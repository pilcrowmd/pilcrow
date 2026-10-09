// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.components.beginPinchScaleGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * M-157: long-press selects inside one block, and the reader's taps survive it.
 *
 * The row's done-rule is "test the three together" — link taps, footnote taps (exact and near-miss)
 * and pinch — so every test here runs in the PRODUCTION adapter, in an Activity, and asserts on what
 * a tap actually did: an activity started, a list that jumped, a section that opened. Taps are sent
 * as DOWN then UP, as a finger does, so a selectable block's first tap (which takes focus) is covered.
 *
 * Native graphics, because the legacy shadow's text metrics are degenerate: `getOffsetForHorizontal`
 * answers the line end for every x, so the platform's own link lookup (which this reuses) could not
 * find a link anywhere but at the end of a line.
 *
 * Long-press is real time: DOWN, the looper run past the platform's long-press timeout, then UP
 * carrying the original down time. That is what both guards read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlockSelectionTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val markwon = buildPilcrowMarkwon(activity)

    private class Reader(val list: RecyclerView, val details: DetailsState) {
        fun view(position: Int): View = list.findViewHolderForAdapterPosition(position)!!.itemView
        fun text(position: Int): TextView = view(position) as TextView
    }

    private fun reader(markdown: String, heightPx: Int = 4000): Reader {
        val state = DetailsState()
        val list = RecyclerView(activity)
        list.layoutManager = ReaderLayoutManager(activity)
        list.addOnItemTouchListener(SecondFingerEndsSelection)
        list.setTag(R.id.details_state, state)
        applyReaderBottomSpacer(list)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(activity, markwon, details = state)
        val document = ReaderDocument.transform(markwon.parse(markdown))
        state.load(markdown, Details.sections(document))
        adapter.setParsedMarkdown(markwon, document)
        list.adapter = adapter
        activity.setContentView(list)
        settle(list, heightPx)
        return Reader(list, state)
    }

    private fun settle(list: RecyclerView, heightPx: Int = 4000) {
        idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, heightPx)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Centre of the text range [start, end) on its first line, in the TextView's own coordinates. */
    private fun centreOf(textView: TextView, start: Int, end: Int): Pair<Float, Float> {
        val layout = textView.layout
        val line = layout.getLineForOffset(start)
        val left = layout.getPrimaryHorizontal(start)
        val sameLine = layout.getLineForOffset(end) == line
        val right = if (sameLine) layout.getPrimaryHorizontal(end) else layout.getLineRight(line)
        val x = (left + right) / 2f + textView.totalPaddingLeft
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + textView.totalPaddingTop
        return x to y
    }

    private inline fun <reified T : Any> spanCentre(textView: TextView): Pair<Float, Float> {
        val spanned = textView.text as Spanned
        val span = spanned.getSpans(0, spanned.length, T::class.java).first()
        return centreOf(textView, spanned.getSpanStart(span), spanned.getSpanEnd(span))
    }

    private fun send(view: View, action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun tap(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, down, x, y)
        send(view, MotionEvent.ACTION_UP, down, x, y)
        idle()
    }

    private fun longPress(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, down, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        send(view, MotionEvent.ACTION_UP, down, x, y)
        idle()
    }

    /**
     * Two fingers held still past the long-press time, as a slow pinch starts: DOWN at [first], the
     * second finger at [second] before the timeout, then the wait. Both points are in [view]'s
     * coordinates, and the events go to [view], as the parent would send them.
     */
    private fun twoFingerHold(view: View, first: Pair<Float, Float>, second: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, down, first.first, first.second)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        val props = Array(2) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = arrayOf(first, second).map { (x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(down, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
    }

    private fun startedUrl(): String? = shadowOf(activity).nextStartedActivity?.dataString

    private val linkDoc = "Go to [site](https://example.com) now.\n"

    // --- links ------------------------------------------------------------------------------

    @Test
    fun `a link on a selectable block opens on its first tap`() {
        val r = reader(linkDoc)
        val tv = r.text(0)
        assertTrue("the block must be selectable, or this tests nothing new", tv.isTextSelectable)
        val (x, y) = spanCentre<URLSpan>(tv)
        tap(tv, x, y)
        assertEquals("https://example.com", startedUrl())
    }

    @Test
    fun `a lone ACTION_UP on a link still opens it, as the footnote tests send taps`() {
        val tv = reader(linkDoc).text(0)
        val (x, y) = spanCentre<URLSpan>(tv)
        send(tv, MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), x, y)
        assertEquals("https://example.com", startedUrl())
    }

    @Test
    fun `a long-press on a link does not open it`() {
        val tv = reader(linkDoc).text(0)
        val (x, y) = spanCentre<URLSpan>(tv)
        longPress(tv, x, y)
        assertTrue("the long-press must select: that is the feature", tv.hasSelection())
        assertNull("a long-press selects; it must never open the link under the finger", startedUrl())
    }

    @Test
    fun `a tap on a link while text is selected ends the selection and does not open the link`() {
        val tv = reader(linkDoc).text(0)
        Selection.setSelection(tv.text as Spannable, 0, 2)
        val (x, y) = spanCentre<URLSpan>(tv)
        tap(tv, x, y)
        assertNull(startedUrl())
    }

    // --- footnotes --------------------------------------------------------------------------

    private val noteDoc = "See[^n] here.\n\n[^n]: body\n"

    /** A point just past the marker's right edge: off the glyph, well inside the 12dp slop. */
    private fun nearMarker(textView: TextView): Pair<Float, Float> {
        val spanned = textView.text as Spanned
        val span = spanned.getSpans(0, spanned.length, FootnoteJumpSpan::class.java).first()
        val end = spanned.getSpanEnd(span)
        val (_, y) = centreOf(textView, spanned.getSpanStart(span), end)
        val right = textView.layout.getPrimaryHorizontal(end) + textView.totalPaddingLeft
        return right + 4f * textView.resources.displayMetrics.density to y
    }

    @Test
    fun `a footnote marker jumps on an exact tap and on a near miss`() {
        val exact = reader(noteDoc)
        val (x, y) = spanCentre<FootnoteJumpSpan>(exact.text(0))
        tap(exact.text(0), x, y)
        assertNotEquals("exact tap", RecyclerView.NO_POSITION, highlightedBlock(exact.list))

        val near = reader(noteDoc)
        val (nx, ny) = nearMarker(near.text(0))
        tap(near.text(0), nx, ny)
        assertNotEquals("near-miss tap", RecyclerView.NO_POSITION, highlightedBlock(near.list))
    }

    @Test
    fun `a long-press near a marker does not jump`() {
        val r = reader(noteDoc)
        val (x, y) = nearMarker(r.text(0))
        longPress(r.text(0), x, y)
        assertTrue("the long-press must select: that is the feature", r.text(0).hasSelection())
        assertEquals(RecyclerView.NO_POSITION, highlightedBlock(r.list))
    }

    /**
     * A press held past the long-press time whose long-press selected nothing. On a phone that is a
     * long-press the platform did not turn into a selection; here the long-press callback is simply
     * never run, so only the release's own down time says how long it was held. Neither the link nor
     * the near-miss footnote may act on it.
     */
    private fun heldRelease(view: View, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis() - ViewConfiguration.getLongPressTimeout() - 100L
        send(view, MotionEvent.ACTION_UP, down, x, y)
        idle()
    }

    @Test
    fun `a held press that selected nothing does not open a link`() {
        val tv = reader(linkDoc).text(0)
        val (x, y) = spanCentre<URLSpan>(tv)
        heldRelease(tv, x, y)
        assertNull(startedUrl())
    }

    @Test
    fun `a held press near a marker that selected nothing does not jump`() {
        val r = reader(noteDoc)
        val (x, y) = nearMarker(r.text(0))
        heldRelease(r.text(0), x, y)
        assertEquals(RecyclerView.NO_POSITION, highlightedBlock(r.list))
    }

    @Test
    fun `a long-press on a marker does not jump`() {
        val r = reader(noteDoc)
        val (x, y) = spanCentre<FootnoteJumpSpan>(r.text(0))
        longPress(r.text(0), x, y)
        assertTrue("the long-press must select: that is the feature", r.text(0).hasSelection())
        assertEquals(RecyclerView.NO_POSITION, highlightedBlock(r.list))
    }

    /**
     * A near-miss tap consumes the UP, so the TextView never sees the release that would cancel the
     * long-press its DOWN armed. In the reader the jump usually scrolls the block away, which cancels it
     * too, so this fixture has no list: the block stays on screen and only the near-miss path's own
     * cancel can stop a word being selected half a second after the tap.
     */
    @Test
    fun `a near-miss footnote tap does not select a word half a second later`() {
        val node = ReaderDocument.transform(markwon.parse(noteDoc)).firstChild!!
        val tv = TextView(activity)
        markwon.setParsedMarkdown(tv, markwon.render(node) as Spanned)
        tv.enableGenerousFootnoteTaps()
        tv.enableBlockSelection()
        activity.setContentView(FrameLayout(activity).apply { addView(tv) })
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tv.layout(0, 0, 1080, tv.measuredHeight)
        val (x, y) = nearMarker(tv)
        val down = SystemClock.uptimeMillis()
        send(tv, MotionEvent.ACTION_DOWN, down, x, y)
        send(tv, MotionEvent.ACTION_UP, down, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        assertFalse("a tap must not turn into a selection after it has ended", tv.hasSelection())
    }

    @Test
    fun `a footnote definition body is selectable`() {
        val r = reader(noteDoc)
        val body = r.view(1).findViewById<TextView>(R.id.footnote_body)
        assertTrue(body.isTextSelectable)
        assertSame(ReaderMovementMethod, body.movementMethod)
    }

    // --- details header ---------------------------------------------------------------------

    private val detailsDoc = "Before.\n\n<details>\n<summary>Section</summary>\n\nHidden body.\n</details>\n\nAfter."

    @Test
    fun `a details header opens on its first tap and is not selectable`() {
        val r = reader(detailsDoc)
        val header = r.text(1)
        assertFalse("a header is tapped, not selected", header.isTextSelectable)
        tap(header, header.width / 2f, header.height / 2f)
        assertTrue("ONE tap must open it; a selectable header spends its first tap on focus", r.details.isExpanded(1))
    }

    @Test
    fun `selection follows the block a recycled holder now shows, both ways`() {
        val r = reader(detailsDoc)
        val adapter = r.list.adapter!!
        val prose = r.list.findViewHolderForAdapterPosition(0)!!
        val header = r.list.findViewHolderForAdapterPosition(1)!!
        assertEquals(
            "same view type, so these holders really are swapped by recycling",
            prose.itemViewType,
            header.itemViewType,
        )

        // A header's holder, re-bound as a paragraph, and the paragraph's holder re-bound as a header.
        @Suppress("UNCHECKED_CAST")
        val a = adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        a.bindViewHolder(header, 0)
        a.bindViewHolder(prose, 1)
        val nowProse = header.itemView as TextView
        val nowHeader = prose.itemView as TextView
        assertTrue(nowProse.isTextSelectable)
        assertSame(ReaderMovementMethod, nowProse.movementMethod)
        assertFalse(nowHeader.isTextSelectable)
        assertTrue("the header keeps its tap", nowHeader.hasOnClickListeners() && nowHeader.isClickable)

        // And a plain re-bind (search step, pinch commit) leaves a paragraph selectable.
        adapter.notifyDataSetChanged()
        settle(r.list)
        assertTrue(r.text(0).isTextSelectable)
        assertSame(ReaderMovementMethod, r.text(0).movementMethod)
    }

    // --- focus ------------------------------------------------------------------------------

    /**
     * Put [view]'s window in or out of touch mode, as the platform does on the first touch or key.
     * `Instrumentation.setInTouchMode(false)` is ignored by Robolectric (measured), so this calls the
     * window's own switch.
     */
    private fun setTouchMode(view: View, inTouchMode: Boolean) {
        val root = ReflectionHelpers.callInstanceMethod<Any>(view, "getViewRootImpl")
        ReflectionHelpers.callInstanceMethod<Boolean>(
            root,
            "ensureTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, inTouchMode),
        )
        idle()
    }

    /** A tall first block, scrolled so its top is above the viewport. */
    private fun tallBlockScrolled(): Pair<Reader, Int> {
        val words = (1..400).joinToString(" ") { "word$it" }
        val r = reader("$words\n\nAfter.\n", heightPx = 600)
        r.list.scrollBy(0, 300)
        idle()
        val topBefore = r.view(0).top
        assertTrue("the fixture's block must start above the viewport", topBefore < 0)
        return r to topBefore
    }

    @Test
    fun `in touch mode a first tap on a tall block does not scroll the page to its top`() {
        val (r, topBefore) = tallBlockScrolled()
        setTouchMode(r.list, true)
        assertTrue("precondition: the list must be in touch mode", r.list.isInTouchMode)
        tap(r.list, 540f, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue("the tap must have focused the block", r.view(0).isFocused)
        assertEquals("a tap that takes focus must not move the page", topBefore, r.view(0).top)
    }

    @Test
    fun `out of touch mode focus still scrolls the block into view`() {
        val (r, _) = tallBlockScrolled()
        setTouchMode(r.list, false)
        assertFalse("precondition: keyboard or D-pad, not touch", r.list.isInTouchMode)
        assertTrue(r.view(0).requestFocus())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("keyboard focus brings the block's top into view", 0, r.list.getChildAt(0).top)
    }

    // --- TalkBack ---------------------------------------------------------------------------

    /**
     * Pinned to API 30: measured on 29, 30, 34, 35 and 36, only 29 and 30 add the click action to a
     * focused selectable TextView, so on newer levels this would pass with the delegate deleted. The
     * delegate is harmless where the platform already leaves the action out.
     */
    @Test
    @Config(sdk = [30])
    fun `TalkBack is not told a plain block can be activated, and a header still can`() {
        val r = reader(detailsDoc)
        fun actions(view: View): List<Int> {
            val info = view.createAccessibilityNodeInfo()
            return info.actionList.map { it.id }
        }
        // Focused, as a block is once it has been tapped or long-pressed: that is when the platform
        // itself offers a selectable TextView's click.
        r.text(0).requestFocus()
        assertTrue(r.text(0).isFocused)
        val prose = actions(r.text(0))
        assertFalse("no 'double-tap to activate' on a paragraph", AccessibilityNodeInfo.ACTION_CLICK in prose)
        assertTrue("selection is still offered", AccessibilityNodeInfo.ACTION_SET_SELECTION in prose)
        assertTrue(AccessibilityNodeInfo.ACTION_CLICK in actions(r.text(1)))
    }

    // --- pinch ------------------------------------------------------------------------------

    private val twoBlocks = "First paragraph with words.\n\nSecond paragraph with words.\n"

    @Test
    fun `a second finger on the same block cancels the long-press`() {
        val tv = reader(twoBlocks).text(0)
        val y = tv.height / 2f
        twoFingerHold(tv, tv.totalPaddingLeft + 30f to y, tv.totalPaddingLeft + 300f to y)
        assertFalse("two fingers are a pinch, never a selection", tv.hasSelection())
    }

    @Test
    fun `a second finger on another block cancels the first block's long-press`() {
        val r = reader(twoBlocks)
        val first = r.text(0)
        val second = r.text(1)
        twoFingerHold(
            r.list,
            first.totalPaddingLeft + 30f to first.top + first.height / 2f,
            second.totalPaddingLeft + 30f to second.top + second.height / 2f,
        )
        assertFalse("two fingers are a pinch, never a selection", first.hasSelection())
    }

    @Test
    fun `a pinch beginning ends any selection`() {
        val r = reader(linkDoc)
        val tv = r.text(0)
        Selection.setSelection(tv.text as Spannable, 0, 4)
        assertTrue(tv.hasSelection())
        beginPinchScaleGesture(r.list)
        assertFalse(tv.hasSelection())
    }
}
