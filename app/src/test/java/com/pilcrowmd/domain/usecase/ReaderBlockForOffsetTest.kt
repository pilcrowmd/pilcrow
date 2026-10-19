// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.testing.codeLines
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ParseMarkdownHeadingsUseCase.readerBlockForOffset]: the reader adapter position of the block the
 * editor caret sat in, which the reader reveals above the keyboard after an Edit-to-Read switch.
 * The expected positions are cross-checked against [AdapterBlocks.of] of the full parse, the list the
 * adapter is numbered by.
 */
class ReaderBlockForOffsetTest {
    private val useCase = ParseMarkdownHeadingsUseCase()

    private val content = """
        # Title

        First paragraph.

        ```kotlin
        val x = 1
        ```

        - item one
        - item two

        [ref]: https://example.com

        Text with a note[^1].

        [^1]: The note body.

        ### Last Line!
    """.trimIndent()

    /** Each block's marker text and the adapter position it has (the link definition has none). */
    private val markers = listOf(
        "# Title" to 0,
        "First paragraph." to 1,
        "val x = 1" to 2,
        "item two" to 3,
        "[ref]: https://example.com" to 3, // takes no position: the block before it
        "Text with a note" to 4,
        "The note body." to 5,
        "### Last Line!" to 6,
    )

    @Test
    fun `the fixture has the blocks the expectations assume`() {
        val blocks = AdapterBlocks.of(useCase.parseDocument(content)!!)
        assertEquals(7, blocks.size)
        assertEquals(true, blocks[0] is Heading)
        assertEquals(true, blocks[1] is Paragraph)
        assertEquals(true, blocks[2] is FencedCodeBlock)
        assertEquals(true, blocks[6] is Heading)
    }

    @Test
    fun `an offset inside each block maps to that block's adapter position`() {
        for ((marker, expected) in markers) {
            val start = content.indexOf(marker)
            // First character, a middle one and the last one of the marker text.
            for (offset in listOf(start, start + marker.length / 2, start + marker.length - 1)) {
                assertEquals("offset $offset in \"$marker\"", expected, useCase.readerBlockForOffset(content, offset))
            }
        }
    }

    @Test
    fun `an offset at the very end maps to the last block`() {
        assertEquals(6, useCase.readerBlockForOffset(content, content.length))
    }

    @Test
    fun `an out-of-range offset is clamped`() {
        assertEquals(0, useCase.readerBlockForOffset(content, -5))
        assertEquals(6, useCase.readerBlockForOffset(content, content.length + 99))
    }

    @Test
    fun `an offset inside frontmatter maps to the frontmatter block`() {
        val doc = "---\ntitle: x\n---\n\n# Body\n"
        assertEquals(0, useCase.readerBlockForOffset(doc, doc.indexOf("title")))
        assertEquals(1, useCase.readerBlockForOffset(doc, doc.indexOf("Body")))
    }

    @Test
    fun `an empty document has no block`() {
        assertNull(useCase.readerBlockForOffset("", 0))
    }

    @Test
    fun `a document the reader shows as plain text has no block`() {
        val tooDeep = ">".repeat(150) + " deep"
        assertNull(useCase.readerBlockForOffset(tooDeep, tooDeep.length))
    }

    @Test
    fun `a caret after a blank line inside a fenced admonition is in that admonition`() {
        // M-251: the closing `:::` lies past the caret, so the prefix alone would read the opener as prose.
        val md = "::: note\np1\n\np2\n:::\nAfter"
        assertEquals(2, AdapterBlocks.of(useCase.parseDocument(md)!!).size)
        assertEquals(0, useCase.readerBlockForOffset(md, md.indexOf("p2")))
        assertEquals(1, useCase.readerBlockForOffset(md, md.indexOf("After")))
    }

    /**
     * Issue #17: a long code block is several adapter items, so a caret deep in it points at its own piece,
     * not at the block's first. The expected positions are cross-checked against [AdapterBlocks.of].
     */
    private val longBlock = "# Title\n\n```rust\n" + codeLines(3_000) + "```\n\nAfter the block.\n"

    private fun offsetOfRow(content: String, row: Int) = content.indexOf("row $row.")

    @Test
    fun `the fixture is cut into pieces of 100 lines between a heading and a paragraph`() {
        val blocks = AdapterBlocks.of(useCase.parseDocument(longBlock)!!)
        assertEquals(1 + 30 + 1, blocks.size)
        assertEquals(true, blocks[0] is Heading)
        assertEquals(30, blocks.count { it is CodeBlockPiece })
        assertEquals(true, blocks.last() is Paragraph)
    }

    @Test
    fun `a caret on line 150 of a long block is in its second piece`() {
        assertEquals(2, useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 150)))
    }

    @Test
    fun `a caret deep in a long block is in the piece holding that line`() {
        // Piece p (from 0) holds lines 100p + 1 to 100p + 100, and the heading is item 0.
        assertEquals(
            "line 2,500 ends piece 24",
            1 + 24,
            useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 2_500)),
        )
        assertEquals(
            "line 2,501 opens piece 25",
            1 + 25,
            useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 2_501)),
        )
        assertEquals("the last line", 1 + 29, useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 3_000)))
    }

    @Test
    fun `a caret at each side of a piece boundary is in the matching piece`() {
        assertEquals(
            "line 100 ends the first piece",
            1,
            useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 100)),
        )
        assertEquals(
            "line 101 opens the second",
            2,
            useCase.readerBlockForOffset(longBlock, offsetOfRow(longBlock, 101)),
        )
        val endOfLine100 = longBlock.indexOf('\n', offsetOfRow(longBlock, 100))
        assertEquals("the line end of line 100", 1, useCase.readerBlockForOffset(longBlock, endOfLine100))
    }

    @Test
    fun `a caret on the opening fence of a long block is in its first piece`() {
        assertEquals(1, useCase.readerBlockForOffset(longBlock, longBlock.indexOf("```rust") + 3))
    }

    @Test
    fun `a caret in the paragraph after a long block is after its last piece`() {
        val blocks = AdapterBlocks.of(useCase.parseDocument(longBlock)!!)
        val lastPiece = blocks.indexOfLast { it is CodeBlockPiece }
        assertEquals(31, lastPiece + 1)
        assertEquals(lastPiece + 1, useCase.readerBlockForOffset(longBlock, longBlock.indexOf("After the block")))
        assertEquals(
            "and the closing fence's line is still in the block",
            lastPiece,
            useCase.readerBlockForOffset(longBlock, longBlock.lastIndexOf("```")),
        )
    }

    @Test
    fun `a block cut only by its chars is mapped too, where the caret's prefix is too short to be cut`() {
        // 300 lines of 40 chars is 12,000 chars: cut. The text up to line 150 is 6,000: not cut, so
        // counting the prefix's blocks alone would point at the first piece.
        val content = "# Title\n\n```rust\n" + codeLines(300, width = 40) + "```\n\nAfter.\n"
        val caret = offsetOfRow(content, 150)
        check(caret - content.indexOf("row 1.") < 10_000) { "the prefix must be under the limit" }
        val blocks = AdapterBlocks.of(useCase.parseDocument(content)!!)
        check(
            blocks.count {
                it is CodeBlockPiece
            } == 3,
        ) { "the fixture is cut in three: ${blocks.map { it.javaClass.simpleName }}" }

        assertEquals("line 150 is in the second piece", 2, useCase.readerBlockForOffset(content, caret))
        assertEquals("line 50 is in the first", 1, useCase.readerBlockForOffset(content, offsetOfRow(content, 50)))
        assertEquals("line 250 is in the third", 3, useCase.readerBlockForOffset(content, offsetOfRow(content, 250)))
    }

    @Test
    fun `a block that is not cut maps as before`() {
        val content = "# Title\n\n```rust\n" + codeLines(300) + "```\n\nAfter.\n"

        assertEquals(1, useCase.readerBlockForOffset(content, offsetOfRow(content, 250)))
        assertEquals(2, useCase.readerBlockForOffset(content, content.indexOf("After.")))
    }
}
