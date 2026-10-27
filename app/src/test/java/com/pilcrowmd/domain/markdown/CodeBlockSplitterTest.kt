// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #17: [CodeBlockSplitter] cuts a long top-level code block into [CodeBlockPiece]s. What these
 * tests guard: no character is lost or moved by a cut (Safeguard 2), a cut falls only at a line end,
 * the size thresholds sit exactly where the constants say, the blocks that must stay whole stay whole
 * (front matter, a Mermaid fence within the gate, anything not top-level), and a prefix of a block is
 * cut where the whole block is (the Edit to Read caret mapping relies on it).
 *
 * The documents are built through the parity parser, the same one search and the headings drawer use, or
 * by hand where only the splitter is under test.
 */
class CodeBlockSplitterTest {

    private val parser = ParseMarkdownHeadingsUseCase()

    /** A block of [lines] lines, each [width] chars long once its line end is counted. */
    private fun lines(count: Int, width: Int = 2): String =
        (1..count).joinToString("") { n -> n.toString().padStart(width - 1, 'a').takeLast(width - 1) + "\n" }

    private fun fenced(literal: String, info: String = "rust", fence: String = "```") = "$fence$info\n$literal$fence\n"

    private fun documentOf(
        literal: String,
        info: String? = "rust",
        fenceChar: Char = '`',
        fenceLength: Int = 3,
    ): Document = Document().apply {
        appendChild(
            FencedCodeBlock().also {
                it.info = info
                it.fenceChar = fenceChar
                it.fenceLength = fenceLength
                it.literal = literal
            },
        )
    }

    private fun blocksOf(document: Node): List<Node> = generateSequence(document.firstChild) { it.next }.toList()

    private fun piecesOf(literal: String): List<CodeBlockPiece> {
        val document = documentOf(literal)
        CodeBlockSplitter.split(document)
        return blocksOf(document).map { it as CodeBlockPiece }
    }

    private fun lineCount(text: String) = text.count { it == '\n' }

    @Test
    fun `every character is in exactly one piece, and every piece but the first starts after a line end`() {
        val literal = (1..777).joinToString("") { "line $it ${"x".repeat(it % 37)}\n" }
        val pieces = piecesOf(literal)

        assertTrue("the block was cut", pieces.size > 1)
        assertEquals(literal, pieces.joinToString("") { it.literal })
        pieces.drop(1).forEach { piece ->
            val before = piece.group.start(piece.index) - 1
            assertEquals("piece ${piece.index} starts right after a line end", '\n', literal[before])
        }
        assertEquals("pieces are numbered in order", pieces.indices.toList(), pieces.map { it.index })
        assertEquals("the pieces share one group", 1, pieces.map { System.identityHashCode(it.group) }.toSet().size)
        assertEquals(literal, pieces.first().group.literal)
        assertTrue(pieces.first().isFirst && !pieces.first().isLast)
        assertTrue(pieces.last().isLast && !pieces.last().isFirst)
    }

    @Test
    fun `a piece holds at most 100 lines, exactly 100 unless the character rule ended it first`() {
        val literal = lines(2_250)
        val pieces = piecesOf(literal)

        assertEquals("2,250 short lines in pieces of 100, the last holding 50", 23, pieces.size)
        pieces.dropLast(1).forEach { assertEquals(100, lineCount(it.literal)) }
        assertEquals(50, lineCount(pieces.last().literal))
    }

    @Test
    fun `a block of exactly 2,100 lines leaves no empty piece at the end`() {
        val pieces = piecesOf(lines(2_100))

        assertEquals(21, pieces.size)
        assertTrue(pieces.all { it.literal.isNotEmpty() })
        assertEquals(100, lineCount(pieces.last().literal))
    }

    @Test
    fun `lines of 200 chars end a piece at 8,000 chars, which is 40 lines`() {
        val line = "x".repeat(199) + "\n"
        val literal = line.repeat(250)
        val pieces = piecesOf(literal)

        assertEquals("250 lines in pieces of 40: six full ones and one of 10", 7, pieces.size)
        pieces.dropLast(1).forEach { assertEquals(8_000, it.literal.length) }
        assertEquals(10 * 200, pieces.last().literal.length)
    }

    @Test
    fun `a single line longer than a piece stays whole, and ends its piece`() {
        val huge = "y".repeat(20_000)
        val literal = "short one\n$huge\nshort two\nshort three\n"
        val pieces = piecesOf(literal)

        assertEquals(literal, pieces.joinToString("") { it.literal })
        val holding = pieces.single { it.literal.contains(huge) }
        assertEquals(
            "the long line is in one piece, whole",
            huge,
            holding.literal.lines().single {
                it.startsWith("yyy")
            },
        )
        assertTrue("the piece ends after the long line", holding.literal.endsWith("$huge\n"))
        assertEquals("the lines after it start the next piece", "short two\nshort three\n", pieces.last().literal)
    }

    @Test
    fun `a block of exactly 10,000 chars is not cut, one char more is`() {
        val line = "z".repeat(99) + "\n"
        val atLimit = line.repeat(100)
        assertEquals(CodeBlockSplitter.LONG_BLOCK_CHARS, atLimit.length)
        assertEquals("exactly at the limit: one block", 1, blocksOf(cut(atLimit)).size)
        assertFalse(blocksOf(cut(atLimit)).single() is CodeBlockPiece)

        // One char more, still on whole lines and far under the line limit: the char count alone cuts it.
        val over = atLimit.dropLast(1) + "z\n"
        assertEquals(CodeBlockSplitter.LONG_BLOCK_CHARS + 1, over.length)
        val pieces = blocksOf(cut(over))
        assertTrue("one char over: cut", pieces.size > 1 && pieces.all { it is CodeBlockPiece })
        assertEquals(over, pieces.joinToString("") { (it as CodeBlockPiece).literal })
    }

    private fun cut(literal: String, info: String? = "rust"): Document =
        documentOf(literal, info).also { CodeBlockSplitter.split(it) }

    @Test
    fun `2,000 short lines are not cut, 2,001 are`() {
        val two000 = lines(2_000)
        assertTrue("under the char limit, so only the line limit can cut it", two000.length < 10_000)
        assertEquals(1, blocksOf(cut(two000)).size)
        assertFalse(blocksOf(cut(two000)).single() is CodeBlockPiece)

        val two001 = lines(2_001)
        assertTrue(two001.length < 10_000)
        val pieces = blocksOf(cut(two001))
        assertTrue(pieces.size > 1 && pieces.all { it is CodeBlockPiece })
        assertEquals(two001, pieces.joinToString("") { (it as CodeBlockPiece).literal })
    }

    @Test
    fun `a piece keeps the fence info, character and length of its block`() {
        val literal = lines(2_100)
        listOf(
            Triple("rust title=\"x\"", '`', 3),
            Triple("python", '~', 4),
            Triple(null, '`', 5),
        ).forEach { (info, char, length) ->
            val document = documentOf(literal, info, char, length)
            CodeBlockSplitter.split(document)
            val pieces = blocksOf(document).map { it as CodeBlockPiece }
            assertTrue(pieces.size > 1)
            pieces.forEach {
                assertEquals(info, it.info)
                assertEquals(char, it.fenceChar)
                assertEquals(length, it.fenceLength)
                assertEquals(info, it.group.info)
            }
        }
    }

    @Test
    fun `a long block among others is replaced in place, its neighbours untouched`() {
        val md = "# Head\n\nBefore.\n\n" + fenced(lines(2_100)) + "\nAfter.\n"
        val blocks = blocksOf(parser.parseDocument(md)!!)

        assertEquals(21, blocks.count { it is CodeBlockPiece })
        val first = blocks.indexOfFirst { it is CodeBlockPiece }
        assertEquals("heading, paragraph, then the pieces", 2, first)
        assertTrue(blocks.subList(first, first + 21).all { it is CodeBlockPiece })
        assertTrue(blocks.last() is org.commonmark.node.Paragraph)
        assertEquals(first + 21 + 1, blocks.size)
    }

    @Test
    fun `front matter is never cut, however long`() {
        val md = "---\n" + lines(2_100, width = 8) + "---\n\n# Body\n"
        val blocks = blocksOf(parser.parseDocument(md)!!)

        val front = blocks.first() as FencedCodeBlock
        assertTrue("the fixture is front matter", front.isFrontmatter())
        assertTrue("and long enough to be cut: ${front.literal.length}", front.literal.length > 10_000)
        assertFalse(front is CodeBlockPiece)
        assertEquals(2, blocks.size)
    }

    /**
     * S8-A31 lead 4: a Mermaid fence is drawn as a picture, so it stays whole, but only up to Mermaid's
     * own maxTextSize. Past [CodeBlockSplitter.MERMAID_MAX_CHARS] it cannot be drawn, so it is shown as
     * source and is cut like any long block (one TextView holding 100 KB took over a second to lay out).
     * The fixture is 5,000 lines, over LONG_BLOCK_LINES and LONG_BLOCK_CHARS, so the gate is the only
     * thing that can keep it whole at the limit, and one extra char is the only thing that cuts it.
     */
    @Test
    fun `a mermaid block is cut only past MERMAID_MAX_CHARS, to the char`() {
        val atGate = lines(5_000, width = 10)
        assertEquals("fixture: exactly the gate", CodeBlockSplitter.MERMAID_MAX_CHARS, atGate.length)
        assertTrue("fixture: over the plain limits", lineCount(atGate) > CodeBlockSplitter.LONG_BLOCK_LINES)

        listOf("mermaid", "Mermaid {.wide}").forEach { info ->
            val whole = documentOf(atGate, info).also { CodeBlockSplitter.split(it) }
            assertEquals("$info at the gate", 1, blocksOf(whole).size)
            assertFalse("$info at the gate", blocksOf(whole).single() is CodeBlockPiece)

            val over = atGate + "x"
            val cut = documentOf(over, info).also { CodeBlockSplitter.split(it) }
            val pieces = blocksOf(cut).map { it as CodeBlockPiece }
            assertTrue("$info one char over the gate is cut: ${pieces.size}", pieces.size > 1)
            assertEquals(over, pieces.joinToString("") { it.literal })
            assertTrue(pieces.all { it.literal.length <= CodeBlockSplitter.LONG_BLOCK_CHARS })
            assertTrue("a piece keeps the fence info", pieces.all { it.info == info })
        }
    }

    @Test
    fun `a small mermaid block is untouched, and so is one nested in a list`() {
        val small = "graph TD\n" + (1..20).joinToString("") { "  A$it --> B$it\n" }
        val blocks = blocksOf(parser.parseDocument(fenced(small, "mermaid"))!!)
        assertEquals(1, blocks.size)
        assertFalse(blocks.single() is CodeBlockPiece)

        // Over the gate but not top-level: the reader never routes it to the diagram, so it is not cut here.
        val big = lines(5_100, width = 10).lines().joinToString("\n") { if (it.isEmpty()) it else "  $it" }
        val nested = parser.parseDocument("- item\n\n  ```mermaid\n$big  ```\n")!!
        assertFalse(containsPiece(nested))
    }

    @Test
    fun `a long block inside a list item or a block quote is not cut, it is not top-level`() {
        val code = lines(2_100)
        val inList = "- item\n\n  ```rust\n" + code.lines().joinToString("\n") { if (it.isEmpty()) it else "  $it" } +
            "  ```\n"
        val inQuote = "> ```rust\n" + code.lines().joinToString("\n") { if (it.isEmpty()) it else "> $it" } + "> ```\n"

        listOf(inList, inQuote).forEach { md ->
            val document = parser.parseDocument(md)!!
            assertEquals("one top-level block", 1, blocksOf(document).size)
            assertFalse("nothing was cut anywhere", containsPiece(document))
        }
        // Control: the very same lines at the top level are cut.
        assertTrue(containsPiece(parser.parseDocument(fenced(code))!!))
    }

    private fun containsPiece(node: Node): Boolean =
        node is CodeBlockPiece || generateSequence(node.firstChild) { it.next }.any { containsPiece(it) }

    @Test
    fun `a document split twice is not cut again`() {
        val document = documentOf(lines(2_100))
        CodeBlockSplitter.split(document)
        val once = blocksOf(document).map { (it as CodeBlockPiece).literal }

        CodeBlockSplitter.split(document)

        assertEquals(once, blocksOf(document).map { (it as CodeBlockPiece).literal })
    }

    @Test
    fun `the pieces of a prefix start where the pieces of the whole block do`() {
        val literal = (1..900).joinToString("") { n -> "row $n ${"c".repeat(n % 150)}\n" }
        val whole = CodeBlockSplitter.pieceStarts(literal)
        assertTrue("the fixture has several pieces, got ${whole.size}", whole.size > 4)
        val prefixLengths = listOf(
            0, 1, 77,
            whole[1] - 1, whole[1], whole[1] + 1,
            whole[2] - 1, whole[2], whole[2] + 1,
            whole[3] + 40, literal.length / 2, literal.length - 1, literal.length,
        )

        prefixLengths.forEach { length ->
            val expected = whole.filter { it < length }
            val actual = CodeBlockSplitter.pieceStarts(literal.substring(0, length)).toList()
            assertEquals("prefix of $length chars", expected.ifEmpty { listOf(0) }, actual)
        }
    }

    @Test
    fun `the pieces of a prefix agree for long lines too, where the character rule decides`() {
        val literal = (1..120).joinToString("") { n -> "q".repeat(180 + n % 7 * 90) + "\n" }
        val whole = CodeBlockSplitter.pieceStarts(literal)
        assertTrue(whole.size > 3)

        for (length in listOf(1_000, 8_000, 8_001, whole[1], whole[1] + 5, whole[2] - 1, literal.length - 2)) {
            val expected = whole.filter { it < length }.ifEmpty { listOf(0) }
            assertEquals(
                "prefix of $length chars",
                expected,
                CodeBlockSplitter.pieceStarts(literal.substring(0, length)).toList(),
            )
        }
    }

    @Test
    fun `pieceAt finds the piece holding an offset, at and around every boundary`() {
        val literal = lines(2_250)
        val group = piecesOf(literal).first().group

        for (index in 0 until group.pieceCount) {
            assertEquals("first char of piece $index", index, group.pieceAt(group.start(index)))
            assertEquals("last char of piece $index", index, group.pieceAt(group.end(index) - 1))
            if (index > 0) assertEquals("char before piece $index", index - 1, group.pieceAt(group.start(index) - 1))
            assertEquals("middle of piece $index", index, group.pieceAt((group.start(index) + group.end(index)) / 2))
        }
        assertEquals("before the start clamps", 0, group.pieceAt(-7))
        assertEquals("the end of the literal is the last piece", group.pieceCount - 1, group.pieceAt(literal.length))
        assertEquals("past the end clamps", group.pieceCount - 1, group.pieceAt(literal.length + 500))
        assertEquals("the last piece ends at the literal's end", literal.length, group.end(group.pieceCount - 1))
        assertEquals(0, group.start(0))
    }

    @Test
    fun `the parser path agrees with the splitter on the pieces`() {
        val literal = lines(2_250)
        val blocks = blocksOf(parser.parseDocument(fenced(literal))!!).map { it as CodeBlockPiece }

        assertEquals(piecesOf(literal).map { it.literal }, blocks.map { it.literal })
        assertEquals(literal, blocks.joinToString("") { it.literal })
        assertEquals(
            "the parser's own tree (no transform) has the one block",
            1,
            blocksOf(Parser.builder().build().parse(fenced(literal))).size,
        )
    }
}
