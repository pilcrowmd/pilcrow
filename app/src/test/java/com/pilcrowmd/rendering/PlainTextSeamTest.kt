// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import org.commonmark.node.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * M-365: a blank-line-free run is cut into several TextViews at [PlainTextChunks.MAX_CHUNK_LINES].
 * Stacked views would show a visible jump at each cut (a TextView's last line gets no line spacing
 * and its first line starts at `top`, not `ascent`), so [PlainTextBlockEntry] pads the seam.
 *
 * The oracle is ONE unsplit TextView configured the same way: every baseline of the stacked chunks
 * must equal the matching baseline in it, to the pixel, and the total height must match.
 *
 * NATIVE graphics: the legacy mode fakes text metrics, so a layout measurement there proves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlainTextSeamTest {

    private lateinit var context: android.content.Context
    private lateinit var markwon: io.noties.markwon.Markwon

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    /** Binds every top-level node of [document] through the real adapter. */
    private fun bind(document: Document, fontSet: FontSet, fontScale: Float): List<TextView> {
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, fontScale, fontSet)
        adapter.setParsedMarkdown(markwon, document)
        val parent = FrameLayout(context)
        return (0 until adapter.itemCount).map { i ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(i))
            adapter.onBindViewHolder(holder, i)
            (holder as PlainTextBlockEntry.Holder).textView
        }
    }

    private fun measured(tv: TextView): TextView {
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tv.layout(0, 0, tv.measuredWidth, tv.measuredHeight)
        return tv
    }

    /** Absolute baseline of every laid-out line when [views] are stacked top to bottom. */
    private fun baselines(views: List<TextView>): List<Int> {
        var top = 0
        val out = mutableListOf<Int>()
        views.forEach { tv ->
            measured(tv)
            for (i in 0 until tv.layout.lineCount) {
                out += top + tv.totalPaddingTop + tv.layout.getLineBaseline(i)
            }
            top += tv.measuredHeight
        }
        return out
    }

    private fun totalHeight(views: List<TextView>) = views.sumOf { it.measuredHeight }

    /** A wrapping line, long enough to take several visual lines at [WIDTH_PX]. */
    private fun longLine(n: Int) = "line $n " + "wrapping words ".repeat(40)

    private fun content(): String = (1..1000).joinToString("\n") { n ->
        // Wrapping lines on BOTH sides of both forced seams (400|401 and 800|801).
        if (n in WRAPPING_LINES) longLine(n) else "line $n"
    }

    private fun assertSeamsMatchOneUnsplitView(fontSet: FontSet, fontScale: Float) {
        val label = "${fontSet.id} x$fontScale"
        val text = content()
        val split = bind(PlainTextBlocks.build(text), fontSet, fontScale)
        assertEquals("$label: 1000 lines split into 400/400/200", 3, split.size)

        val whole = bind(Document().apply { appendChild(PlainTextChunk(text)) }, fontSet, fontScale)
        assertEquals(1, whole.size)

        val expected = baselines(whole)
        val actual = baselines(split)
        assertTrue("$label: the oracle wraps, so wrapping is covered", expected.size > 1000)
        assertTrue("$label: real metrics (NATIVE graphics)", expected[1] - expected[0] > 10)
        assertEquals("$label: same number of laid-out lines", expected.size, actual.size)
        val deviations = expected.indices.filter { expected[it] != actual[it] }
            .map { "line#$it expected=${expected[it]} actual=${actual[it]} (${actual[it] - expected[it]}px)" }
        assertTrue("$label: baseline deviations ${deviations.take(4)} (${deviations.size} total)", deviations.isEmpty())
        assertEquals("$label: total stacked height", totalHeight(whole), totalHeight(split))
    }

    @Test
    fun forcedSeamsKeepTheLinePitchAndTheTotalHeightOfOneUnsplitView() {
        // Every bundled reading font: their ascent/top and descent/bottom differ, so a seam formula
        // that is only right for one font's metrics is caught. Two scales for the rounding.
        FontSets.ALL.forEach { fontSet ->
            listOf(1.0f, 1.5f).forEach { scale -> assertSeamsMatchOneUnsplitView(fontSet, scale) }
        }
    }

    @Test
    fun blankLineChunksAreNotPaddedAndKeepDefaultFontPadding() {
        val src = ((1..220).map { "line $it" } + "" + listOf("tail")).joinToString("\n")
        val views = bind(PlainTextBlocks.build(src), FontSets.DEFAULT, 1.0f)
        assertEquals(2, views.size)
        views.forEach { tv ->
            assertTrue("font padding kept", tv.includeFontPadding)
            assertEquals(0, tv.paddingTop)
            assertEquals(0, tv.paddingBottom)
        }
    }

    @Test
    fun aRecycledHolderIsResetWhenARegularChunkIsBoundAfterAForcedOne() {
        // Holders are recycled: a chunk that continues both ways sets no-font-padding and a bottom
        // padding, and the next ordinary chunk bound on the SAME holder must get the defaults back.
        val entry = PlainTextBlockEntry(context)
        val tv = entry.createHolder(android.view.LayoutInflater.from(context), FrameLayout(context)).textView
        val holder = PlainTextBlockEntry.Holder(tv)
        val start = tv.paddingStart
        val end = tv.paddingEnd

        entry.bindHolder(markwon, holder, PlainTextChunk("a\nb", continuesPrevious = true, continuesNext = true))
        assertTrue("sanity: the forced chunk really changed the view", !tv.includeFontPadding && tv.paddingBottom > 0)

        entry.bindHolder(markwon, holder, PlainTextChunk("c\nd"))
        assertTrue("font padding restored", tv.includeFontPadding)
        assertEquals(0, tv.paddingTop)
        assertEquals(0, tv.paddingBottom)
        assertEquals(start, tv.paddingStart)
        assertEquals(end, tv.paddingEnd)
    }

    @Test
    fun seamBottomPaddingNeverGoesNegative() {
        // A font whose bottom lies far below its descent would make the formula negative when the
        // line-spacing extra is too small to absorb it.
        val fm = android.graphics.Paint.FontMetricsInt().apply {
            top = -12
            ascent = -10
            descent = 2
            bottom = 40
        }
        assertEquals(0, seamBottomPadding(fm, 1.0f, continuesPrevious = false, continuesNext = true))
    }

    private companion object {
        const val WIDTH_PX = 1080
        val WRAPPING_LINES = setOf(399, 400, 401, 402, 800, 801)
    }
}
