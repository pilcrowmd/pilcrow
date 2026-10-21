// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.CodeBlockSplitter
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.usecase.ReadEditLanding.ReaderPoint
import com.pilcrowmd.testing.codeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #15 (M-388, NEW-105): [ReadEditLanding], the translation between a reader block and an editor
 * offset, for Markdown blocks and for plain-text chunks. The expected offsets are written from the
 * fixtures (`indexOf` of a marker), not computed by the code under test, and the blocks are the list
 * [AdapterBlocks.of] numbers the reader by.
 */
class ReadEditLandingTest {
    private val useCase = ParseMarkdownHeadingsUseCase()
    private val landing = ReadEditLanding(useCase)

    private fun blocksOf(content: String) = AdapterBlocks.of(useCase.parseDocument(content)!!)

    private fun isLineStart(content: String, offset: Int) = offset == 0 || content[offset - 1] == '\n'

    private val mixed = """
        ---
        title: Doc
        ---

        # First

        Para one line one
        line two of it.

        ## Second

        - item a
        - item b


        ```kotlin
        val x = 1
        ```

        [ref]: https://example.com

        Last paragraph.
    """.trimIndent()

    @Test
    fun `every block of a mixed document lands on the start of its first source line`() {
        val blocks = blocksOf(mixed)
        // Frontmatter, heading, paragraph, heading, list, code, paragraph; the link definition has none.
        assertEquals(7, blocks.size)
        val expected = listOf(
            0, // the frontmatter
            mixed.indexOf("# First"),
            mixed.indexOf("Para one line one"),
            mixed.indexOf("## Second"),
            mixed.indexOf("- item a"),
            mixed.indexOf("```kotlin"),
            mixed.indexOf("Last paragraph."),
        )
        for (b in blocks.indices) {
            val offset = landing.offsetForBlock(mixed, plain = false, point = ReaderPoint(b, 0f))
            assertEquals("block $b", expected[b], offset)
            assertEquals("block of offset $offset", b, landing.blockForOffset(mixed, plain = false, offset = offset!!))
        }
    }

    @Test
    fun `with the blocks at hand a code block lands on its first code line, below the fence`() {
        val blocks = blocksOf(mixed)
        val offset = landing.offsetForBlock(mixed, plain = false, point = ReaderPoint(5, 0f), blocks = blocks)
        assertEquals(mixed.indexOf("val x = 1"), offset)
        val heading = landing.offsetForBlock(mixed, plain = false, point = ReaderPoint(1, 0f), blocks = blocks)
        assertEquals(mixed.indexOf("# First"), heading)
    }

    /** About 3,000 blocks of five kinds, separated by one or two blank lines. */
    private val generated: String = buildString {
        for (i in 0 until 3_000) {
            when (i % 5) {
                0 -> append("## Heading $i\n")
                1 -> append("Para $i line a\nline b of $i\nline c of $i\n")
                2 -> append("- item $i a\n- item $i b\n")
                3 -> append("```\ncode $i\n```\n")
                else -> append("Single line paragraph $i.\n")
            }
            append(if (i % 3 == 0) "\n\n" else "\n")
        }
    }

    @Test
    fun `in a document of 3000 blocks each block lands on its first line and no earlier one`() {
        val blocks = blocksOf(generated)
        assertEquals(3_000, blocks.size)
        for (b in blocks.indices step 37) {
            val offset = landing.offsetForBlock(generated, plain = false, point = ReaderPoint(b, 0f))!!
            assertTrue("block $b: $offset is a line start", isLineStart(generated, offset))
            assertEquals("block of the line", b, useCase.readerBlockForOffset(generated, offset, blocks))
            if (b > 0) {
                val previousLine = generated.lastIndexOf('\n', offset - 2) + 1
                val before = useCase.readerBlockForOffset(generated, previousLine, blocks)!!
                assertTrue("block $b: the line above ($previousLine) maps to $before, not the first", before < b)
            }
        }
    }

    @Test
    fun `a search that is told to stop returns a line start inside the document`() {
        val offset = landing.offsetForBlock(
            generated,
            plain = false,
            point = ReaderPoint(1_500, 0f),
            keepGoing = { false },
        )
        assertNotNull(offset)
        assertTrue("$offset within the document", offset!! in 0..generated.length)
        assertTrue("$offset is a line start", isLineStart(generated, offset))
    }

    private val longCode = "# Title\n\n```rust\n" + codeLines(2_500) + "```\n\nAfter the block.\n"

    @Test
    fun `a piece of a long code block lands the fraction of its lines down, below the fence`() {
        val blocks = blocksOf(longCode)
        assertEquals(1 + 25 + 1, blocks.size)
        assertEquals(CodeBlockSplitter.PIECE_LINES, 100)
        val piece = 1 + 7 // the heading is block 0
        assertTrue(blocks[piece] is CodeBlockPiece)

        val middle = landing.offsetForBlock(longCode, plain = false, point = ReaderPoint(piece, 0.5f), blocks = blocks)
        // Piece 7 opens on row 701; half way down is 50 lines further, row 751.
        assertEquals(longCode.indexOf("row 751."), middle)
        val top = landing.offsetForBlock(longCode, plain = false, point = ReaderPoint(piece, 0f), blocks = blocks)
        assertEquals(longCode.indexOf("row 701."), top)
    }

    @Test
    fun `the first piece at the top lands on the first code line, not the fence`() {
        val blocks = blocksOf(longCode)
        val offset = landing.offsetForBlock(longCode, plain = false, point = ReaderPoint(1, 0f), blocks = blocks)
        assertEquals(longCode.indexOf("row 1."), offset)
    }

    /** 1,000 lines, a blank one after every six, except one run of 450 lines with none. */
    private val plainText: String = (0 until 1_000).joinToString("\n") { i ->
        if (i % 7 == 6 && i !in 300 until 750) "" else "line $i of the text"
    }

    @Test
    fun `plain chunks start where the split puts them and are found and landed on`() {
        val chunks = PlainTextChunks.split(plainText)
        val pieces = PlainTextChunks.pieces(plainText)
        assertTrue("the fixture is cut into several chunks: ${chunks.size}", chunks.size >= 4)
        assertTrue("one cut is forced by the line cap", pieces.any { it.forcedBefore })
        val starts = PlainTextChunks.chunkStarts(plainText)
        assertEquals(chunks.size, starts.size)
        assertEquals(0, starts[0])
        for (i in chunks.indices) {
            assertTrue("chunk $i is where its literal is", plainText.startsWith(chunks[i], starts[i]))
            if (i + 1 < chunks.size) assertEquals("chunk ${i + 1}", starts[i] + chunks[i].length + 1, starts[i + 1])
            val blockAtStart = landing.blockForOffset(plainText, plain = true, starts[i])
            assertEquals("block of the start of chunk $i", i, blockAtStart)
            val lines = chunks[i].split("\n")
            val halfWay = starts[i] + lines.take(lines.size / 2).sumOf { it.length + 1 }
            assertEquals(
                "half way down chunk $i",
                halfWay,
                landing.offsetForBlock(plainText, plain = true, point = ReaderPoint(i, 0.5f)),
            )
        }
    }

    @Test
    fun `a later piece that opens with a fence-like line lands on that line, not below it`() {
        // A four-backtick block holding a fenced block: the inner fence is a code line, and it opens
        // piece 1 (piece 0 is lines 1 to 100). Only the whole block's first piece has a fence above it.
        val inner = codeLines(100) + "```kotlin\n" + (1..2_000).joinToString("") { "tail $it\n" } + "```\n"
        val doc = "# Title\n\n````md\n$inner````\n\nAfter the block.\n"
        val blocks = blocksOf(doc)
        val piece = 2 // the heading is block 0 and piece 0 is block 1
        assertTrue("fixture: block $piece is a later piece", (blocks[piece] as CodeBlockPiece).index == 1)

        val offset = landing.offsetForBlock(doc, plain = false, point = ReaderPoint(piece, 0f), blocks = blocks)

        assertEquals(doc.indexOf("```kotlin"), offset)
    }

    @Test
    fun `a search that runs out of probes returns a line near the block`() {
        val target = 2_000
        val near = target - 25..target + 25
        val blocks = blocksOf(generated)
        for (probes in 1..10) {
            var left = probes
            val offset = useCase.offsetOfReaderBlock(generated, target, blocks) { left-- > 0 }!!
            val block = useCase.readerBlockForOffset(generated, offset, blocks)!!
            assertTrue("after $probes probes: line $offset is in block $block, far from $target", block in near)
        }
    }
}
