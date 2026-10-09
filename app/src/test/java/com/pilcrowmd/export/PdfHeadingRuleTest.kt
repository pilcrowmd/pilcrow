// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.buildPilcrowMarkwon
import com.pilcrowmd.rendering.buildPrintMarkwon
import io.noties.markwon.core.spans.HeadingSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * M-191: the rule under a level-1 or level-2 heading runs to the right edge of the text column in an
 * exported PDF. Markwon's own heading span ends the rule at the canvas width, and the export draws
 * each block on the 595-unit page canvas scaled down to points, so the rule stopped about a quarter
 * of the way across.
 *
 * The heading block is built by the export's own path and drawn the way the exporter draws it: on a
 * canvas the width of the A4 page, inside the margin, scaled from the device's pixels to points. 450
 * dpi is the density the defect was measured at. The heading text is one letter, so the rightmost
 * painted pixel on the page can only be the rule's end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-450dpi")
class PdfHeadingRuleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val densityDpi get() = context.resources.displayMetrics.densityDpi

    // The exporter's measure width and draw scale (PdfExporter.paginateAndRenderStreaming).
    private val columnWidthPx get() = (pointsToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT) / PdfExporter.PRINT_SCALE).toInt()
    private val scale get() = PdfExporter.POINTS_PER_INCH.toFloat() / densityDpi * PdfExporter.PRINT_SCALE

    private fun pointsToPx(pt: Int) = pt * densityDpi / PdfExporter.POINTS_PER_INCH.toFloat()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /** The heading's text view's right text edge, in the block's own pixels. */
    private fun textRightPx(block: View, text: TextView): Int {
        var left = 0
        var v: View = text
        while (v !== block) {
            left += v.left
            v = v.parent as View
        }
        return left + text.width - text.totalPaddingRight
    }

    /** Draws [markdown]'s single block as a PDF page does; returns (rule end, column text edge) in points. */
    private fun ruleEndAndColumnEdgePt(markdown: String): Pair<Int, Float> {
        val markwon = buildPrintMarkwon(context)
        val node = parseTopLevelBlocks(markwon, markdown).single()
        val block = PdfContentLayoutBuilder(context)
            .inflateMeasuredBlock(markwon, node, LayoutInflater.from(context), columnWidthPx, 1f)
        val text = descendants(block).filterIsInstance<TextView>().single()

        val page = Bitmap.createBitmap(PdfExporter.A4_WIDTH_PT, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        canvas.translate(PdfExporter.MARGIN_PT.toFloat(), PdfExporter.MARGIN_PT.toFloat())
        canvas.scale(scale, scale)
        canvas.clipRect(0, 0, columnWidthPx, block.height)
        block.draw(canvas)

        var rightmost = -1
        for (y in 0 until page.height) {
            for (x in page.width - 1 downTo rightmost + 1) {
                if (page.getPixel(x, y) != Color.WHITE) {
                    rightmost = x
                    break
                }
            }
        }
        val columnEdge = PdfExporter.MARGIN_PT + textRightPx(block, text) * scale
        return rightmost to columnEdge
    }

    private fun assertRuleReachesTheColumnEdge(markdown: String) {
        val (ruleEnd, columnEdge) = ruleEndAndColumnEdgePt(markdown)
        assertTrue(
            "'$markdown': the heading rule ends at $ruleEnd pt; the text column ends at %.1f pt".format(columnEdge),
            abs(ruleEnd - columnEdge) <= TOLERANCE_PT,
        )
    }

    @Test
    fun theH1RuleRunsToTheRightEdgeOfTheTextColumn() = assertRuleReachesTheColumnEdge("# A")

    @Test
    fun theH2RuleRunsToTheRightEdgeOfTheTextColumn() = assertRuleReachesTheColumnEdge("## A")

    @Test
    fun control_theReaderKeepsMarkwonsOwnHeadingSpan() {
        val spanned = buildPilcrowMarkwon(context).toMarkdown("# A")
        val span = spanned.getSpans(0, spanned.length, HeadingSpan::class.java).single()
        assertEquals(HeadingSpan::class.java, span.javaClass)
    }

    private companion object {
        const val PAGE_HEIGHT = 300
        const val TOLERANCE_PT = 1.5f
    }
}
