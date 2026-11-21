// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.TextPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * M-163: a reader selection runs across blocks. Gestures go to the list, in its coordinates, as the
 * window sends them, in the production adapter. Native graphics, for real text metrics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSelectionTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val markwon = buildPilcrowMarkwon(activity)

    /**
     * Robolectric gives every test a fresh clock, but not `AnimationUtils`' process-wide record of
     * the latest animation time it handed out. These tests end late on the clock (a long press, then
     * the menu's animation), which leaves that record ahead of the next test's clock: until that
     * clock catches up, animation time stands still there, and scrollers with it. The first test of
     * `ReaderSmoothJumpTest`, the class that runs after this one, ran out of its time that way.
     */
    @After
    fun forgetAnimationTime() {
        val state = AnimationUtils::class.java.getDeclaredField("sAnimationState").apply { isAccessible = true }
        (state.get(null) as ThreadLocal<*>).remove()
    }

    private fun reader(markdown: String, heightPx: Int = 4000): RecyclerView {
        val state = DetailsState()
        val list = RecyclerView(activity)
        list.layoutManager = ReaderLayoutManager(activity)
        list.addOnItemTouchListener(SecondFingerEndsSelection)
        list.setTag(R.id.details_state, state)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(activity, markwon, details = state)
        val document = ReaderDocument.transform(markwon.parse(markdown))
        state.load(markdown, Details.sections(document))
        val blocks = AdapterBlocks.of(document)
        adapter.setParsedMarkdown(markwon, document)
        list.adapter = adapter
        list.installReaderSelection { blocks.getOrNull(it)?.let { node -> readerBlockText(markwon, node) } }
        activity.setContentView(list)
        idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, heightPx)
        idle()
        return list
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun RecyclerView.selection() = readerSelection()!!

    private fun RecyclerView.text(block: Int) = findViewHolderForAdapterPosition(block)!!.itemView as TextView

    /** The middle of the character at [offset] of [block]'s text, in the list's coordinates. */
    private fun RecyclerView.at(block: Int, offset: Int): Pair<Float, Float> {
        val tv = text(block)
        val layout = tv.layout
        val line = layout.getLineForOffset(offset)
        val x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2f
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        return tv.left + tv.totalPaddingLeft + x to tv.top + tv.totalPaddingTop + y
    }

    private fun send(list: RecyclerView, action: Int, downTime: Long, at: Pair<Float, Float>) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, at.first, at.second, 0)
        list.dispatchTouchEvent(event)
        event.recycle()
    }

    /** Long-press at [from], then drag to each of [to] and lift. */
    private fun longPressDrag(list: RecyclerView, from: Pair<Float, Float>, vararg to: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        send(list, MotionEvent.ACTION_DOWN, down, from)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        to.forEach { send(list, MotionEvent.ACTION_MOVE, down, it) }
        send(list, MotionEvent.ACTION_UP, down, to.lastOrNull() ?: from)
        idle()
    }

    private fun tap(list: RecyclerView, at: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        send(list, MotionEvent.ACTION_DOWN, down, at)
        send(list, MotionEvent.ACTION_UP, down, at)
        idle()
    }

    private val helloWorld = "# Hello\n\nWorld wide web\n"

    @Test
    fun `a long-press selects the word under the finger`() {
        val list = reader(helloWorld)
        longPressDrag(list, list.at(1, 7))
        assertEquals("wide", list.selection().selectedText())
    }

    @Test
    fun `dragging on from a heading's word reaches into the paragraph below`() {
        val list = reader(helloWorld)
        longPressDrag(list, list.at(0, 2), list.at(1, 2), list.at(1, 3))
        assertEquals("Hello\n\nWor", list.selection().selectedText())
    }

    @Test
    fun `dragging up from a paragraph's word reaches into the heading above`() {
        val list = reader(helloWorld)
        longPressDrag(list, list.at(1, 1), list.at(0, 1))
        assertEquals("ello\n\nWorld", list.selection().selectedText())
    }

    @Test
    fun `the end handle drags the selection into another block`() {
        val list = reader("One two\n\nThree four\n\nFive six\n")
        longPressDrag(list, list.at(0, 1))
        assertEquals("One", list.selection().selectedText())
        // The handle hangs below the line, under the selection's end.
        val (endX, endY) = list.at(0, 2)
        val tv = list.text(0)
        val handle = endX + 4f to endY + tv.lineHeight
        drag(list, handle, list.at(2, 1).let { it.first to it.second + tv.lineHeight })
        assertEquals("One two\n\nThree four\n\nFi", list.selection().selectedText())
    }

    /** A plain drag, no hold: what moving a handle is. */
    private fun drag(list: RecyclerView, from: Pair<Float, Float>, to: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        send(list, MotionEvent.ACTION_DOWN, down, from)
        send(list, MotionEvent.ACTION_MOVE, down, to)
        send(list, MotionEvent.ACTION_UP, down, to)
        idle()
    }

    @Test
    fun `a first touch is never half of a double-tap, so a drag scrolls`() {
        val list = reader(helloWorld, heightPx = 100)
        val down = SystemClock.uptimeMillis()
        val (x, y) = list.at(1, 1)
        send(list, MotionEvent.ACTION_DOWN, down, x to y)
        send(list, MotionEvent.ACTION_MOVE, down, x to y - 60f)
        assertEquals(RecyclerView.SCROLL_STATE_DRAGGING, list.scrollState)
        send(list, MotionEvent.ACTION_CANCEL, down, x to y - 60f)
        assertNull(list.selection().range)
    }

    @Test
    fun `a tap ends the selection and does not open the link under it`() {
        val list = reader("Go to [site](https://example.com) now.\n\nMore text.\n")
        longPressDrag(list, list.at(1, 1))
        assertNotNull(list.selection().range)
        val tv = list.text(0)
        val spanned = tv.text as android.text.Spanned
        val link = spanned.getSpans(0, spanned.length, URLSpan::class.java).first()
        tap(list, list.at(0, spanned.getSpanStart(link) + 1))
        assertNull(list.selection().range)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a long-press on a link selects it and does not open it`() {
        val list = reader("Go to [site](https://example.com) now.\n")
        val spanned = list.text(0).text as android.text.Spanned
        val link = spanned.getSpans(0, spanned.length, URLSpan::class.java).first()
        longPressDrag(list, list.at(0, spanned.getSpanStart(link) + 1))
        assertEquals("site", list.selection().selectedText())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `the block's own long-press is off, so only one selection shows`() {
        val list = reader(helloWorld)
        val tv = list.text(1)
        assertTrue("TalkBack and keyboards keep the platform's selection", tv.isTextSelectable)
        assertFalse(tv.isLongClickable)
        longPressDrag(list, list.at(1, 7))
        assertFalse(tv.hasSelection())
    }

    @Test
    fun `a pinch beginning ends the selection`() {
        val list = reader(helloWorld)
        longPressDrag(list, list.at(1, 7))
        assertNotNull(list.selection().range)
        endBlockSelection(list)
        assertNull(list.selection().range)
    }

    @Test
    fun `a new document ends the selection`() {
        val list = reader(helloWorld)
        longPressDrag(list, list.at(1, 7))
        list.adapter!!.notifyDataSetChanged()
        idle()
        assertNull(list.selection().range)
    }

    @Test
    fun `Select all takes blocks that are off screen, from their rendered text`() {
        val middle = (1..40).joinToString("\n\n") { "Paragraph $it." }
        val list = reader("First words.\n\n$middle\n\nLast words.\n", heightPx = 400)
        list.selection().selectAll()
        list.scrollToPosition(41)
        idle()
        val text = list.selection().selectedText()
        assertTrue(text, text.startsWith("First words.\n\nParagraph 1.\n\n"))
        assertTrue(text, text.endsWith("Paragraph 40.\n\nLast words."))
        assertEquals(TextPoint(0, 0), list.selection().range!!.start)
    }
}
