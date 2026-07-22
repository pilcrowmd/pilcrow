// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.model.HeadingNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ParseMarkdownHeadingsUseCase.
 * Verifies behavior parity with the ViewModel's original extractHeadings method, plus the parity
 * [ParseMarkdownHeadingsUseCase.parseDocument] shared with SearchMarkdownUseCase (raw-source offset
 * reconstruction itself is covered via match start indices in SearchMarkdownUseCaseTest).
 */
class ParseMarkdownHeadingsUseCaseTest {
    private val useCase = ParseMarkdownHeadingsUseCase()

    @Test
    fun testExtractHeadingsWithSimpleMarkdown() {
        val content = "# H1\n## H2\nPara\n### H3"
        val headings = useCase.extractHeadings(content)

        assertEquals(3, headings.size)
        assertEquals(HeadingNode(level = 1, text = "H1", adapterPosition = 0), headings[0])
        assertEquals(HeadingNode(level = 2, text = "H2", adapterPosition = 1), headings[1])
        assertEquals(HeadingNode(level = 3, text = "H3", adapterPosition = 3), headings[2])
    }

    @Test
    fun testExtractHeadingsWithNoHeadings() {
        val content = "Just some plain text\nNo headings here"
        val headings = useCase.extractHeadings(content)

        assertTrue(headings.isEmpty())
    }

    @Test
    fun testExtractHeadingsWithInlineFormatting() {
        val content = "# Bold **text** and [link](url)\n## Another **bold** heading"
        val headings = useCase.extractHeadings(content)

        assertEquals(2, headings.size)
        // Text extraction should pull out just the text content, not the markdown syntax
        assertEquals("Bold text and link", headings[0].text)
        assertEquals("Another bold heading", headings[1].text)
    }

    @Test
    fun testExtractHeadingsWithMixedLevels() {
        val content = "# H1\n### H3\n## H2\n#### H4"
        val headings = useCase.extractHeadings(content)

        assertEquals(4, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals(3, headings[1].level)
        assertEquals(2, headings[2].level)
        assertEquals(4, headings[3].level)
    }

    @Test
    fun testParseDocumentSplitsTopLevelBlocks() {
        // Two paragraphs separated by a blank line → two top-level children (1:1 with adapter items).
        val doc = useCase.parseDocument("A\n\nB")

        assertNotNull(doc)
        assertEquals(2, topLevelChildCount(doc!!))
    }

    @Test
    fun testParseDocumentEmptyContent() {
        // Empty content parses to an empty (non-null) document — no blocks, never a crash.
        val doc = useCase.parseDocument("")

        assertNotNull(doc)
        assertEquals(0, topLevelChildCount(doc!!))
    }

    @Test
    fun testParseDocumentSingleBlock() {
        val doc = useCase.parseDocument("Just one block")

        assertNotNull(doc)
        assertEquals(1, topLevelChildCount(doc!!))
    }

    @Test
    fun testParseDocumentMalformedDoesNotThrow() {
        // Lenient markdown: "malformed" input still parses (graceful degradation, never throws).
        val doc = useCase.parseDocument("[[[ Invalid markdown")

        assertNotNull(doc)
    }

    private fun topLevelChildCount(doc: org.commonmark.node.Node): Int {
        var count = 0
        var child = doc.firstChild
        while (child != null) {
            count++
            child = child.next
        }
        return count
    }

    @Test
    fun testExtractHeadingsWithEmptyContent() {
        val content = ""
        val headings = useCase.extractHeadings(content)

        assertTrue(headings.isEmpty())
    }

    @Test
    fun testExtractHeadingsPreservesLevel() {
        val content = "# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6"
        val headings = useCase.extractHeadings(content)

        assertEquals(6, headings.size)
        for (i in 0..5) {
            assertEquals(i + 1, headings[i].level)
        }
    }

    @Test
    fun testExtractHeadingsExcludesYamlFrontmatter() {
        // A leading ---...--- block is YAML frontmatter (one yaml block), NOT a setext
        // heading. It must not pollute the TOC. The real H1 below is the only heading; with
        // frontmatter as block 0, the heading is at adapterPosition 1.
        val content = "---\ntitle: My Doc\nauthor: pleree\n---\n\n# Real Heading\n\nBody."
        val headings = useCase.extractHeadings(content)

        assertEquals(1, headings.size)
        assertEquals(HeadingNode(level = 1, text = "Real Heading", adapterPosition = 1), headings[0])
    }

    @Test
    fun testExtractHeadingsAdapterPositionAlignsAcrossTable() {
        // A GFM table is a single top-level block (matching the MarkwonAdapter). The heading after
        // it must report the adapter position that counts the table as exactly one item.
        val content = buildString {
            append("# Before\n\n")
            append("| A | B |\n| - | - |\n| 1 | 2 |\n\n")
            append("## After\n")
        }
        val headings = useCase.extractHeadings(content)

        assertEquals(2, headings.size)
        assertEquals(HeadingNode(level = 1, text = "Before", adapterPosition = 0), headings[0])
        // block 0 = "# Before", block 1 = table, block 2 = "## After"
        assertEquals(HeadingNode(level = 2, text = "After", adapterPosition = 2), headings[1])
    }

    @Test
    fun testExtractHeadingsWithSpecialCharacters() {
        val content = "# Heading with *asterisks* and `code`\n## Heading with [link](url) and **bold**"
        val headings = useCase.extractHeadings(content)

        assertEquals(2, headings.size)
        // Text should be extracted cleanly - check it contains key words without markdown
        assertTrue(headings[0].text.contains("Heading"))
        assertTrue(headings[0].text.contains("asterisks"))
        assertTrue(headings[1].text.contains("Heading"))
        assertTrue(headings[1].text.contains("link"))
    }
}
