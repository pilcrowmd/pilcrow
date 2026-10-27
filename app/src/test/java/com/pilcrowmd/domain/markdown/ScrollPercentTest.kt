// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scroll percent: each block's share of the text, the number shown, and the "more than 6 screens" test. */
class ScrollPercentTest {

    private fun starts(markdown: String): IntArray {
        val document = ParseMarkdownHeadingsUseCase().parseDocument(markdown)!!
        return ScrollPercent.textStarts(AdapterBlocks.of(document))
    }

    // --- textStarts ---

    @Test
    fun `starts add up the text of each block and end with the total`() {
        // A heading (5 letters, below the minimum), a 100-letter paragraph, a code block of 200 characters.
        val markdown = "# Title\n\n${"a".repeat(100)}\n\n```\n${"x".repeat(199)}\n```\n"
        assertArrayEquals(intArrayOf(0, 40, 140, 340), starts(markdown))
    }

    @Test
    fun `starts never go down`() {
        val markdown = (1..30).joinToString("\n\n") { i -> if (i % 3 == 0) "# H$i" else "word ".repeat(i * 7) }
        val s = starts(markdown)
        assertEquals(31, s.size)
        for (i in 1 until s.size) assertTrue("starts[$i] went down", s[i] >= s[i - 1])
        assertTrue(s.last() > 0)
    }

    @Test
    fun `a heading counts as about one line of text`() {
        val s = starts("# Hi")
        assertEquals(ScrollPercent.MIN_BLOCK_WEIGHT, s[1] - s[0])
    }

    @Test
    fun `inline code and text inside emphasis are counted`() {
        // 60 letters, 30 of inline code, 10 inside bold: 100 in all.
        val s = starts("${"a".repeat(60)}`${"b".repeat(30)}`**${"c".repeat(10)}**")
        assertEquals(100, s[1] - s[0])
    }

    @Test
    fun `an HTML block is counted by its text`() {
        val html = "<div>${"x".repeat(95)}</div>"
        val weight = starts(html).let { it[1] - it[0] }
        assertTrue("weight $weight", weight in html.length..html.length + 1)
    }

    @Test
    fun `no blocks give a single zero`() {
        assertArrayEquals(intArrayOf(0), ScrollPercent.textStarts(emptyList()))
        assertArrayEquals(intArrayOf(0), ScrollPercent.textStartsOf(emptyList()))
    }

    @Test
    fun `plain chunks are counted by their length, with the same minimum`() {
        val s = ScrollPercent.textStartsOf(listOf("a".repeat(100), "b", "c".repeat(10)))
        assertArrayEquals(intArrayOf(0, 100, 140, 180), s)
    }

    // --- percent ---

    private val four = intArrayOf(0, 100, 200, 300, 400)

    @Test
    fun `the top is 0 and the end is 100`() {
        assertEquals(0, ScrollPercent.percent(four, 0, 0f, atTop = true, atEnd = false))
        assertEquals(100, ScrollPercent.percent(four, 3, 0.9f, atTop = false, atEnd = true))
    }

    @Test
    fun `the end wins when both ends are reached`() {
        assertEquals(100, ScrollPercent.percent(four, 0, 0f, atTop = true, atEnd = true))
    }

    @Test
    fun `the middle is the share of text above the top of the screen`() {
        // Two blocks of 100 are above block 2, of 400 in all.
        assertEquals(50, ScrollPercent.percent(four, 2, 0f, atTop = false, atEnd = false))
        // 140 of 400 is 35.
        assertEquals(35, ScrollPercent.percent(four, 1, 0.4f, atTop = false, atEnd = false))
    }

    @Test
    fun `between the ends it is never 0 or 100`() {
        assertEquals(1, ScrollPercent.percent(four, 0, 0.0001f, atTop = false, atEnd = false))
        assertEquals(99, ScrollPercent.percent(four, 3, 0.999f, atTop = false, atEnd = false))
    }

    @Test
    fun `a block index past the end is held at the last block`() {
        assertEquals(99, ScrollPercent.percent(four, 40, 1f, atTop = false, atEnd = false))
    }

    // --- withoutHidden ---

    @Test
    fun `hidden blocks weigh nothing and the rest keep their weight`() {
        assertArrayEquals(
            intArrayOf(0, 100, 100, 100, 200),
            ScrollPercent.withoutHidden(four) { it == 1 || it == 2 },
        )
    }

    @Test
    fun `nothing hidden gives the same weights, everything hidden gives none`() {
        assertArrayEquals(four, ScrollPercent.withoutHidden(four) { false })
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), ScrollPercent.withoutHidden(four) { true })
        assertArrayEquals(intArrayOf(0), ScrollPercent.withoutHidden(intArrayOf(0)) { true })
    }

    @Test
    fun `the percent does not jump across a closed section`() {
        val withSection = intArrayOf(0, 100, 200, 1200)
        assertEquals(12, ScrollPercent.percent(withSection, 1, 0.4f, atTop = false, atEnd = false))
        val taken = ScrollPercent.withoutHidden(withSection) { it == 2 }
        assertEquals(70, ScrollPercent.percent(taken, 1, 0.4f, atTop = false, atEnd = false))
    }

    // --- rows ---

    private fun counts(markdown: String): ScrollPercent.RowCounts {
        val document = ParseMarkdownHeadingsUseCase().parseDocument(markdown)!!
        return ScrollPercent.rowCounts(AdapterBlocks.of(document))
    }

    private val none: (Int) -> Boolean = { false }

    /** [n] blocks of [chars] characters of prose each. */
    private fun prose(n: Int, chars: Int) = ScrollPercent.RowCounts(IntArray(n) { chars }, IntArray(n))

    @Test
    fun `prose counts its characters and a code block its lines`() {
        val code = List(10) { "x".repeat(300) }.joinToString("\n")
        val c = counts("${"a".repeat(100)}\n\n```\n$code\n```\n")
        assertArrayEquals(intArrayOf(100, 0), c.chars)
        assertArrayEquals(intArrayOf(0, 10), c.lines)
        // 100 characters at 40 to a row are 3 rows; the code block is 10 lines however wide they are.
        assertEquals(13L, ScrollPercent.estimatedRows(c, none, charsPerLine = 40))
    }

    @Test
    fun `a block takes the larger of its lines and its wrapped prose, with no gap rows`() {
        val c = ScrollPercent.RowCounts(intArrayOf(100, 0, 40), intArrayOf(2, 5, 0))
        // max(2, 3) + max(5, 0) + max(0, 1)
        assertEquals(9L, ScrollPercent.estimatedRows(c, none, charsPerLine = 40))
    }

    @Test
    fun `HTML, picture text, formulas and diagrams count nothing`() {
        val comment = "<!--${"x".repeat(15_000)}-->"
        val picture = "![a long description of the picture](p.png)"
        val formula = "Euler: \$e^{i}+1=0\$ done"
        val c = counts("$comment\n\n$picture\n\n$formula\n\n```mermaid\ngraph TD\nA-->B\n```")
        assertArrayEquals(intArrayOf(0, 0, 0, 0), c.chars)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), c.lines)
    }

    /** S8-A31: past the draw limit a diagram is shown as source in pieces, so it has the lines of any code block. */
    @Test
    fun `a diagram too long to draw counts its lines like any code block`() {
        val source = (1..5_100).joinToString("") { "  A$it --> B$it\n" }
        assertTrue("fixture: past the limit", source.length > CodeBlockSplitter.MERMAID_MAX_CHARS)

        val diagram = counts("```mermaid\n$source```").lines.sum()
        val code = counts("```text\n$source```").lines.sum()

        assertTrue("fixture: the control has lines", code > 5_000)
        assertEquals(code, diagram)
    }

    @Test
    fun `a table counts its rows`() {
        val c = counts("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")
        assertArrayEquals(intArrayOf(3), c.lines)
        assertArrayEquals(intArrayOf(0), c.chars)
    }

    @Test
    fun `a plain-text chunk counts its lines`() {
        val c = ScrollPercent.rowCountsOf(listOf("a\nb\nc", "x\n", ""))
        assertArrayEquals(intArrayOf(3, 1, 0), c.lines)
    }

    @Test
    fun `hidden blocks take no rows`() {
        val c = prose(4, 80)
        assertEquals(8L, ScrollPercent.estimatedRows(c, none, charsPerLine = 40))
        assertEquals(4L, ScrollPercent.estimatedRows(c, { it >= 2 }, charsPerLine = 40))
        assertEquals(0L, ScrollPercent.estimatedRows(c, { true }, charsPerLine = 40))
    }

    // --- isLong ---

    @Test
    fun `55 one-line paragraphs are not long`() {
        // 55 rows of 77 px are 4235 px, under two screens of 1300 px: never to be judged long.
        assertFalse(ScrollPercent.isLong(prose(55, 39), none, 49, lineHeightPx = 77, viewportHeightPx = 1300))
    }

    @Test
    fun `a short page with a huge HTML comment is not long`() {
        val comment = "<!--${"x".repeat(15_000)}-->"
        val c = counts((1..30).joinToString("\n\n") { "Paragraph $it." } + "\n\n" + comment)
        assertFalse(ScrollPercent.isLong(c, none, 40, lineHeightPx = 77, viewportHeightPx = 1300))
    }

    @Test
    fun `a big closed section does not make a short page long`() {
        // Two blocks of 100 characters and a closed one of 4000, at 10 to a row and 50 px rows on a
        // 1000 px screen: 20 rows once the section is out, 420 with it.
        val c = ScrollPercent.RowCounts(intArrayOf(100, 100, 4000), IntArray(3))
        assertTrue(ScrollPercent.isLong(c, none, 10, lineHeightPx = 50, viewportHeightPx = 1000))
        assertFalse(ScrollPercent.isLong(c, { it == 2 }, 10, lineHeightPx = 50, viewportHeightPx = 1000))
    }

    @Test
    fun `a document of more than six screens of prose is long`() {
        // 100 blocks of 400 characters: 10 rows each at 40 to a row, 50,000 px.
        assertTrue(ScrollPercent.isLong(prose(100, 400), none, 40, lineHeightPx = 50, viewportHeightPx = 1000))
    }

    @Test
    fun `exactly six screens of rows is not long and just above is`() {
        // 6 screens are 6000 px, 120 rows of 50 px.
        assertFalse(ScrollPercent.isLong(prose(120, 40), none, 40, lineHeightPx = 50, viewportHeightPx = 1000))
        assertTrue(ScrollPercent.isLong(prose(121, 40), none, 40, lineHeightPx = 50, viewportHeightPx = 1000))
    }

    @Test
    fun `narrower rows or taller lines make a document longer`() {
        val c = prose(1, 4000)
        assertFalse(ScrollPercent.isLong(c, none, charsPerLine = 40, lineHeightPx = 50, viewportHeightPx = 1000))
        assertTrue(ScrollPercent.isLong(c, none, charsPerLine = 20, lineHeightPx = 50, viewportHeightPx = 1000))
        assertTrue(ScrollPercent.isLong(c, none, charsPerLine = 40, lineHeightPx = 80, viewportHeightPx = 1000))
    }

    @Test
    fun `nothing to judge is not long`() {
        val big = prose(1, 8000)
        assertFalse(ScrollPercent.isLong(prose(0, 0), none, 40, 50, 1000))
        assertFalse(ScrollPercent.isLong(big, none, 0, 50, 1000))
        assertFalse(ScrollPercent.isLong(big, none, 40, 0, 1000))
        assertFalse(ScrollPercent.isLong(big, none, 40, 50, 0))
    }
}
