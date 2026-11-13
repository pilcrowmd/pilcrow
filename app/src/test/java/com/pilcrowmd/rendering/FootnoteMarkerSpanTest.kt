// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.ui.theme.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The reader's boxed footnote marker (M-76), at the span itself.
 *
 * A replacement span is handed a paint WITHOUT the character styles over its range, so the colour
 * and the search highlight have to be read back from the text by the span. These tests draw onto a
 * canvas that records what it was asked to paint, so each guarantee is checked as a painted colour,
 * not as "the span exists".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FootnoteMarkerSpanTest {

    private val marker = DarkColorScheme.footnoteMarker.toArgb()
    private val highlight = DarkColorScheme.searchHighlightFocused.toArgb()

    /** Records the colour of every rectangle, rounded rectangle and text run drawn. */
    private class RecordingCanvas : Canvas(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)) {
        val rects = mutableListOf<Int>()
        val roundRects = mutableListOf<Int>()
        val texts = mutableListOf<Int>()

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += paint.color
        }

        override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
            roundRects += paint.color
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += paint.color
        }
    }

    /** "See1 here." with the "1" boxed and tinted as `tintFootnoteMarkers` tints it. */
    private fun markedText(): SpannableString = SpannableString("See1 here.").apply {
        setSpan(FootnoteMarkerSpan(density = 1f), 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(marker), 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun draw(text: SpannableString): RecordingCanvas {
        val span = text.getSpans(0, text.length, FootnoteMarkerSpan::class.java).single()
        val canvas = RecordingCanvas()
        span.draw(canvas, text, 3, 4, 10f, 0, 30, 40, TextPaint().apply { color = Color.WHITE })
        return canvas
    }

    @Test
    fun `the digits take the marker colour, and the box is that colour at 22 percent`() {
        val canvas = draw(markedText())
        assertEquals("the digits", listOf(marker), canvas.texts)
        assertEquals("the box", listOf(ColorUtils.setAlphaComponent(marker, 56)), canvas.roundRects)
    }

    @Test
    fun `a search match on the digits is still painted under the box`() {
        val text = markedText()
        text.setSpan(BackgroundColorSpan(highlight), 3, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(listOf(highlight), draw(text).rects)
        assertTrue("Guard: no match, no highlight", draw(markedText()).rects.isEmpty())
    }

    /**
     * A replacement span must report its line's metrics when asked, or a line holding only the marker
     * has no height. It reports the SURROUNDING text's metrics, never a taller box: a marker line must
     * stand exactly as tall as the lines around it.
     */
    @Test
    fun `the span reports the surrounding text's line metrics, no taller`() {
        val text = markedText()
        val span = text.getSpans(0, text.length, FootnoteMarkerSpan::class.java).single()
        val paint = TextPaint().apply { textSize = 40f }
        val fm = Paint.FontMetricsInt()
        val width = span.getSize(paint, text, 3, 4, fm)
        assertTrue("it still takes room across the line: digits plus padding and margins", width > 16)
        val expected = paint.fontMetricsInt
        assertTrue("filled: a fresh FontMetricsInt is all zeros", fm.ascent < 0 && fm.descent > 0)
        assertEquals(
            listOf(expected.top, expected.ascent, expected.descent, expected.bottom),
            listOf(fm.top, fm.ascent, fm.descent, fm.bottom),
        )
    }

    @Test
    fun `a block that is only a marker still lays out a line of text height`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val markwon = buildPilcrowMarkwon(context)
        val textView = TextView(context)
        markwon.setParsedMarkdown(
            textView,
            markwon.render(Footnotes.transform(markwon.parse("[^1]\n\n[^1]: body\n")).firstChild!!) as Spanned,
        )
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        textView.layout(0, 0, 400, textView.measuredHeight)
        assertTrue("precondition: the block is the marker alone", textView.text.toString().trim() == "1")
        val line = textView.layout.getLineBottom(0) - textView.layout.getLineTop(0)
        val textHeight = textView.paint.fontMetricsInt.let { it.descent - it.ascent }
        assertTrue("line height $line must be at least the text's own, $textHeight", line >= textHeight)
    }
}
