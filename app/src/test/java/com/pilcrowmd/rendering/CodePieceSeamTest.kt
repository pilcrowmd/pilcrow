// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #17: the pieces of a long code block must look like one block. Stacked, they show no jump at a
 * join (a TextView's last line gets no line spacing, and with font padding its first line starts at `top`,
 * not `ascent`, so a naive cut shifts every line after it), no border or rounded corner at a join, and
 * the one Copy button copies the whole block.
 *
 * The oracle is the SAME text as one ordinary code block through the same entry, with no colours: every
 * baseline of the stacked pieces, margins included, must equal the matching baseline in it, to the pixel,
 * and the total height must match. As in [PlainTextSeamTest], NATIVE graphics: the legacy mode fakes text
 * metrics, so a layout measurement there proves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodePieceSeamTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    /**
     * 250 lines of about 50 chars: 12,500 chars, so cut into pieces of 100, 100 and 50 lines. [long] lines
     * are about 200 chars, which is 40 lines (8,000 chars) to a piece, and wrap at the test's width.
     */
    private fun lines(long: Boolean = false): String = (1..LINES).joinToString("") { n ->
        val filler = if (long) "wrapping words ".repeat(12) else "= ${"x".repeat(40)}"
        "line $n $filler\n"
    }

    private fun fence(code: String) = "```text\n$code```\n"

    /** Binds every code item of [document], in order, through the real adapter. */
    private fun bindCode(
        document: org.commonmark.node.Node,
        fontScale: Float,
        wrap: Boolean,
    ): List<FencedCodeBlockEntry.Holder> {
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            fontScale,
            wrapCodeLines = wrap,
        )
        adapter.setParsedMarkdown(markwon, document)
        val parent = FrameLayout(context)
        return blocks.indices.filter { blocks[it] is FencedCodeBlock }.map { position ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            holder as FencedCodeBlockEntry.Holder
        }
    }

    private fun split(code: String, fontScale: Float, wrap: Boolean) =
        bindCode(ReaderDocument.transform(markwon.parse(fence(code))), fontScale, wrap)

    private fun whole(code: String, fontScale: Float, wrap: Boolean) =
        bindCode(ReaderDocument.transform(markwon.parse(fence(code)), splitLongParagraphs = false), fontScale, wrap)

    private fun layOut(holder: FencedCodeBlockEntry.Holder) {
        holder.itemView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
    }

    private fun margins(holder: FencedCodeBlockEntry.Holder): ViewGroup.MarginLayoutParams =
        holder.itemView.layoutParams as ViewGroup.MarginLayoutParams

    /** Where [view] sits inside [root], by the tops of it and of the views between. */
    private fun topIn(root: View, view: View): Int {
        var top = 0
        var at: View = view
        while (at !== root) {
            top += at.top
            at = at.parent as View
        }
        return top
    }

    /** Absolute baseline of every laid-out code line when [holders] are stacked as the list stacks them. */
    private fun baselines(holders: List<FencedCodeBlockEntry.Holder>): List<Int> {
        var top = 0
        val out = mutableListOf<Int>()
        holders.forEach { holder ->
            layOut(holder)
            val text = holder.codeView
            top += margins(holder).topMargin
            val inItem = topIn(holder.itemView, text)
            for (i in 0 until text.layout.lineCount) {
                out += top + inItem + text.totalPaddingTop + text.layout.getLineBaseline(i)
            }
            top += holder.itemView.measuredHeight + margins(holder).bottomMargin
        }
        return out
    }

    private fun totalHeight(holders: List<FencedCodeBlockEntry.Holder>) =
        holders.sumOf { margins(it).topMargin + it.itemView.measuredHeight + margins(it).bottomMargin }

    private fun assertSeamsMatchOneBlock(code: String, fontScale: Float, wrap: Boolean) {
        val label = "x$fontScale wrap=$wrap"
        val pieces = split(code, fontScale, wrap)
        assertTrue("$label: the block was cut into at least 3 pieces: ${pieces.size}", pieces.size >= 3)
        val oracle = whole(code, fontScale, wrap)
        assertEquals("$label: the oracle is one block", 1, oracle.size)

        val expected = baselines(oracle)
        val actual = baselines(pieces)
        assertTrue("$label: real metrics (NATIVE graphics)", expected[2] - expected[1] > 10)
        assertTrue("$label: at least the code's lines are laid out: ${expected.size}", expected.size >= LINES)
        if (wrap) {
            assertTrue(
                "$label: the lines wrap, so wrapping is covered: ${expected.size}",
                expected.size > 2 * LINES,
            )
        }
        assertEquals("$label: same number of laid-out lines", expected.size, actual.size)
        val deviations = expected.indices.filter { expected[it] != actual[it] }
            .map { "line#$it expected=${expected[it]} actual=${actual[it]} (${actual[it] - expected[it]}px)" }
        assertTrue("$label: baseline deviations ${deviations.take(4)} (${deviations.size} total)", deviations.isEmpty())
        assertEquals("$label: total stacked height", totalHeight(oracle), totalHeight(pieces))
    }

    @Test
    fun `stacked pieces keep the line pitch and the height of one block, at font scale 1`() =
        assertSeamsMatchOneBlock(lines(), 1.0f, wrap = false)

    @Test
    fun `stacked pieces keep the line pitch and the height of one block, at font scale 1_5`() =
        assertSeamsMatchOneBlock(lines(), 1.5f, wrap = false)

    @Test
    fun `stacked pieces keep the line pitch and the height of one block, with wrapped lines`() =
        assertSeamsMatchOneBlock(lines(long = true), 1.0f, wrap = true)

    @Test
    fun `stacked pieces keep the line pitch and the height of one block, with wrapped lines at font scale 1_5`() =
        assertSeamsMatchOneBlock(lines(long = true), 1.5f, wrap = true)

    @Test
    fun `only the first piece has a Copy button, and it copies the whole block`() {
        val code = lines()
        val pieces = split(code, 1.0f, wrap = false)

        assertEquals(View.VISIBLE, pieces[0].copyButton.visibility)
        pieces.drop(1).forEach { assertEquals(View.GONE, it.copyButton.visibility) }

        pieces[0].copyButton.performClick()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue("it copies more than the first piece shows", copied.length > 2 * 4_000)
        assertEquals("the whole block, every char", code, copied)
    }

    @Test
    fun `an ordinary block still has its Copy button, margins and border`() {
        val only = whole(lines(), 1.0f, wrap = false).single()
        layOut(only)

        assertEquals(View.VISIBLE, only.copyButton.visibility)
        assertTrue(only.codeScroll.background is GradientDrawable)
        assertTrue(margins(only).topMargin > 0 && margins(only).bottomMargin > 0)
    }

    @Test
    fun `a middle piece is drawn without a border or a rounded corner at its joins`() {
        val pieces = split(lines(), 1.0f, wrap = false)
        val middle = pieces[1]
        layOut(middle)

        assertTrue("the surface is the inset shape", middle.codeScroll.background is InsetDrawable)
        assertEquals("no gap above", 0, margins(middle).topMargin)
        assertEquals("no gap below", 0, margins(middle).bottomMargin)

        val bg = DarkColorScheme.codeBlockBg.toArgb()
        val border = DarkColorScheme.codeBlockBorder.toArgb()
        assertNotEquals("the fixture: border and fill differ", bg, border)
        val middleImage = draw(middle)
        val x = middleImage.width / 2
        assertEquals("top edge of a middle piece is fill", bg, middleImage.getPixel(x, 0))
        assertEquals("bottom edge of a middle piece is fill", bg, middleImage.getPixel(x, middleImage.height - 1))
        assertEquals("a corner of a middle piece is fill, not rounded away", bg, middleImage.getPixel(1, 1))
        assertEquals("the sides keep their border", border, middleImage.getPixel(0, middleImage.height / 2))

        // Control: the block's own ends do draw the border and the round corner.
        val firstImage = draw(pieces.first().also { layOut(it) })
        assertEquals("the first piece's top edge is the border", border, firstImage.getPixel(firstImage.width / 2, 0))
        val lastImage = draw(pieces.last().also { layOut(it) })
        assertEquals(
            "the last piece's bottom edge is the border",
            border,
            lastImage.getPixel(
                lastImage.width / 2,
                lastImage.height - 1,
            ),
        )
    }

    private fun draw(holder: FencedCodeBlockEntry.Holder): Bitmap {
        val view = holder.codeScroll
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private companion object {
        const val WIDTH_PX = 600
        const val LINES = 250
    }
}
