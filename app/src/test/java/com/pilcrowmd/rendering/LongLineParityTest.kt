// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CellEllipsis
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.testing.readerModelFor
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A62: the reader's tree (Markwon's parse, with `$…$` formulas and `:name:` emoji as nodes of their
 * own) and the parity tree search and the Contents walk (commonmark alone, where both are plain text)
 * must make the same character-limit cuts, or every block after a cut paragraph is numbered one off
 * between them. Each fixture is sized so the formulas or emoji are what tips it over the limit: without
 * them the text fits, so only how those nodes are counted can decide.
 */
@RunWith(RobolectricTestRunner::class)
class LongLineParityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parity = ParseMarkdownHeadingsUseCase()

    /** 7,000 chars of prose and 10 formulas of 110 source chars each: 8,110 chars of source. */
    private val proseWithFormulas: String =
        (0 until 10).joinToString("") { "word ".repeat(140) + "$" + "a+b".repeat(36) + "$ " }

    private fun readerBlocks(renderer: MarkwonRenderer, content: String): List<Node> =
        (renderer.readerModelFor(content) as ReaderModel.Markdown).blocks

    private fun parityBlocks(content: String): List<Node> = AdapterBlocks.of(parity.parseDocument(content)!!)

    @Test
    fun `a paragraph tipped over the limit by formulas is cut alike in the reader and in search`() {
        val content = "# Top\n\n$proseWithFormulas\n\n## After\n"

        val reader = readerBlocks(MarkwonRenderer(context), content)
        val search = parityBlocks(content)

        assertEquals("search cuts it", 4, search.size)
        assertEquals("the reader cuts it the same", search.size, reader.size)
    }

    @Test
    fun `a paragraph tipped over the limit by emoji is cut alike in the reader and in search`() {
        val asset = { context.assets.open(AssetEmojiTable.ASSET_PATH) }
        val emoji = EmojiShortcodes(AssetEmojiTable(asset, canDraw = { true }))
        val content = "# Top\n\n" + "word ".repeat(1_580) + ":smile: ".repeat(20) + "\n\n## After\n"

        val reader = readerBlocks(MarkwonRenderer(context, emoji = emoji), content)
        val search = parityBlocks(content)

        assertEquals("search cuts it", 4, search.size)
        assertEquals("the reader cuts it the same", search.size, reader.size)
    }

    @Test
    fun `a table cell tipped over the cell limit by formulas is capped alike in the reader and in search`() {
        val cell = (0 until 10).joinToString("") { "word ".repeat(190) + "$" + "a+b".repeat(36) + "$ " }
        val content = "| a |\n|---|\n| $cell |\n"

        val reader = readerBlocks(MarkwonRenderer(context), content)
        val search = parityBlocks(content)

        assertEquals("search caps it", 1, search.sumOf { ellipses(it) })
        assertEquals("the reader caps it the same", 1, reader.sumOf { ellipses(it) })
    }

    @Test
    fun `a formula the cell limit falls in is dropped whole, and the ellipsis follows the text`() {
        val content = "| a |\n|---|\n| " + "word ".repeat(1_990) + "$" + "a+b".repeat(36) + "$ |\n"

        val reader = readerBlocks(MarkwonRenderer(context), content)

        assertEquals(1, reader.sumOf { ellipses(it) })
        assertEquals("the formula is not drawn half-capped", 0, reader.sumOf { formulas(it) })
    }

    private fun formulas(node: Node): Int = (if (node is JLatexMathNode) 1 else 0) +
        generateSequence(node.firstChild) { it.next }.sumOf { formulas(it) }

    private fun ellipses(node: Node): Int =
        (if (node is CellEllipsis) 1 else 0) + generateSequence(node.firstChild) { it.next }.sumOf { ellipses(it) }
}
