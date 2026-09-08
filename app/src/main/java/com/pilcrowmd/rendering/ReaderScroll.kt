// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The reader list's scroll geometry — the two halves of M-06, kept together because changing
 * either one alone breaks the other.
 *
 * THE BUG. `scrollToPositionWithOffset(target, 0)` asks for a block at the TOP of the viewport.
 * RecyclerView cannot scroll past the end of its content, so for a block at the end of the
 * document that request is CLAMPED and the block lands at the BOTTOM instead. A footnote
 * definition is ALWAYS the last block, so a footnote jump has never been able to land — it stops
 * about a screen short, every time, in every document.
 *
 * THE COUPLING, which is why these are one file. Jump-to-bottom depends on that same clamp:
 * `scrollToPosition(last)` also asks for the top, and only the clamp turns it into "the end of the
 * document at the bottom of the screen". Remove the clamp for the footnote jump and jump-to-bottom
 * silently starts leaving a screen of dead space below the last block. So the spacer comes with
 * [scrollToDocumentEnd], which states that resting position explicitly instead of inheriting it
 * from a limit that no longer exists.
 */

/**
 * Install M-06's two reader behaviours — the bottom spacer and the arrival highlight — as one
 * decoration, because they are one interaction: the spacer gets the definition to the top of the
 * screen, and the highlight tells the eye it has arrived.
 *
 * [highlightColor] is passed in rather than chosen here (Safeguard 4): the Markwon visitor that
 * emits footnote markers is shared by the reader (Dark/Light) and the PDF export (Print), so
 * nothing on this path may pick a colour for itself. The caller is the composable that owns the
 * scheme.
 */
fun applyReaderJumpBehaviour(recyclerView: RecyclerView, @ColorInt highlightColor: Int) {
    recyclerView.addItemDecoration(ReaderJumpDecoration(highlightColor))
}

/** Back-compat entry point for the spacer alone — used by tests that do not exercise the highlight. */
fun applyReaderBottomSpacer(recyclerView: RecyclerView) {
    applyReaderJumpBehaviour(recyclerView, android.graphics.Color.TRANSPARENT)
}

/**
 * One viewport of headroom below the LAST block, plus a brief tint over a block that was jumped to.
 *
 * AN ITEM DECORATION, NOT PADDING, AND THE DIFFERENCE IS NOT A STYLE CHOICE. `clipToPadding = false`
 * plus a bottom padding looks like the obvious way to make the last block reachable and is wrong:
 * padding changes where RecyclerView CLIPS, but LinearLayoutManager still measures its available
 * space as `height - paddingBottom`, so the padding comes straight out of the reading area.
 * Measured, not reasoned: with `paddingBottom` set to a full viewport, ZERO children lay out
 * (`findFirstVisibleItemPosition() == -1`). A decoration grows the last item's occupied space
 * instead, which extends the scroll range and leaves the viewport whole.
 *
 * One viewport rather than a measured `viewport - lastBlockHeight`: the minimum depends on the last
 * block's height, which is not known until it is bound, and it is read from [RecyclerView.getHeight]
 * at offset time so it follows rotation without being recomputed anywhere.
 */
private class ReaderJumpDecoration(@ColorInt highlightColor: Int) : RecyclerView.ItemDecoration() {

    private val highlightPaint = Paint().apply { color = highlightColor }

    /** The block to tint, or [RecyclerView.NO_POSITION] when nothing has been jumped to. */
    var highlightedPosition: Int = RecyclerView.NO_POSITION
        private set

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val lastPosition = state.itemCount - 1
        val position = parent.getChildAdapterPosition(view)
        // `position >= 0` is load-bearing, not defensive noise: on an empty list `lastPosition` is
        // -1, and `getChildAdapterPosition` returns NO_POSITION — also -1 — for a view on its way
        // out. Without this the two compare equal and a departing view is handed a whole viewport
        // of offset mid-flight. Raised in review.
        outRect.bottom = if (position >= 0 && position == lastPosition) parent.height else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val target = highlightedPosition
        if (target == RecyclerView.NO_POSITION) return
        // UNDER the text, never over it: `onDrawOver` would wash the definition out at the exact
        // moment the reader is trying to read it.
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (parent.getChildAdapterPosition(child) != target) continue
            canvas.drawRect(
                child.left.toFloat(),
                child.top.toFloat(),
                child.right.toFloat(),
                child.bottom.toFloat(),
                highlightPaint,
            )
        }
    }

    /**
     * Tint [position] until [HIGHLIGHT_MILLIS] have passed, then clear it.
     *
     * A stale tint is dropped by [clearReaderHighlight], which the reader calls when it swaps the
     * adapter — a pinch-zoom rebuilds it many times a second, and an index meaning "the definition"
     * in one document means an unrelated block in the next. Doing it at the swap rather than
     * through a registered `AdapterDataObserver` means there is no observer lifetime to get wrong.
     * Raised in review.
     */
    fun highlight(recyclerView: RecyclerView, position: Int) {
        highlightedPosition = position
        recyclerView.invalidateItemDecorations()
        recyclerView.removeCallbacks(clear)
        recyclerView.postDelayed(clear, HIGHLIGHT_MILLIS)
    }

    /** Drop the tint immediately, without waiting for the timer. */
    fun clearHighlight() {
        highlightedPosition = RecyclerView.NO_POSITION
    }

    private val clear = Runnable { highlightedPosition = RecyclerView.NO_POSITION }

    private companion object {
        /** Long enough for the eye to find the block, short enough not to sit on the page. */
        const val HIGHLIGHT_MILLIS = 1_200L
    }
}

/** The decoration installed by [applyReaderJumpBehaviour], or null when there is none. */
private fun RecyclerView.jumpDecoration(): ReaderJumpDecoration? {
    for (index in 0 until itemDecorationCount) {
        val decoration = getItemDecorationAt(index)
        if (decoration is ReaderJumpDecoration) return decoration
    }
    return null
}

/** Tint the block just jumped to, so the eye lands on it instead of hunting. Inert without a decoration. */
fun highlightOnArrival(recyclerView: RecyclerView, position: Int) {
    recyclerView.jumpDecoration()?.highlight(recyclerView, position)
}

/** Drop any tint. Called when the reader swaps the adapter, so an index cannot outlive its document. */
fun clearReaderHighlight(recyclerView: RecyclerView) {
    recyclerView.jumpDecoration()?.clearHighlight()
}

/** The position currently tinted, for tests. [RecyclerView.NO_POSITION] when nothing is. */
fun highlightedBlock(recyclerView: RecyclerView): Int =
    recyclerView.jumpDecoration()?.highlightedPosition ?: RecyclerView.NO_POSITION

/**
 * Whether the DOCUMENT itself overflows the viewport — the question the floating jump controls ask,
 * which is about content and must not be answered by [applyReaderJumpBehaviour]'s headroom.
 *
 * MEASURES THE DOCUMENT, NOT THE SCROLL POSITION. Three wrong versions came before this one, and
 * all three are worth recording because each looked obviously right and each was caught by a
 * different mechanism.
 *
 * `range > height` — the spacer inflates the range, so EVERY document reported as scrollable and
 * the controls appeared on short ones. Caught by six `link_and_image` goldens.
 *
 * `range - viewport > height` — LinearLayoutManager ESTIMATES that range from laid-out children, so
 * at the top of a document the final block (the only one carrying the spacer) contributes nothing;
 * the subtraction then under-reported by a screen and the controls VANISHED on a document between
 * one and two screens tall. Caught by review.
 *
 * `lastBlock.bottom > height` — `bottom` is relative to the viewport and SHRINKS as the reader
 * scrolls down, so it hit exactly `height` at the end of the document and the controls vanished
 * again, worst of all on a single tall block where `findFirstVisibleItemPosition()` stays 0
 * throughout. Caught by review. **Any formulation that reads a scroll-dependent coordinate has this
 * bug**, which is why this one reads a height instead.
 *
 * `last.bottom - first.top` is the content's height and is the same number at every scroll offset.
 * When either end is not laid out they could not both fit, so the document overflows by definition.
 */
fun documentOverflowsViewport(recyclerView: RecyclerView): Boolean {
    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return false
    val itemCount = recyclerView.adapter?.itemCount ?: 0
    if (itemCount == 0) return false
    // Looked up AFTER the count guard, so `itemCount - 1` is never the -1 that would ask the
    // layout manager for position -1.
    val firstBlock = layoutManager.findViewByPosition(0)
    val lastBlock = layoutManager.findViewByPosition(itemCount - 1)
    // Either end absent means LinearLayoutManager could not fit them both on screen.
    if (firstBlock == null || lastBlock == null) return true
    // MARGINS IN, DECORATIONS OUT — and the asymmetry is the whole design of this function.
    // Margins are part of the document: `adapter_code_block.xml` and `adapter_latex_block.xml`
    // carry 4dp top and bottom on the item root, so ignoring them under-reports a code-block-ended
    // document by up to 8dp and mis-answers a document that overflows by less than that. Raised in
    // review. Decorations are NOT part of the document: the only one here is the spacer, which is
    // headroom — counting it would report every document as overflowing, which is the first bug
    // this function ever had and the one six goldens caught.
    val documentHeight = (lastBlock.bottom + lastBlock.bottomMarginPx()) -
        (firstBlock.top - firstBlock.topMarginPx())
    return documentHeight > recyclerView.height
}

private fun View.topMarginPx(): Int = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

private fun View.bottomMarginPx(): Int = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

/**
 * Scroll so the document's LAST block rests against the bottom edge of the viewport — what
 * "jump to bottom" has always meant, now stated rather than inherited from the clamp
 * [applyReaderBottomSpacer] removes.
 *
 * Two steps because the last block's height is not knowable until it is laid out: bring it into
 * view, then close the remaining gap once it has a height. Both steps are no-ops on an empty list.
 */
fun scrollToDocumentEnd(recyclerView: RecyclerView) {
    val lastPosition = (recyclerView.adapter?.itemCount ?: 0) - 1
    if (lastPosition < 0) return
    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
    val alreadyLaidOut = layoutManager.findViewByPosition(lastPosition)
    if (alreadyLaidOut != null) {
        recyclerView.scrollBy(0, alreadyLaidOut.bottom - recyclerView.height)
        return
    }
    // NO "pending" flag guarding this. An earlier version used one and could DEADLOCK: when the
    // list is already at the end, `scrollToPosition` triggers no layout, the callback never runs,
    // the flag is never cleared, and the button is dead for the rest of the session. Raised in
    // review. Splitting on whether the block is laid out removes the need for a flag entirely —
    // the aligned case is a `scrollBy(0, 0)` no-op, so a rapid double-tap costs nothing, and the
    // deferred branch only runs when `scrollToPosition` has guaranteed a layout.
    recyclerView.scrollToPosition(lastPosition)
    recyclerView.doOnNextLayout {
        val lastChild = layoutManager.findViewByPosition(lastPosition) ?: return@doOnNextLayout
        recyclerView.scrollBy(0, lastChild.bottom - recyclerView.height)
    }
}
