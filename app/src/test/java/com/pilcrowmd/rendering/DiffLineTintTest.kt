// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.export.PdfContentLayoutBuilder
import com.pilcrowmd.export.parseTopLevelBlocks
import com.pilcrowmd.ui.theme.CodeSyntaxColors
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightCodeSyntax
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * M-216: a `diff` block's added and removed lines get a full-line background tint, drawn by a span over
 * the token's range. The text is never touched.
 *
 * Two kinds of check. The span checks call the span the way a Layout does, against a recording canvas,
 * and read the colour and the rectangle it fills. The pixel checks draw a real TextView and read the
 * pixel at the right edge of each line's text area: they are what proves the tint is not painted over by
 * Markwon's own code-block fill, which a span-only check cannot see (a `LineBackgroundSpan` passed every
 * span check and drew nothing).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiffLineTintTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val diffBlock = "```diff\n context\n-val removed\n+val added\n```"
    private val kotlinBlock = "```kotlin\nval a = 1\n+ 2\n- 3\n```"

    /** A canvas that records the rectangles drawn, with the colour each was painted in. */
    private class RecordingCanvas : Canvas() {
        val rects = mutableListOf<Pair<Rect, Int>>()
        override fun drawRect(r: Rect, paint: Paint) {
            rects += Rect(r) to paint.color
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()) to paint.color
        }
    }

    private fun render(markwon: Markwon, markdown: String): Spanned = markwon.render(markwon.parse(markdown)) as Spanned

    /** Rectangles the tint spans over [probe]'s line draw, as (rect, colour). */
    private fun tintsOver(text: Spanned, probe: String): List<Pair<Rect, Int>> {
        val at = text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        val layout = StaticLayout.Builder.obtain("x", 0, 1, TextPaint(), WIDTH).build()
        val canvas = RecordingCanvas()
        text.getSpans(at, at + probe.length, DiffLineBackgroundSpan::class.java).forEach {
            val end = at + probe.length
            it.drawLeadingMargin(canvas, Paint(), 0, 1, TOP, BASELINE, BOTTOM, text, at, end, true, layout)
        }
        return canvas.rects
    }

    private fun assertTint(text: Spanned, probe: String, expected: Color) {
        val tints = tintsOver(text, probe)
        assertEquals("'$probe' should be covered by exactly one tint", 1, tints.size)
        assertEquals("'$probe' tint colour", expected.toArgb(), tints.single().second)
        assertEquals("'$probe' tint spans the whole line width", Rect(0, TOP, WIDTH, BOTTOM), tints.single().first)
    }

    private fun assertDiffTints(markwon: Markwon, syntax: CodeSyntaxColors) {
        val text = render(markwon, diffBlock)
        assertTint(text, "-val removed", syntax.deletedLineBg!!)
        assertTint(text, "+val added", syntax.insertedLineBg!!)
        assertTrue("the context line must not be tinted", tintsOver(text, " context").isEmpty())
    }

    @Test
    fun diffLinesAreTintedInDark() =
        assertDiffTints(buildPilcrowMarkwon(context, DarkColorScheme), DarkColorScheme.codeSyntax)

    @Test
    fun diffLinesAreTintedInLight() =
        assertDiffTints(buildPilcrowMarkwon(context, LightColorScheme), LightColorScheme.codeSyntax)

    @Test
    fun thePdfInstanceUsesLightsTints() = assertDiffTints(buildPrintMarkwon(context), LightCodeSyntax)

    /** A scheme that leaves both roles null draws no tint, and its text is the same as with the tints. */
    @Test
    fun theTintNeverChangesTheText() {
        val untinted: PilcrowColorScheme = DarkColorScheme.copy(
            codeSyntax = DarkColorScheme.codeSyntax.copy(insertedLineBg = null, deletedLineBg = null),
        )
        val plain = render(buildPilcrowMarkwon(context, untinted), diffBlock)
        val tinted = render(buildPilcrowMarkwon(context, DarkColorScheme), diffBlock)
        assertTrue("the null-role render must carry no tint", tintsOver(plain, "-val removed").isEmpty())
        assertTrue("the tinted render must carry one", tintsOver(tinted, "-val removed").isNotEmpty())
        assertEquals(plain.toString(), tinted.toString())
    }

    /** Control: `+` and `-` at the start of a line in another language are not a diff. */
    @Test
    fun aKotlinBlockWithPlusAndMinusLinesGetsNoTint() {
        val text = render(buildPilcrowMarkwon(context, DarkColorScheme), kotlinBlock)
        assertTrue(text.getSpans(0, text.length, DiffLineBackgroundSpan::class.java).isEmpty())
    }

    /**
     * Draws [markdown] in a real TextView [viewWidth] px wide and returns the pixel at the right edge of
     * the text area, on each line of the paragraph that holds [probe].
     */
    private fun edgePixels(markwon: Markwon, markdown: String, probe: String, viewWidth: Int): List<Int> {
        val view = TextView(context)
        markwon.setParsedMarkdown(view, render(markwon, markdown))
        val exactly = View.MeasureSpec.makeMeasureSpec(viewWidth, View.MeasureSpec.EXACTLY)
        view.measure(exactly, View.MeasureSpec.UNSPECIFIED)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return edgePixelsOf(view, probe)
    }

    /** Draws the already laid-out [view] and reads the right-edge pixel of each line of [probe]'s paragraph. */
    private fun edgePixelsOf(view: TextView, probe: String): List<Int> {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val layout = view.layout
        val at = view.text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the drawn text" }
        val newline = view.text.indexOf('\n', at)
        val first = layout.getLineForOffset(at)
        val last = layout.getLineForOffset((if (newline < 0) view.text.length else newline) - 1)
        return (first..last).map {
            bitmap.getPixel(view.width - view.paddingRight - 2, (layout.getLineTop(it) + layout.getLineBottom(it)) / 2)
        }
    }

    private fun assertPainted(markwon: Markwon, syntax: CodeSyntaxColors, panel: Color) {
        assertEquals(listOf(syntax.deletedLineBg!!.toArgb()), edgePixels(markwon, diffBlock, "-val removed", 600))
        assertEquals(listOf(syntax.insertedLineBg!!.toArgb()), edgePixels(markwon, diffBlock, "+val added", 600))
        assertEquals(listOf(panel.toArgb()), edgePixels(markwon, diffBlock, " context", 600))
    }

    @Test
    fun theTintIsPaintedInDark() = assertPainted(
        buildPilcrowMarkwon(context, DarkColorScheme),
        DarkColorScheme.codeSyntax,
        DarkColorScheme.codeBlockBg,
    )

    @Test
    fun theTintIsPaintedInLight() = assertPainted(
        buildPilcrowMarkwon(context, LightColorScheme),
        LightColorScheme.codeSyntax,
        LightColorScheme.codeBlockBg,
    )

    @Test
    fun theTintIsPaintedInThePdfInstance() =
        assertPainted(buildPrintMarkwon(context), LightCodeSyntax, LightColorScheme.codeBlockBg)

    private fun assertPaintedOn(codeView: TextView, syntax: CodeSyntaxColors) {
        assertEquals(listOf(syntax.deletedLineBg!!.toArgb()), edgePixelsOf(codeView, "-val removed"))
        assertEquals(listOf(syntax.insertedLineBg!!.toArgb()), edgePixelsOf(codeView, "+val added"))
    }

    /**
     * The reader's own route: the real [FencedCodeBlockEntry] bind (`setParsedMarkdown`, the highlight
     * step, then [SearchHighlighter]) with a query that matches a word on the `+` line, so the highlighter
     * runs and re-handles the text. The tint depends on span order, so this must still draw it.
     */
    @Test
    fun theTintSurvivesTheCodeBlockBindAndAnActiveSearch() {
        val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
        val search = SearchHighlight(
            query = "added",
            focusedPosition = 0,
            otherColor = 0xFFFFCC00.toInt(),
            focusedColor = 0xFFFF8800.toInt(),
        )
        val entry = FencedCodeBlockEntry(context, colorScheme = DarkColorScheme, searchHighlight = search)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, markwon.parse(diffBlock).firstChild as FencedCodeBlock)
        val width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        holder.itemView.measure(width, View.MeasureSpec.UNSPECIFIED)
        holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)

        val text = holder.codeView.text as Spanned
        val highlights = text.getSpans(0, text.length, BackgroundColorSpan::class.java)
        assertEquals("the search highlight must have run", 1, highlights.size)
        assertPaintedOn(holder.codeView, DarkColorScheme.codeSyntax)
    }

    /** The PDF's route: the print Markwon through [PdfContentLayoutBuilder], which binds the same entry. */
    @Test
    fun theTintIsPaintedThroughThePdfBlockBuilder() {
        val markwon = buildPrintMarkwon(context)
        val node = parseTopLevelBlocks(markwon, diffBlock).single()
        val inflater = LayoutInflater.from(context)
        val block = PdfContentLayoutBuilder(context).inflateMeasuredBlock(markwon, node, inflater, 1000, 1f)
        block.layout(0, 0, block.measuredWidth, block.measuredHeight)
        assertPaintedOn(block.findViewById(R.id.code_text), LightCodeSyntax)
    }

    /** A removed line that wraps is tinted on every one of its lines. */
    @Test
    fun aWrappedLineIsTintedOnEveryLine() {
        val long = "-" + "removed ".repeat(60)
        val pixels = edgePixels(buildPilcrowMarkwon(context, DarkColorScheme), "```diff\n$long\n```", "-removed", 300)
        assertTrue("the fixture must wrap, got ${pixels.size} line(s)", pixels.size >= 2)
        assertEquals(List(pixels.size) { DarkColorScheme.codeSyntax.deletedLineBg!!.toArgb() }, pixels)
    }

    private companion object {
        const val WIDTH = 500
        const val TOP = 10
        const val BASELINE = 25
        const val BOTTOM = 30
    }
}
