// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.domain.usecase.findSearchMatches
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-A65 to S8-A67: a code line longer than [CodeBlockSplitter.LINE_PIECE_CHARS] is cut inside the
 * line, so no reader TextView lays out or draws more than a piece of it. A 1 MB line froze the reader
 * in minikin's line breaker with code wrap on (S8-A62 e3) and with it off too (S8-A65 e5), so the cut
 * does not depend on the setting: one tree for both. What these tests guard: the pieces are bounded and cut where
 * [LongLineCut.pieceEnd] says, no character is lost or moved (Safeguard 2), and search, the parity parser
 * and the caret lookup take the same cuts as the reader.
 */
class CodeBlockLongLineTest {

    private val parser = ParseMarkdownHeadingsUseCase()

    private fun documentOf(literal: String): Document = Document().apply {
        appendChild(
            FencedCodeBlock().also {
                it.info = "rust"
                it.fenceChar = '`'
                it.fenceLength = 3
                it.literal = literal
            },
        )
    }

    private fun piecesOf(literal: String): List<CodeBlockPiece> {
        val document = documentOf(literal)
        CodeBlockSplitter.split(document)
        return generateSequence(document.firstChild) { it.next }.map { it as CodeBlockPiece }.toList()
    }

    private fun fenced(literal: String) = "# Top\n\n```\n$literal```\n\nafter\n"

    @Test
    fun `a 25,000 char line is cut into pieces of at most 750, at character boundaries`() {
        // 'a' then emoji: every high surrogate sits at an odd index, so a cut at 750 would split a pair.
        val line = "a" + "😀".repeat(12_500)
        val literal = "short\n$line\nend\n"
        val pieces = piecesOf(literal)

        assertEquals(literal, pieces.joinToString("") { it.literal })
        assertTrue("the line was cut: ${pieces.size} pieces", pieces.size >= 30)
        pieces.forEach { piece ->
            assertTrue("piece ${piece.index} holds ${piece.literal.length}", piece.literal.length <= 750)
            assertFalse("piece ${piece.index} starts inside a surrogate pair", piece.literal.first().isLowSurrogate())
        }
        val cuts = (1 until pieces.size).map { pieces.first().group.start(it) }
        val inLine = cuts.filter { literal[it - 1] != '\n' }
        assertTrue("cuts inside the line: $cuts", inLine.size >= 30)
        inLine.forEach { cut ->
            assertEquals("the cut is where LongLineCut puts it", cut, expectedCut(literal, cuts, cut))
        }
    }

    /** Where [LongLineCut.pieceEnd] ends the piece that ends at [cut]. */
    private fun expectedCut(literal: String, cuts: List<Int>, cut: Int): Int {
        val from = (listOf(0) + cuts).last { it < cut }
        val lineEnd = literal.indexOf('\n', from)
        return LongLineCut.pieceEnd(literal, from, lineEnd, CodeBlockSplitter.LINE_PIECE_CHARS)
    }

    @Test
    fun `a line of 750 chars is not cut, one of 751 is`() {
        // S8-A67: a normal code line must show no break with code wrap on, so only a longer line is cut.
        // 14 lines of 750 chars make a block over LONG_BLOCK_CHARS, so the block itself is cut.
        val fits = "f".repeat(750) + "\n"
        val literal = fits.repeat(14)
        val starts = CodeBlockSplitter.pieceStarts(literal).toList()
        assertTrue("every piece starts on a line: $starts", starts.all { it == 0 || literal[it - 1] == '\n' })

        val over = literal + "g".repeat(751) + "\n"
        val cuts = CodeBlockSplitter.pieceStarts(over).filter { it > 0 && over[it - 1] != '\n' }
        assertEquals("the 751-char line is cut once, at 750", listOf(fits.length * 14 + 750), cuts)
    }

    @Test
    fun `the cut prefers the last space in the window`() {
        val word = "w".repeat(99)
        val line = (1..300).joinToString(" ") { word } // 29,999 chars, a space every 100
        val pieces = piecesOf("$line\n")

        assertTrue(pieces.size >= 4)
        pieces.dropLast(1).forEach { piece ->
            assertTrue("piece ${piece.index} ends after a space", piece.literal.endsWith(" "))
            assertTrue(piece.literal.length <= 750)
        }
    }

    @Test
    fun `one unbreakable cluster that runs to the end leaves no empty piece`() {
        // A letter, 748 combining marks and a supplementary combining mark: one cluster of 751
        // chars, which pieceEnd can only end after the surrogate pair, at the very end.
        val cluster = "a" + "\u0301".repeat(748) + "\uD834\uDD67"
        val literal = "x\n$cluster"

        val starts = CodeBlockSplitter.pieceStarts(literal)

        assertTrue("no piece starts at the end: ${starts.toList()}", starts.all { it < literal.length })
    }

    @Test
    fun `short lines before a long one end their piece at the long line's start`() {
        val literal = "one\ntwo\n" + "z".repeat(20_000) + "\nthree\n"
        val starts = CodeBlockSplitter.pieceStarts(literal)

        assertEquals("the long line starts a piece", true, "one\ntwo\n".length in starts.toList())
        assertTrue(starts.toList().zipWithNext().all { (a, b) -> b - a <= 8_000 })
        assertTrue(literal.length - starts.last() <= 8_000)
    }

    @Test
    fun `the pieces of a prefix that ends at a line end start where the whole block's do`() {
        val literal = "head\n" + "q".repeat(30_000) + "\nmid\n" + "r ".repeat(9_000) + "\ntail\n"
        val whole = CodeBlockSplitter.pieceStarts(literal)

        var lineEnd = literal.indexOf('\n')
        while (lineEnd >= 0) {
            val prefix = literal.substring(0, lineEnd)
            val expected = whole.filter { it < prefix.length }.ifEmpty { listOf(0) }
            val actual = CodeBlockSplitter.pieceStarts(prefix).toList()
            assertEquals("prefix to $lineEnd", expected, actual)
            lineEnd = literal.indexOf('\n', lineEnd + 1)
        }
    }

    // --- The other trees take the same cuts -----------------------------------------------------

    private val longLine = "x".repeat(12_000) + "NEEDLE" + "x".repeat(12_000)

    @Test
    fun `the parity parser cuts the line as the reader does`() {
        val markdown = fenced("$longLine\n")

        val parity = AdapterBlocks.of(parser.parseDocument(markdown)!!)

        assertTrue("the line is in several pieces: ${parity.size}", parity.size >= 6)
        assertEquals(parity.size, AdapterBlocks.of(ReaderDocument.transform(parse(markdown))).size)
    }

    private fun parse(markdown: String): Node = parser.parseTree(markdown)

    @Test
    fun `search finds a needle that runs over a cut, on the piece it starts in`() {
        val markdown = fenced("$longLine\n")
        val blocks = AdapterBlocks.of(parser.parseDocument(markdown)!!)
        val pieces = blocks.filterIsInstance<CodeBlockPiece>()
        val group = pieces.first().group
        val start = longLine.indexOf("NEEDLE")
        val cut = (1 until group.pieceCount).map { group.start(it) }.minBy { kotlin.math.abs(it - start) }
        // Move the needle so that it runs over that cut: three chars before it, three after.
        val moved = "x".repeat(cut - 3) + "NEEDLE" + "x".repeat(longLine.length - cut - 3)
        val content = fenced("$moved\n")

        val matches = SearchMarkdownUseCase(parser).findSearchMatches(content, "needle")

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals(content.indexOf("NEEDLE"), match.startIndex)
        val movedBlocks = AdapterBlocks.of(parser.parseDocument(content)!!)
        val holding = movedBlocks[match.adapterPosition] as CodeBlockPiece
        assertTrue("it lands on the piece the needle starts in", holding.literal.endsWith("NEE"))
        assertEquals("after that piece's own matches", 0, match.occurrenceInBlock)
    }

    @Test
    fun `search finds a needle across the first cut of a line, at 750, on the piece it starts in`() {
        // S8-A67: with 8,000-char pieces the needle sat whole inside the first piece.
        val line = "x".repeat(747) + "NEEDLE" + "x".repeat(23_000)
        val content = fenced("$line\n")

        val match = SearchMarkdownUseCase(parser).findSearchMatches(content, "needle").single()

        assertEquals(content.indexOf("NEEDLE"), match.startIndex)
        val holding = AdapterBlocks.of(parser.parseDocument(content)!!)[match.adapterPosition] as CodeBlockPiece
        assertEquals("the line's first piece", 0, holding.index)
        assertTrue("it ends inside the needle: ${holding.literal.takeLast(3)}", holding.literal.endsWith("NEE"))
    }

    @Test
    fun `the caret lookup counts the same pieces as the reader`() {
        val content = fenced("$longLine\n") + "\n# Below\n"
        val caret = content.indexOf("# Below")
        val blocks = AdapterBlocks.of(parser.parseDocument(content)!!)

        val block = parser.readerBlockForOffset(content, caret)

        assertEquals("the heading after the cut line", blocks.lastIndex, block)
    }
}
