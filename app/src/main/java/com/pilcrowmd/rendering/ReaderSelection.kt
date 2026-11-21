// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.ReaderSelectionRange
import com.pilcrowmd.domain.markdown.TextPoint
import io.noties.markwon.Markwon
import org.commonmark.node.Node
import kotlin.math.abs
import kotlin.math.max

/**
 * Touch selection in the reader that may run across blocks (M-163): long-press or double-tap selects
 * a word, then the finger or either handle extends it into any other block, and Copy takes the lot.
 *
 * Every block is its own view, so the platform's selection stops at the block's edge. This one lives
 * on the list instead: a touch listener for the gestures, an item decoration for the highlight and
 * the handles, and a floating action mode for the menu. A place is a [TextPoint], a block and an
 * offset in the text it shows. A block with no selectable text (code, a table, a picture) is
 * selected whole or not at all.
 *
 * Block TextViews stay selectable ([enableBlockSelection]) for TalkBack and keyboards; only their
 * long-press is taken away, so the two selections never show at once.
 *
 * @param textOf a block's shown text when its view is not on screen; null for none (a closed section).
 */
@Suppress("TooManyFunctions") // The gestures, the drawing and the menu of one selection.
class ReaderSelection(private val list: RecyclerView, private val textOf: (Int) -> CharSequence?) :
    RecyclerView.ItemDecoration(),
    RecyclerView.OnItemTouchListener {

    /** Told when a selection appears or goes, so Back can end it first. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    /** The selection, or null for none. */
    var range: ReaderSelectionRange? = null
        private set(value) {
            val was = field != null
            field = value
            list.invalidate()
            if (was != (value != null)) onActiveChanged?.invoke(value != null)
        }

    private val config = ViewConfiguration.get(list.context)
    private val density = list.resources.displayMetrics.density
    private val handles: Pair<Drawable?, Drawable?> = themeHandles(list.context)
    private val wholeBlockColor = overlayColor(themeHighlight(list.context))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val origin = Rect()
    private val bounds = RectF()

    private enum class Drag { NONE, WORD, HANDLE }

    private var drag = Drag.NONE

    // The word a long-press or double-tap chose: dragging on from it never deselects it.
    private var word: ReaderSelectionRange? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f

    // From the finger to the text point a handle stands for: the finger is on the handle, below the line.
    private var handleDx = 0f
    private var handleDy = 0f
    private var lastTapUp = NO_TAP
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var autoScrolling = false
    private var actionMode: ActionMode? = null
    private var finishingMode = false
    private var observed: RecyclerView.Adapter<*>? = null

    private val longPress = Runnable { onLongPress() }

    private val observer = object : RecyclerView.AdapterDataObserver() {
        // A new document or a search re-binds everything; offsets would point into other text.
        override fun onChanged() = clear()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = clear()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = clear()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = clear()
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            val step = edgeStep(lastY)
            if (drag == Drag.NONE || step == 0) {
                autoScrolling = false
                return
            }
            list.scrollBy(0, step)
            extendTo(lastX, lastY)
            list.postOnAnimation(this)
        }
    }

    init {
        list.addItemDecoration(this)
        list.addOnItemTouchListener(this)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                actionMode?.invalidateContentRect()
            }
        })
        list.setTag(R.id.reader_selection, this)
    }

    /** Ends the selection, its menu and any gesture building one. */
    fun clear() {
        list.removeCallbacks(longPress)
        drag = Drag.NONE
        word = null
        finishMenu()
        if (range != null) range = null
    }

    /** The selected text, as Copy puts it on the clipboard. */
    fun selectedText(): String = range?.text(::blockText).orEmpty()

    // ---- gestures -------------------------------------------------------------------------------

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                watchAdapter()
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                moved = false
                if (range != null && grabHandle(e.x, e.y)) return true
                if (isDoubleTap(e)) {
                    val point = textPointAt(e.x, e.y)
                    if (point != null) {
                        lastTapUp = NO_TAP
                        startWord(point)
                        return true
                    }
                }
                if (textPointAt(e.x, e.y) != null) {
                    list.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && beyondSlop(e)) {
                    moved = true
                    list.removeCallbacks(longPress)
                }
                if (drag != Drag.NONE) {
                    extendTo(e.x, e.y)
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> clear()
            MotionEvent.ACTION_UP -> {
                list.removeCallbacks(longPress)
                if (drag != Drag.NONE) {
                    // Held still past the long-press and lifted: the word is chosen; the menu comes now.
                    endDrag()
                    return true
                }
                if (!moved && e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout()) {
                    lastTapUp = e.eventTime
                    lastTapX = e.x
                    lastTapY = e.y
                    // A tap ends a selection, and is spent on that: no link opens under it.
                    if (range != null) {
                        clear()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> list.removeCallbacks(longPress)
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                extendTo(e.x, e.y)
                if (!autoScrolling && edgeStep(e.y) != 0) {
                    autoScrolling = true
                    list.postOnAnimation(autoScroll)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> clear()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    private fun beyondSlop(e: MotionEvent): Boolean =
        abs(e.x - downX) > config.scaledTouchSlop || abs(e.y - downY) > config.scaledTouchSlop

    private fun isDoubleTap(e: MotionEvent): Boolean {
        val slop = config.scaledDoubleTapSlop
        return lastTapUp != NO_TAP &&
            e.eventTime - lastTapUp < ViewConfiguration.getDoubleTapTimeout() &&
            abs(e.x - lastTapX) < slop &&
            abs(e.y - lastTapY) < slop
    }

    private fun onLongPress() {
        if (moved) return
        textPointAt(downX, downY)?.let(::startWord)
    }

    private fun startWord(point: TextPoint) {
        val text = blockText(point.block) ?: return
        val (from, to) = ReaderSelectionRange.wordAt(text, point.offset)
        val chosen = ReaderSelectionRange(TextPoint(point.block, from), TextPoint(point.block, to))
        endPlatformSelection(list)
        finishMenu()
        word = chosen
        range = chosen
        drag = Drag.WORD
        list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        list.parent?.requestDisallowInterceptTouchEvent(true)
    }

    /** Moves the end being dragged to the text under ([x], [y]), the finger's place. */
    @Suppress("ReturnCount")
    private fun extendTo(x: Float, y: Float) {
        val current = range ?: return
        val point = pointAt(x + handleDx, y + handleDy) ?: return
        val next = when (drag) {
            Drag.WORD -> {
                val chosen = word ?: return
                if (point < chosen.start) {
                    ReaderSelectionRange(chosen.end, point)
                } else {
                    ReaderSelectionRange(chosen.start, maxOf(point, chosen.end))
                }
            }
            Drag.HANDLE -> current.copy(focus = point).takeIf { point != current.anchor } ?: return
            Drag.NONE -> return
        }
        if (next != current) range = next
    }

    private fun endDrag() {
        if (drag == Drag.NONE) return
        drag = Drag.NONE
        handleDx = 0f
        handleDy = 0f
        showMenu()
    }

    /** Takes hold of a handle under ([x], [y]), keeping the other end where it is. */
    private fun grabHandle(x: Float, y: Float): Boolean {
        val current = range ?: return false
        // The end handle first: on a one-character selection the two overlap, and extending is likelier.
        val (isStart, anchor) = listOf(false, true).firstNotNullOfOrNull { isStart ->
            anchorOf(if (isStart) current.start else current.end, isStart)
                ?.takeIf { handleHitRect(it, isStart).contains(x, y) }
                ?.let { isStart to it }
        } ?: return false
        handleDx = anchor.x - x
        handleDy = (anchor.top + anchor.bottom) / 2f - y
        // The other end stays put as the anchor; the grabbed one becomes the focus.
        range = if (isStart) {
            ReaderSelectionRange(current.end, current.start)
        } else {
            ReaderSelectionRange(current.start, current.end)
        }
        drag = Drag.HANDLE
        finishMenu()
        list.parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    /** Scroll speed while a drag holds the finger near the top or bottom edge; 0 away from them. */
    private fun edgeStep(y: Float): Int {
        val edge = EDGE_DP * density
        val max = MAX_SCROLL_DP * density
        return when {
            y < edge -> -(max * (edge - y) / edge).toInt().coerceAtLeast(1)
            y > list.height - edge -> (max * (y - (list.height - edge)) / edge).toInt().coerceAtLeast(1)
            else -> 0
        }
    }

    // ---- places ---------------------------------------------------------------------------------

    /** The text point under ([x], [y]) when that is selectable text; null elsewhere. */
    private fun textPointAt(x: Float, y: Float): TextPoint? {
        val child = list.findChildViewUnder(x, y) ?: return null
        val tv = selectableText(child) ?: return null
        originOf(tv)
        val inside = x >= origin.left && x < origin.left + tv.width && y >= origin.top && y < origin.top + tv.height
        return if (inside) pointAt(x, y) else null
    }

    /** The text point nearest ([x], [y]): in the block under it, or the closest block above or below. */
    @Suppress("ReturnCount")
    private fun pointAt(x: Float, y: Float): TextPoint? {
        val child = nearestChild(y) ?: return null
        val block = list.getChildAdapterPosition(child)
        if (block == RecyclerView.NO_POSITION) return null
        val tv = selectableText(child)
        val layout = tv?.layout
        if (tv == null || layout == null) {
            return TextPoint(block, if (y < child.top + child.height / 2f) 0 else TextPoint.END)
        }
        originOf(tv)
        val lx = x - origin.left - tv.totalPaddingLeft + tv.scrollX
        val ly = (y - origin.top - tv.totalPaddingTop + tv.scrollY).toInt()
        val line = when {
            ly < 0 -> return TextPoint(block, 0)
            ly >= layout.height -> return TextPoint(block, tv.text.length)
            else -> layout.getLineForVertical(ly)
        }
        return TextPoint(block, layout.getOffsetForHorizontal(line, lx))
    }

    private fun nearestChild(y: Float): View? {
        var best: View? = null
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.height == 0 || child.visibility != View.VISIBLE) continue
            val distance = when {
                y < child.top -> child.top - y
                y >= child.bottom -> y - child.bottom
                else -> return child
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = child
            }
        }
        return best
    }

    /** Where a handle for [point] stands, in the list's coordinates: x, and the line's top and bottom. */
    private class Anchor(val x: Float, val top: Float, val bottom: Float)

    private fun anchorOf(point: TextPoint, isStart: Boolean): Anchor? {
        val child = list.findViewHolderForAdapterPosition(point.block)?.itemView ?: return null
        if (child.height == 0 || child.parent !== list) return null
        val tv = selectableText(child)
        val layout = tv?.layout
        if (tv == null || layout == null) {
            return if (isStart) {
                Anchor(child.left + child.paddingLeft.toFloat(), child.top.toFloat(), child.top.toFloat())
            } else {
                Anchor(child.right - child.paddingRight.toFloat(), child.bottom.toFloat(), child.bottom.toFloat())
            }
        }
        val offset = point.offset.coerceIn(0, tv.text.length)
        var line = layout.getLineForOffset(offset)
        // An end at a wrap belongs to the line it ends, not the next line's start.
        val x = if (!isStart && line > 0 && offset == layout.getLineStart(line)) {
            line--
            layout.getLineRight(line)
        } else {
            layout.getPrimaryHorizontal(offset)
        }
        originOf(tv)
        val dx = origin.left + tv.totalPaddingLeft - tv.scrollX
        val dy = origin.top + tv.totalPaddingTop - tv.scrollY
        return Anchor(dx + x, dy + layout.getLineTop(line).toFloat(), dy + layout.getLineBottom(line).toFloat())
    }

    private fun originOf(view: View) {
        origin.set(0, 0, 0, 0)
        list.offsetDescendantRectToMyCoords(view, origin)
    }

    /** A block's shown text: its view's while on screen, else what [textOf] renders. */
    private fun blockText(block: Int): CharSequence? {
        val view = list.findViewHolderForAdapterPosition(block)?.itemView
        if (view != null && view.height == 0) return null
        return view?.let(::selectableText)?.text ?: textOf(block)
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val current = range ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (selectionPathOf(child, current, path)) {
                paint.color = selectableText(child)?.highlightColor?.let(::overlayColor) ?: wholeBlockColor
                c.drawPath(path, paint)
            }
        }
        drawHandle(c, current.start, isStart = true)
        drawHandle(c, current.end, isStart = false)
    }

    /** Puts the selected part of [child] into [out], in the list's coordinates; false when none of it is. */
    @Suppress("ReturnCount")
    private fun selectionPathOf(child: View, current: ReaderSelectionRange, out: Path): Boolean {
        val block = list.getChildAdapterPosition(child)
        if (child.height == 0 || block < current.start.block || block > current.end.block) return false
        out.reset()
        val tv = selectableText(child)
        val layout = tv?.layout
        if (tv == null || layout == null) {
            if (current.rangeIn(block, 1) == null) return false
            val l = child.left.toFloat()
            out.addRect(l, child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat(), Path.Direction.CW)
            return true
        }
        val (from, to) = current.rangeIn(block, tv.text.length) ?: return false
        layout.getSelectionPath(from, to, out)
        originOf(tv)
        out.offset(
            (origin.left + tv.totalPaddingLeft - tv.scrollX).toFloat(),
            (origin.top + tv.totalPaddingTop - tv.scrollY).toFloat(),
        )
        return true
    }

    private fun drawHandle(c: Canvas, point: TextPoint, isStart: Boolean) {
        val drawable = (if (isStart) handles.first else handles.second) ?: return
        val anchor = anchorOf(point, isStart) ?: return
        drawable.bounds = handleRect(drawable, anchor, isStart)
        drawable.draw(c)
    }

    /** The platform's placement: a start handle's hot spot is 3/4 across, an end handle's 1/4. */
    private fun handleRect(drawable: Drawable, anchor: Anchor, isStart: Boolean): Rect {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        val left = (anchor.x - if (isStart) w * HANDLE_HOTSPOT_START else w * HANDLE_HOTSPOT_END).toInt()
        val top = anchor.bottom.toInt()
        return Rect(left, top, left + w, top + h)
    }

    private fun handleHitRect(anchor: Anchor, isStart: Boolean): RectF {
        val drawable = (if (isStart) handles.first else handles.second)
        val minimum = MIN_TOUCH_DP * density
        val rect = drawable?.let { RectF(handleRect(it, anchor, isStart)) }
            ?: RectF(anchor.x, anchor.bottom, anchor.x, anchor.bottom)
        val growX = max(0f, (minimum - rect.width()) / 2f)
        val growY = max(0f, (minimum - rect.height()) / 2f)
        rect.inset(-growX, -growY)
        return rect
    }

    // ---- menu -----------------------------------------------------------------------------------

    private fun showMenu() {
        if (range == null) return
        actionMode?.let {
            it.invalidateContentRect()
            return
        }
        actionMode = list.startActionMode(menu, ActionMode.TYPE_FLOATING)
    }

    private fun finishMenu() {
        val mode = actionMode ?: return
        actionMode = null
        finishingMode = true
        try {
            mode.finish()
        } finally {
            finishingMode = false
        }
    }

    private fun copy() {
        val text = selectedText()
        if (text.isEmpty()) return
        val clipboard = list.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /** Selects the whole document, every block. */
    fun selectAll() {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return
        range = ReaderSelectionRange(TextPoint(0, 0), TextPoint(count - 1, TextPoint.END))
        actionMode?.invalidateContentRect()
    }

    private val menu = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(Menu.NONE, android.R.id.selectAll, 1, android.R.string.selectAll)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            android.R.id.copy -> {
                copy()
                clear()
                true
            }
            android.R.id.selectAll -> {
                selectAll()
                true
            }
            else -> false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            // Closed by the system, not by a drag or a new selection: the selection goes with it.
            if (!finishingMode && drag == Drag.NONE) clear()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            selectionBounds(outRect)
        }
    }

    /** The visible part of the selection, in the list's coordinates; the list's top centre when none is. */
    private fun selectionBounds(out: Rect) {
        val current = range
        var found = false
        val all = RectF()
        if (current != null) {
            for (i in 0 until list.childCount) {
                if (!selectionPathOf(list.getChildAt(i), current, path)) continue
                path.computeBounds(bounds, true)
                if (found) all.union(bounds) else all.set(bounds)
                found = true
            }
        }
        if (found) {
            all.intersect(0f, 0f, list.width.toFloat(), list.height.toFloat())
            all.round(out)
        } else {
            out.set(list.width / 2, 0, list.width / 2, 0)
        }
    }

    private fun watchAdapter() {
        val adapter = list.adapter
        if (adapter === observed) return
        observed?.unregisterAdapterDataObserver(observer)
        observed = adapter
        adapter?.registerAdapterDataObserver(observer)
        clear()
    }

    private companion object {
        const val EDGE_DP = 48f
        const val MAX_SCROLL_DP = 16f
        const val MIN_TOUCH_DP = 48f
        const val HANDLE_HOTSPOT_START = 0.75f
        const val HANDLE_HOTSPOT_END = 0.25f
        const val OVERLAY_ALPHA = 0x66
        const val OPAQUE = 0xFF
        const val NO_TAP = -1L

        /** Drawn over the text, so an opaque highlight is made see-through. */
        fun overlayColor(color: Int): Int = if (Color.alpha(color) == OPAQUE) {
            Color.argb(OVERLAY_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        } else {
            color
        }

        fun themeHighlight(context: Context): Int {
            val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorHighlight))
            return try {
                a.getColor(0, overlayColor(Color.GRAY))
            } finally {
                a.recycle()
            }
        }

        fun themeHandles(context: Context): Pair<Drawable?, Drawable?> {
            val a = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.textSelectHandleLeft, android.R.attr.textSelectHandleRight),
            )
            return try {
                a.getDrawable(0) to a.getDrawable(1)
            } finally {
                a.recycle()
            }
        }
    }
}

/** The selectable text of a reader block: the view itself, or the first selectable TextView inside it. */
internal fun selectableText(view: View): TextView? {
    if (view is TextView) return view.takeIf { it.isTextSelectable }
    if (view !is ViewGroup) return null
    for (i in 0 until view.childCount) {
        selectableText(view.getChildAt(i))?.let { return it }
    }
    return null
}

/** Puts a [ReaderSelection] on this reader list. */
fun RecyclerView.installReaderSelection(textOf: (Int) -> CharSequence?): ReaderSelection = ReaderSelection(this, textOf)

/** This reader list's [ReaderSelection], if it has one. */
fun RecyclerView.readerSelection(): ReaderSelection? = getTag(R.id.reader_selection) as? ReaderSelection

/**
 * A block's text as the reader shows it, for a block whose view is off screen. Null when it cannot
 * be rendered: Copy then leaves the block out rather than fail (Safeguard 3).
 */
@Suppress("TooGenericExceptionCaught")
fun readerBlockText(markwon: Markwon, node: Node): CharSequence? = try {
    if (node is PlainTextChunk) node.literal else markwon.render(node)
} catch (e: Exception) {
    Log.w("ReaderSelection", "block text failed: ${e.message}")
    null
}
