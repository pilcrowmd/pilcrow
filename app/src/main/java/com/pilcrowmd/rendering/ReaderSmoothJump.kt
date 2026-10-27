// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.animation.ValueAnimator
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** A jump within this many viewports is "near": one smooth scroll. Further is "far": see [ReaderJump]. */
private const val NEAR_LIMIT_SCREENS = 2

/** A laid-out near target is a plain eased scroll; 300 ms reads as travel without making anyone wait. */
private const val NEAR_SCROLL_MILLIS = 300

/** Seek speed for a near target that is not laid out yet: 175 ms a screen, so two screens take 350 ms. */
private const val NEAR_SEEK_MILLIS_PER_SCREEN = 175f

/** The landing of that seek is capped here; LinearSmoothScroller's own would be ~500 ms for a screen. */
private const val NEAR_LANDING_MAX_MILLIS = 200

/**
 * A near seek that has travelled this many viewports beyond its estimate without finding the target
 * gives up and finishes like a far jump. The estimate counts blocks, not pixels, so one huge block
 * the seek cannot see makes the real distance far longer; half a viewport of slack tells that from
 * an estimate that is merely rough.
 */
private const val SEEK_OVERRUN_SCREENS = 0.5f

/** Far jump, step 1: a short run-up in the direction of travel, so the eye sees which way it goes. */
private const val FAR_RUN_UP_MILLIS = 100L

/** Far jump, step 3: the glide back onto the target, long enough to read as a landing. */
private const val FAR_LANDING_MILLIS = 250

/** Far jump: the target is placed this many viewports beyond the one being left before it glides in. */
private const val FAR_APPROACH_SCREENS = 1.5f

/** The run-up is a quarter of a viewport: movement is seen, the screen is not yet crossed. */
private const val FAR_RUN_UP_DIVISOR = 4

/** Below this height there is no room for a run-up, and nothing worth animating. */
private const val MIN_ANIMATED_HEIGHT_PX = 8

/**
 * Bring [position] to the top of the viewport — a jump the eye can follow instead of a teleport.
 *
 * Ends where `scrollToPositionWithOffset(position, 0)` ends, clamp included, which is why the far
 * case below teleports with that very call and then only ever undoes its own offset. Falls back to
 * that call, instantly, when animations are off (a system setting people turn on for vestibular
 * reasons, so it must win) or there is nothing to animate.
 */
fun smoothJumpTo(recyclerView: RecyclerView, position: Int) {
    val jump = recyclerView.readerJump()
    jump.cancel()
    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
    if (layoutManager == null) {
        recyclerView.smoothScrollToPosition(position)
        return
    }
    val animated = ValueAnimator.areAnimatorsEnabled() &&
        recyclerView.adapter != null &&
        position in 0 until (recyclerView.adapter?.itemCount ?: 0) &&
        recyclerView.height >= MIN_ANIMATED_HEIGHT_PX
    if (animated) jump.start(layoutManager, position) else layoutManager.scrollToPositionWithOffset(position, 0)
}

/**
 * Stop a jump in flight because something else is about to position the list (a rebuild, a search
 * match, the jump buttons): left running, its teleport or glide would carry the list away from
 * where that caller just put it. No-op when no jump was ever started.
 */
fun cancelReaderJump(recyclerView: RecyclerView) {
    (recyclerView.getTag(R.id.reader_jump) as? ReaderJump)?.cancel()
}

private fun RecyclerView.readerJump(): ReaderJump =
    (getTag(R.id.reader_jump) as? ReaderJump) ?: ReaderJump(this).also { setTag(R.id.reader_jump, it) }

/**
 * The one jump in flight on a reader list, if any. A new jump cancels it first.
 *
 * NEAR (within [NEAR_LIMIT_SCREENS] viewports): a single scroll that decelerates onto the target.
 * FAR: scrolling the whole way would take seconds, or fly past the content too fast to read, so the
 * list is moved instead — a short run-up, a teleport to the target, a hop back 1.5 viewports, and a
 * glide in. What the eye sees is "away, then arriving", in well under half a second at any distance.
 */
private class ReaderJump(private val recyclerView: RecyclerView) {

    init {
        // A detached list reports no more scroll states, so a far jump's listener would never go.
        recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) = abandon()
        })
    }

    /** Bumped by every [cancel], so a layout or scroll callback from a cancelled jump does nothing. */
    private var token = 0
    private var farJump: RecyclerView.OnScrollListener? = null

    /** Stop whatever is moving and forget the jump. */
    fun cancel() {
        abandon() // before stopScroll: it reports IDLE
        recyclerView.stopScroll()
    }

    /** Forget the jump without touching the list: for when the scroll has ended, or the user has it. */
    private fun abandon() {
        token++
        farJump?.let(recyclerView::removeOnScrollListener)
        farJump = null
    }

    fun start(layoutManager: LinearLayoutManager, position: Int) {
        val viewport = recyclerView.height
        val distance = distanceToTop(layoutManager, position)
        when {
            distance == null -> layoutManager.scrollToPositionWithOffset(position, 0)
            abs(distance) > NEAR_LIMIT_SCREENS * viewport ->
                startFar(layoutManager, position, sign(distance.toFloat()).toInt())
            layoutManager.findViewByPosition(position) != null ->
                recyclerView.smoothScrollBy(0, distance, DecelerateInterpolator(2f), NEAR_SCROLL_MILLIS)
            else -> startNear(layoutManager, position, abs(distance))
        }
    }

    /**
     * Signed distance, in px, to put [position]'s top at the viewport's top; null when nothing is
     * laid out to measure from. Exact when the target is laid out, otherwise the number of blocks
     * between times the average laid-out block height — the same estimate RecyclerView's own
     * scrollbar makes, and all that a far jump needs, since it only has to tell near from far.
     */
    private fun distanceToTop(layoutManager: LinearLayoutManager, position: Int): Int? {
        layoutManager.findViewByPosition(position)?.let {
            return it.top - layoutManager.getTopDecorationHeight(it) - recyclerView.paddingTop
        }
        val first = layoutManager.findFirstVisibleItemPosition()
        val firstChild = layoutManager.findViewByPosition(first) ?: return null
        val children = (0 until recyclerView.childCount).map { recyclerView.getChildAt(it) }
        val average = children.sumOf { it.height + it.verticalMarginsPx() }.toFloat() / children.size
        val firstTop = firstChild.top - layoutManager.getTopDecorationHeight(firstChild)
        return ((position - first) * average).roundToInt() + firstTop - recyclerView.paddingTop
    }

    private fun startNear(layoutManager: LinearLayoutManager, position: Int, estimate: Int) {
        val mine = token
        val giveUpAfter = estimate + (SEEK_OVERRUN_SCREENS * recyclerView.height).roundToInt()
        val scroller = NearScroller(recyclerView, position, giveUpAfter) { direction ->
            // The target was far after all: finish from where the list is, as a far jump does.
            if (token == mine) teleportAndGlide(layoutManager, position, direction)
        }
        layoutManager.startSmoothScroll(scroller)
    }

    private fun startFar(layoutManager: LinearLayoutManager, position: Int, direction: Int) {
        val viewport = recyclerView.height
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // The user took over: leave the list wherever their finger puts it.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) return abandon()
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                rv.removeOnScrollListener(this)
                teleportAndGlide(layoutManager, position, direction)
            }
        }
        farJump = listener
        recyclerView.addOnScrollListener(listener)
        recyclerView.smoothScrollBy(
            0,
            direction * (viewport / FAR_RUN_UP_DIVISOR),
            AccelerateInterpolator(),
            FAR_RUN_UP_MILLIS.toInt(),
        )
        // A run-up that could not start (frozen layout) would never report IDLE: go straight on.
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
            listener.onScrollStateChanged(recyclerView, RecyclerView.SCROLL_STATE_IDLE)
        }
    }

    /**
     * The far jump's second half, also where a near seek that lost its way ends: teleport to
     * [position], hop back [FAR_APPROACH_SCREENS] viewports against [direction] (the way the list
     * was travelling), and glide in. Replaces the jump's listener, so the user taking over still ends it.
     */
    private fun teleportAndGlide(layoutManager: LinearLayoutManager, position: Int, direction: Int) {
        val mine = token
        val viewport = recyclerView.height
        var hopping = false
        var gliding = false
        var consumed = 0
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (hopping) consumed += dy
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                val userTookOver = newState == RecyclerView.SCROLL_STATE_DRAGGING
                val glideEnded = gliding && newState == RecyclerView.SCROLL_STATE_IDLE
                if (userTookOver || glideEnded) abandon()
            }
        }
        farJump?.let(recyclerView::removeOnScrollListener)
        farJump = listener
        recyclerView.addOnScrollListener(listener)
        layoutManager.scrollToPositionWithOffset(position, 0)
        recyclerView.doOnNextLayout {
            if (token != mine) return@doOnNextLayout
            // The end or start of the document can refuse part of the hop, so what comes
            // back is what was actually taken, not what was asked for.
            hopping = true
            recyclerView.scrollBy(0, -direction * (FAR_APPROACH_SCREENS * viewport).roundToInt())
            hopping = false
            if (consumed == 0) return@doOnNextLayout abandon()
            gliding = true
            recyclerView.smoothScrollBy(0, -consumed, DecelerateInterpolator(2f), FAR_LANDING_MILLIS)
        }
    }
}

private fun View.verticalMarginsPx(): Int =
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.topMargin + it.bottomMargin } ?: 0

/**
 * A seek to a block that is not laid out yet, tuned for the near case: fast enough to cross two
 * viewports in about 350 ms, and a landing capped at [NEAR_LANDING_MAX_MILLIS]. LinearSmoothScroller's
 * defaults are tuned for a list of small rows and take over a second on reader-sized blocks.
 */
private class NearScroller(
    private val recyclerView: RecyclerView,
    position: Int,
    private val giveUpAfterPx: Int,
    private val onLostWay: (direction: Int) -> Unit,
) : LinearSmoothScroller(recyclerView.context) {

    private var travelled = 0

    init {
        targetPosition = position
    }

    override fun getVerticalSnapPreference() = SNAP_TO_START

    override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics) =
        NEAR_SEEK_MILLIS_PER_SCREEN / recyclerView.height

    override fun calculateTimeForDeceleration(dx: Int) =
        min(super.calculateTimeForDeceleration(dx), NEAR_LANDING_MAX_MILLIS)

    override fun onSeekTargetStep(dx: Int, dy: Int, state: RecyclerView.State, action: Action) {
        travelled += dy
        if (abs(travelled) > giveUpAfterPx) {
            stop()
            onLostWay(sign(travelled.toFloat()).toInt())
            return
        }
        super.onSeekTargetStep(dx, dy, state, action)
    }
}
