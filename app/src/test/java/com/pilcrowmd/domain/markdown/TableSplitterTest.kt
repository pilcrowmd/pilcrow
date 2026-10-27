// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A table with very many cells is one reader item with one View per cell, bound on the main thread
 * (S8-A31 lead 3). [TableSplitter] cuts a top-level table into [TablePiece]s of a bounded number of body
 * rows, each drawn with the header. What these tests guard: no row is lost, moved or reordered by a cut
 * (Safeguard 2: the reader tree is a render copy, the text and the saved bytes are untouched), the
 * header travels with every piece, the thresholds sit exactly where the constants say, a table that fits
 * is the very node the parser built, and a wide table is bounded by its cell count, not only its rows.
 *
 * The documents go through the parity parser and [ReaderDocument.transform], the pass search and the
 * headings drawer use, so these also pin that the pass is wired in.
 */
class TableSplitterTest {

    private val parser = ParseMarkdownHeadingsUseCase()

    private fun table(rows: Int, columns: Int = 3): String = buildString {
        append((0 until columns).joinToString(" | ", "| ", " |\n") { "h$it" })
        append((0 until columns).joinToString(" | ", "| ", " |\n") { "---" })
        for (r in 0 until rows) append((0 until columns).joinToString(" | ", "| ", " |\n") { "r$r-$it" })
    }

    private fun blocksOf(document: Node): List<Node> = generateSequence(document.firstChild) { it.next }.toList()

    private fun tablesOf(markdown: String): List<TableBlock> =
        blocksOf(parser.parseDocument(markdown)!!).filterIsInstance<TableBlock>()

    private fun childrenOf(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    private fun cellTexts(row: Node): List<String> =
        childrenOf(row).filterIsInstance<TableCell>().map { cell -> (cell.firstChild as Text).literal }

    private fun bodyRows(table: TableBlock): List<List<String>> =
        childrenOf(table).filterIsInstance<TableBody>().flatMap { body -> childrenOf(body).map(::cellTexts) }

    private fun headerOf(head: TableHead): List<String> = cellTexts(head.firstChild)

    @Test
    fun `a table of five pieces keeps its header in each and every row in order`() {
        val rows = 5 * TableSplitter.PIECE_ROWS + 7
        val pieces = tablesOf(table(rows)).map { it as TablePiece }

        assertEquals("five full pieces and a remainder", 6, pieces.size)
        assertEquals("pieces are numbered in order", pieces.indices.toList(), pieces.map { it.index })
        pieces.forEach { piece ->
            assertEquals(listOf("h0", "h1", "h2"), headerOf(piece.head))
            assertSame("the pieces share the one header", pieces.first().head, piece.head)
            assertTrue("a piece holds at most PIECE_ROWS rows", bodyRows(piece).size <= TableSplitter.PIECE_ROWS)
        }
        assertFalse(pieces.first().repeatsHeader)
        assertTrue(pieces.drop(1).all { it.repeatsHeader })
        assertSame("the first piece owns the header", pieces.first().head, pieces.first().firstChild)
        pieces.drop(1).forEach { assertTrue("a later piece holds body rows only", it.firstChild is TableBody) }
        assertEquals(
            "every row, in order, none repeated",
            (0 until rows).map { r -> (0..2).map { "r$r-$it" } },
            pieces.flatMap(::bodyRows),
        )
    }

    @Test
    fun `a table that fits is the very node the parser built`() {
        val document = Parser.builder().extensions(listOf(TablesExtension.create())).build()
            .parse(table(TableSplitter.PIECE_ROWS)) as Document
        val before = document.firstChild

        TableSplitter.split(document)

        assertSame(before, document.firstChild)
        assertFalse(document.firstChild is TablePiece)
        assertEquals(1, blocksOf(document).size)
        assertEquals(TableSplitter.PIECE_ROWS, bodyRows(document.firstChild as TableBlock).size)
    }

    @Test
    fun `the threshold is exact, one row over is cut`() {
        assertEquals(1, tablesOf(table(TableSplitter.PIECE_ROWS)).size)
        assertFalse(tablesOf(table(TableSplitter.PIECE_ROWS)).single() is TablePiece)

        val over = tablesOf(table(TableSplitter.PIECE_ROWS + 1))
        assertEquals(2, over.size)
        assertEquals(listOf(TableSplitter.PIECE_ROWS, 1), over.map { bodyRows(it).size })
    }

    @Test
    fun `a wide table is cut by its cell count, so a piece never holds more than PIECE_CELLS body cells`() {
        val columns = 30
        val perPiece = TableSplitter.PIECE_CELLS / columns
        assertTrue("fixture: the cell limit, not the row limit, decides", perPiece < TableSplitter.PIECE_ROWS)

        val fits = tablesOf(table(perPiece, columns))
        assertEquals("exactly the cell limit stays whole", 1, fits.size)

        val pieces = tablesOf(table(perPiece + 1, columns))
        assertEquals(listOf(perPiece, 1), pieces.map { bodyRows(it).size })
    }

    @Test
    fun `cells past MAX_COLUMNS are dropped from every row of a table that has to be cut`() {
        val columns = TableSplitter.MAX_COLUMNS + 50
        val rows = 20
        assertTrue(
            "fixture: the rows alone do not fit one piece of the wide table",
            rows > TableSplitter.PIECE_CELLS / columns,
        )
        val pieces = tablesOf(table(rows, columns))

        assertTrue("fixture: it was cut: ${pieces.size}", pieces.size > 1)
        val cells = pieces.flatMap(::bodyRows)
        assertEquals("no row is lost", rows, cells.size)
        assertTrue("every row is capped", cells.all { it.size == TableSplitter.MAX_COLUMNS })
        val head = (pieces.first() as TablePiece).head
        assertEquals("the header is capped too", TableSplitter.MAX_COLUMNS, headerOf(head).size)
        assertEquals("the cells before the cap are untouched", "r0-${TableSplitter.MAX_COLUMNS - 1}", cells[0].last())
    }

    @Test
    fun `a small wide table is returned untouched, none of its columns lost`() {
        val columns = TableSplitter.MAX_COLUMNS + 50
        val rows = TableSplitter.PIECE_CELLS / columns
        val document = Parser.builder().extensions(listOf(TablesExtension.create())).build()
            .parse(table(rows, columns)) as Document
        val before = document.firstChild

        TableSplitter.split(document)

        assertSame("the very node the parser built", before, document.firstChild)
        assertFalse(document.firstChild is TablePiece)
        val kept = bodyRows(document.firstChild as TableBlock)
        assertEquals(rows, kept.size)
        assertTrue("no column dropped", kept.all { it.size == columns })
        val head = childrenOf(document.firstChild).filterIsInstance<TableHead>().single()
        assertEquals("the header keeps its columns", columns, headerOf(head).size)
    }

    @Test
    fun `one row wider than the cell limit is capped, though it is only one row`() {
        val columns = TableSplitter.PIECE_CELLS + 1
        val tables = tablesOf(table(1, columns))

        assertEquals(1, tables.size)
        assertEquals(listOf(TableSplitter.MAX_COLUMNS), bodyRows(tables.single()).map { it.size })
        val head = childrenOf(tables.single()).filterIsInstance<TableHead>().single()
        assertEquals(TableSplitter.MAX_COLUMNS, headerOf(head).size)
    }

    @Test
    fun `the blocks around a cut table keep their place`() {
        val md = "Before\n\n" + table(TableSplitter.PIECE_ROWS * 2 + 1) + "\nAfter\n"
        val blocks = blocksOf(parser.parseDocument(md)!!)

        assertEquals(5, blocks.size)
        assertTrue(blocks.first() is Paragraph)
        assertTrue(blocks.subList(1, 4).all { it is TablePiece })
        assertTrue(blocks.last() is Paragraph)
    }

    @Test
    fun `a table inside a list item or a block quote is not cut, it is not top-level`() {
        val big = table(TableSplitter.PIECE_ROWS * 2)
        val inQuote = big.lines().joinToString("\n") { "> $it" }
        val inList = "- item\n\n" + big.lines().joinToString("\n") { "  $it" }

        listOf(inQuote, inList).forEach { md ->
            val document = parser.parseDocument(md)!!
            assertEquals("one top-level block", 1, blocksOf(document).size)
            assertFalse("nothing was cut anywhere", containsPiece(document))
        }
    }

    private fun containsPiece(node: Node): Boolean =
        node is TablePiece || generateSequence(node.firstChild) { it.next }.any { containsPiece(it) }

    @Test
    fun `the pass is skipped when asked, as the PDF asks`() {
        val document = parser.parseTree(table(TableSplitter.PIECE_ROWS * 3))
        ReaderDocument.transform(document, splitLongParagraphs = false)

        assertEquals(1, tablesOf(document).size)
        assertFalse(document.firstChild is TablePiece)
    }

    private fun tablesOf(document: Node): List<TableBlock> = blocksOf(document).filterIsInstance<TableBlock>()
}
