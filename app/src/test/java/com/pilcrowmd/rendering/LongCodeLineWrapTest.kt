// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Layout
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A65 to S8-A67: a long code line cut into pieces is drawn, coloured and copied as the one line it is,
 * with code wrap on and off. Through the real adapter, Markwon instance and TextMate highlighter:
 *
 *  - a string token cut in two keeps the string colour on both sides of the cut;
 *  - no piece's TextView holds more than a piece of the line;
 *  - the Copy button copies the original block, byte for byte, with no newline added at a cut.
 */
@RunWith(RobolectricTestRunner::class)
class LongCodeLineWrapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon: Markwon = buildPilcrowMarkwon(context, DarkColorScheme)
    private val stringColour = DarkColorScheme.codeSyntax.string.toArgb()

    // One rust line of 25,016 chars: a string literal that runs over every cut but the first, which
    // falls after the last space in its window (`let s = `).
    private val code = "let s = \"" + "a".repeat(25_000) + "\";\n"

    private class Built(val adapter: MarkwonAdapter, val blocks: List<Node>)

    private fun build(wrap: Boolean): Built {
        val markdown = "# Head\n\n```rust\n$code```\n\nAfter the block.\n"
        val document = ReaderDocument.transform(markwon.parse(markdown))
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            wrapCodeLines = wrap,
            codeHighlighting = CodeHighlighting(TextMateCodeHighlighter(context.assets::open), scope = null),
        )
        adapter.setParsedMarkdown(markwon, document)
        return Built(adapter, blocks)
    }

    private fun MarkwonAdapter.bind(position: Int): FencedCodeBlockEntry.Holder {
        val holder = onCreateViewHolder(FrameLayout(context), getItemViewType(position))
        onBindViewHolder(holder, position)
        return holder as FencedCodeBlockEntry.Holder
    }

    private fun Spanned.hasColourAt(index: Int, colour: Int): Boolean =
        getSpans(index, index + 1, ForegroundColorSpan::class.java).any { it.foregroundColor == colour }

    private fun Built.pieces(): List<Int> = blocks.indices.filter { blocks[it] is CodeBlockPiece }

    @Test
    fun `a string cut in two keeps its colour on both sides of the cut, wrap on and off`() {
        for (wrap in listOf(true, false)) {
            val built = build(wrap)
            val pieces = built.pieces()
            assertTrue("wrap $wrap: several pieces, got ${pieces.size}", pieces.size >= 4)

            // Piece 1 ends inside the string and piece 2 goes on with it.
            val before = built.adapter.bind(pieces[1]).codeView.text as Spanned
            val after = built.adapter.bind(pieces[2]).codeView.text as Spanned

            assertTrue("wrap $wrap, before the cut: ${before.takeLast(3)}", before.endsWith("aaa"))
            assertTrue("wrap $wrap, after the cut: ${after.take(3)}", after.startsWith("aaa"))
            assertTrue("wrap $wrap: string-coloured before", before.hasColourAt(before.lastIndex, stringColour))
            assertTrue("wrap $wrap: string-coloured after", after.hasColourAt(0, stringColour))
        }
    }

    @Test
    fun `no piece holds more than 750 chars of the line, wrap on and off`() {
        for (wrap in listOf(true, false)) {
            val built = build(wrap)

            built.pieces().forEach { position ->
                val shown = built.adapter.bind(position).codeView.text
                assertTrue("wrap $wrap: item $position shows ${shown.length} chars", shown.length <= 750 + 4)
            }
        }
    }

    @Test
    fun `only the last piece shows a scrollbar, and a recycled holder gets it back`() {
        // S8-A67: with short rows each piece's own scroll view showed a thumb on every pan.
        val built = build(wrap = false)
        val pieces = built.pieces()
        val holder = built.adapter.bind(pieces.first())
        assertEquals("first piece", false, holder.codeScroll.isHorizontalScrollBarEnabled)
        built.adapter.onBindViewHolder(holder, pieces.last())
        assertEquals("last piece", true, holder.codeScroll.isHorizontalScrollBarEnabled)

        val small = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, wrapCodeLines = false)
        small.setParsedMarkdown(markwon, markwon.parse("```\nshort\n```\n"))
        built.adapter.onBindViewHolder(holder, pieces.first())
        small.onBindViewHolder(holder, 0)
        assertEquals("an ordinary block", true, holder.codeScroll.isHorizontalScrollBarEnabled)
    }

    @Test
    fun `Copy copies the original block, byte for byte, wrap on and off`() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        for (wrap in listOf(false, true)) {
            clipboard.setPrimaryClip(ClipData.newPlainText("before", "nothing yet"))
            val built = build(wrap)
            val first = built.adapter.bind(built.pieces().first())

            first.copyButton.performClick()

            assertEquals("wrap $wrap", code, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun `wrap off breaks code lines the simple way, wrap on keeps the high-quality breaker`() {
        // S8-A66 e5: with wrap off an 8,000-char CJK row still ANRed in minikin's optimal breaker, which
        // weighs every break chance in a row it never needs to break. Greedy is linear; with no wrap
        // it shows the same text.
        val off = build(wrap = false)
        val on = build(wrap = true)
        for (position in off.pieces()) {
            assertEquals(Layout.BREAK_STRATEGY_SIMPLE, off.adapter.bind(position).codeView.breakStrategy)
        }
        // The same holder bound for wrap on after wrap off must not keep the simple breaker.
        val holder = off.adapter.bind(off.pieces().first())
        on.adapter.onBindViewHolder(holder, on.pieces().first())
        assertEquals(Layout.BREAK_STRATEGY_HIGH_QUALITY, holder.codeView.breakStrategy)
    }
}
