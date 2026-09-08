// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-06's other two halves: the arrival highlight, and the widened tap target.
 *
 * Both assert on OBSERVABLE state — the tinted position, and whether a tap actually produced a jump
 * — never on "a listener was installed", which would pass for a handler that does nothing.
 *
 * HARNESS LIMIT, stated so the numbers are not over-read. Robolectric's text metrics are degenerate:
 * every character is 1 px wide, so `"See1 here."` measures 10 px and the marker measures 1. These
 * tests therefore prove the RANGE SEARCH is correct — that a tap off the glyph still finds it, and
 * that a distant tap does not — and NOT that 12 dp is the right distance on a phone. The dp value
 * is device business, and belongs in UAT.
 */
@RunWith(RobolectricTestRunner::class)
class FootnoteTapAndHighlightTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // --- arrival highlight ---------------------------------------------------------------

    @Test
    fun `a jumped-to block is tinted, and a list with no jump behaviour is left alone`() {
        val decorated = RecyclerView(context).also { applyReaderBottomSpacer(it) }
        highlightOnArrival(decorated, 7)
        assertEquals("the block just jumped to is the one tinted", 7, highlightedBlock(decorated))

        val plain = RecyclerView(context)
        highlightOnArrival(plain, 7)
        assertEquals(
            "Guard: without the decoration this must be inert, not crash and not tint — the PDF " +
                "export renders these same views with no reader list around them (Safeguard 3).",
            RecyclerView.NO_POSITION,
            highlightedBlock(plain),
        )
    }

    // --- widened tap target --------------------------------------------------------------

    /**
     * A laid-out TextView carrying a real rendered footnote marker, INSIDE a decorated list.
     *
     * The list matters: the assertions are on whether the tap produced a JUMP, not on what
     * `dispatchTouchEvent` returned. That return value is `true` for a distant tap too — the
     * TextView consumes it for text selection — so asserting on it would have measured the platform
     * rather than this fix. Found by watching the guard test fail.
     */
    private fun markerInList(): Pair<RecyclerView, TextView> {
        val markwon = buildPilcrowMarkwon(context)
        val node = Footnotes.transform(markwon.parse("See[^n] here.\n\n[^n]: body\n")).firstChild!!
        val textView = TextView(context)
        markwon.setParsedMarkdown(textView, markwon.render(node) as Spanned)
        textView.enableGenerousFootnoteTaps()
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        applyReaderBottomSpacer(list)
        list.addView(textView)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        textView.layout(0, 0, 600, textView.measuredHeight)
        return list to textView
    }

    /**
     * The centre of the footnote marker, in view coordinates.
     *
     * The Y matters and is computed rather than guessed: Robolectric wraps even `"See1 here."` onto
     * three lines, so a tap at the view's vertical centre lands on a line the marker is not on and
     * finds nothing. That cost a debugging round; aiming at the marker's own line is what a finger
     * does anyway.
     */
    private fun markerCentre(textView: TextView): Pair<Float, Float> {
        val spanned = textView.text as Spanned
        val span = spanned.getSpans(0, spanned.length, FootnoteJumpSpan::class.java).first()
        val layout = textView.layout
        val start = spanned.getSpanStart(span)
        val line = layout.getLineForOffset(start)
        val left = layout.getPrimaryHorizontal(start)
        val right = layout.getPrimaryHorizontal(spanned.getSpanEnd(span))
        val x = (left + right) / 2f + textView.totalPaddingLeft
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + textView.totalPaddingTop
        return x to y
    }

    private fun tap(textView: TextView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        textView.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `a tap that MISSES the marker still reaches it`() {
        val (list, textView) = markerInList()
        assertNotNull("the sample must actually render a marker", textView.layout)
        val (x, y) = markerCentre(textView)
        tap(textView, x + 2f, y) // off the glyph, inside the slop
        assertTrue(
            "A near-miss must still jump — that is the whole of the tap-target fix. Two taps in " +
                "three missed this marker on a real device.",
            highlightedBlock(list) != RecyclerView.NO_POSITION,
        )
    }

    /**
     * REVIEW FINDING (round 1), reproduced before it was fixed.
     *
     * A regular link sitting within the slop of a footnote marker was SWALLOWED: the exact-hit
     * check looked only for footnotes, so it missed the link, the widened search then found the
     * neighbouring marker, and the link the reader actually tapped never fired. The slop exists to
     * rescue a MISS; it must never overrule a HIT.
     */
    @Test
    fun `a link next to a marker still opens the link, not the footnote`() {
        val markwon = buildPilcrowMarkwon(context)
        val node = Footnotes
            .transform(markwon.parse("[site](https://example.com)[^n] x\n\n[^n]: body\n")).firstChild!!
        val textView = TextView(context)
        markwon.setParsedMarkdown(textView, markwon.render(node) as Spanned)
        textView.enableGenerousFootnoteTaps()
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        applyReaderBottomSpacer(list)
        list.addView(textView)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        textView.layout(0, 0, 600, textView.measuredHeight)

        val spanned = textView.text as Spanned
        val link = spanned.getSpans(0, spanned.length, URLSpan::class.java).first()
        val layout = textView.layout
        val start = spanned.getSpanStart(link)
        val line = layout.getLineForOffset(start)
        val linkLeft = layout.getPrimaryHorizontal(start)
        val linkRight = layout.getPrimaryHorizontal(spanned.getSpanEnd(link))
        val linkX = (linkLeft + linkRight) / 2f + textView.totalPaddingLeft
        val linkY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + textView.totalPaddingTop
        // The link firing IS the evidence, and it is caught rather than avoided: a URLSpan reaches
        // `startActivity`, which throws outside an Activity. That throw means Markwon's movement
        // method handled the tap — which is exactly what must happen.
        var linkFired = false
        try {
            tap(textView, linkX, linkY)
        } catch (expected: android.util.AndroidRuntimeException) {
            linkFired = true
        }

        assertTrue("the LINK must be what handles the tap", linkFired)
        assertEquals(
            "and the footnote beside it must NOT have fired — the slop rescues a miss, never " +
                "overrules a hit.",
            RecyclerView.NO_POSITION,
            highlightedBlock(list),
        )
    }

    @Test
    fun `a tap far from any marker does NOT jump`() {
        val (list, textView) = markerInList()
        val (x, y) = markerCentre(textView)
        tap(textView, x + 400f, y)
        assertEquals(
            "Guard: if every tap jumped, the test above would pass for the wrong reason and " +
                "ordinary prose taps would fire footnotes.",
            RecyclerView.NO_POSITION,
            highlightedBlock(list),
        )
    }
}
