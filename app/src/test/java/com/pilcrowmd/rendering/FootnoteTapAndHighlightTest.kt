// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.RectF
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * M-06's arrival highlight (still used by the back-link and `#heading` links), and M-76's tap target:
 * a tap on or near a footnote marker opens its note in a sheet.
 *
 * Both assert on OBSERVABLE state: the tinted position, and whether a tap actually reached the
 * reader's footnote handler: never on "a listener was installed", which would pass for a handler
 * that does nothing.
 *
 * Native graphics, so lines have real heights: the thumb-sized reach is vertical as well as
 * horizontal, and a tap on "the line below" needs lines that are really there. The dp values are
 * still device business (density 1 here), and belong in UAT.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    /** The definition's top-level block in every fixture below: the paragraph is 0, the note is 1. */
    private val noteBlock = 1

    /** Every call the reader's footnote handler received: the observable a tap produces (M-76). */
    private val sheetCalls = mutableListOf<Int>()

    /**
     * A laid-out TextView carrying a real rendered footnote marker, inside a list that carries the
     * reader's footnote handler, exactly as the reader's RecyclerView does.
     *
     * The assertions are on whether the tap REACHED THE HANDLER, not on what `dispatchTouchEvent`
     * returned. That return value is `true` for a distant tap too: the TextView consumes it for text
     * selection: so asserting on it would have measured the platform rather than this fix.
     *
     * Narrow, so the paragraph wraps onto several lines below the marker's: the vertical reach needs
     * lines to land on that are not the marker's own.
     */
    private fun markerInList(markdown: String = SAMPLE): TextView {
        val markwon = buildPilcrowMarkwon(context)
        val node = Footnotes.transform(markwon.parse(markdown)).firstChild!!
        val textView = TextView(context)
        markwon.setParsedMarkdown(textView, markwon.render(node) as Spanned)
        textView.enableGenerousFootnoteTaps()
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.setFootnoteTapHandler { sheetCalls += it }
        list.addView(textView)
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        textView.layout(0, 0, WIDTH_PX, textView.measuredHeight)
        return textView
    }

    /** The footnote marker's painted bounds, in view coordinates. */
    private fun markerBounds(textView: TextView): RectF {
        val spanned = textView.text as Spanned
        val span = spanned.getSpans(0, spanned.length, FootnoteJumpSpan::class.java).first()
        return spanned.footnoteMarkerBounds(textView.layout, span).apply {
            offset(textView.totalPaddingLeft.toFloat(), textView.totalPaddingTop.toFloat())
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density

    /** Half the 48 dp square a thumb gets around a marker. */
    private fun reachPx(): Float = dp(24f)

    private fun lineAt(textView: TextView, y: Float): Int =
        textView.layout.getLineForVertical((y - textView.totalPaddingTop).toInt())

    private fun tap(textView: TextView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        textView.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `a tap ON the marker opens its note`() {
        val textView = markerInList()
        val marker = markerBounds(textView)
        tap(textView, marker.centerX(), marker.centerY())
        assertEquals("the sheet is asked for the definition's block", listOf(noteBlock), sheetCalls)
    }

    @Test
    fun `a tap that MISSES the marker sideways, inside the thumb square, still opens it`() {
        val textView = markerInList()
        val marker = markerBounds(textView)
        // Off the box, still inside the 24 dp half-square around its centre.
        val x = marker.centerX() + reachPx() - 1f
        assertTrue("precondition: the tap is off the marker", x > marker.right)
        tap(textView, x, marker.centerY())
        assertEquals(
            "A near-miss must still open the note: that is the whole of the tap-target fix. Two " +
                "taps in three missed this marker on a real device.",
            listOf(noteBlock),
            sheetCalls,
        )
    }

    @Test
    fun `a tap on the line BELOW the marker, inside the thumb square, still opens it`() {
        val textView = markerInList()
        val marker = markerBounds(textView)
        val y = marker.centerY() + reachPx() - 1f
        assertTrue(
            "precondition: the tap lands on another line, so only the vertical reach can find the marker",
            lineAt(textView, y) != lineAt(textView, marker.centerY()),
        )
        tap(textView, marker.centerX(), y)
        assertEquals(listOf(noteBlock), sheetCalls)
    }

    /**
     * REVIEW FINDING (round 1), reproduced before it was fixed.
     *
     * A regular link sitting within reach of a footnote marker was SWALLOWED: the exact-hit check
     * looked only for footnotes, so it missed the link, the widened search then found the
     * neighbouring marker, and the link the reader actually tapped never fired. The reach exists to
     * rescue a MISS; it must never overrule a HIT.
     */
    @Test
    fun `a link next to a marker still opens the link, not the footnote`() {
        val textView = markerInList("[site](https://example.com)[^n] x\n\n[^n]: body\n")

        val spanned = textView.text as Spanned
        val link = spanned.getSpans(0, spanned.length, URLSpan::class.java).first()
        val layout = textView.layout
        val start = spanned.getSpanStart(link)
        val line = layout.getLineForOffset(start)
        val linkLeft = layout.getPrimaryHorizontal(start)
        val linkRight = layout.getPrimaryHorizontal(spanned.getSpanEnd(link))
        val linkX = (linkLeft + linkRight) / 2f + textView.totalPaddingLeft
        val linkY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + textView.totalPaddingTop
        assertTrue(
            "precondition: the link is within the marker's reach, or this proves nothing",
            abs(linkX - markerBounds(textView).centerX()) <= reachPx(),
        )
        // The link firing IS the evidence: a URLSpan reaches `startActivity`, which throws outside an
        // Activity. Since M-173 the reader's link resolver catches that throw and logs it, so the
        // log line means Markwon's movement method handled the tap — which is exactly what must happen.
        ShadowLog.clear()
        tap(textView, linkX, linkY)
        val linkFired = ShadowLog.getLogsForTag("ReaderLinkResolver").isNotEmpty()

        assertTrue("the LINK must be what handles the tap", linkFired)
        assertEquals(
            "and the footnote beside it must NOT have fired: the reach rescues a miss, never " +
                "overrules a hit.",
            emptyList<Int>(),
            sheetCalls,
        )
    }

    /**
     * Review finding: a link that WRAPS was measured from the line's left edge on its first line,
     * because its end offset belongs to the next line. A tap on the link's own words, within a
     * marker's reach, missed the link and opened the footnote instead.
     */
    @Test
    fun `a tap on a wrapping link's first-line words, within a marker's reach, opens the link`() {
        val textView = markerInList(
            "text[^1] [a link long enough to wrap onto the next line](https://example.com)\n\n[^1]: body\n",
        )
        val spanned = textView.text as Spanned
        val link = spanned.getSpans(0, spanned.length, URLSpan::class.java).first()
        val layout = textView.layout
        val start = spanned.getSpanStart(link)
        val line = layout.getLineForOffset(start)
        assertTrue("precondition: the link wraps", layout.getLineForOffset(spanned.getSpanEnd(link)) > line)
        val marker = markerBounds(textView)
        // On the link's second glyph, on its first line.
        val x = (layout.getPrimaryHorizontal(start + 1) + layout.getPrimaryHorizontal(start + 2)) / 2f +
            textView.totalPaddingLeft
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + textView.totalPaddingTop
        assertTrue("precondition: the tap is within the marker's reach", abs(x - marker.centerX()) <= reachPx())
        assertTrue("precondition: the tap is off the marker", x > marker.right)

        ShadowLog.clear()
        tap(textView, x, y)
        assertTrue("the LINK must handle the tap", ShadowLog.getLogsForTag("ReaderLinkResolver").isNotEmpty())
        assertEquals("and the footnote must not fire", emptyList<Int>(), sheetCalls)
    }

    @Test
    fun `a tap beyond the thumb square does NOT open the note`() {
        val textView = markerInList()
        val marker = markerBounds(textView)
        tap(textView, marker.centerX(), marker.centerY() + reachPx() + 2f)
        tap(textView, marker.centerX() + reachPx() + 2f, marker.centerY())
        assertEquals(
            "Guard: if every tap opened the note, the tests above would pass for the wrong reason " +
                "and ordinary prose taps would fire footnotes.",
            emptyList<Int>(),
            sheetCalls,
        )
    }

    private companion object {
        const val WIDTH_PX = 200
        const val SAMPLE = "See[^n] here, and then a good many more words so that this paragraph wraps " +
            "onto several lines below the line the marker sits on.\n\n[^n]: body\n"
    }
}
