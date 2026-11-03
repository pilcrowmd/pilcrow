// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.node.Code
import org.commonmark.node.Node
import org.commonmark.node.Text

/**
 * One piece of a long table, cut by [TableSplitter]: an ordinary [TableBlock] holding a run of the
 * table's body rows, so search, the headings drawer and every other walker see a table and count it as
 * one. The first piece owns the [head] (it is its first child); a later piece holds body rows only, and
 * the reader draws [head] above them again ([repeatsHeader]). Counting the header once is what keeps
 * search and the painted matches in step, so a repeated header is drawn but never highlighted.
 */
class TablePiece internal constructor(val head: TableHead, val index: Int) : TableBlock() {
    /** True when the header is not this piece's own child, so the reader must draw it above the rows. */
    val repeatsHeader: Boolean get() = index > 0
}

/**
 * The "…" a cell capped by [TableSplitter] shows where its text stops. It paints as any [Text]; it is
 * a class of its own so search knows the rest of the cell's source was dropped and moves its source
 * cursor to the cell's end: the next cell's text would otherwise lie further on than search looks.
 */
class CellEllipsis internal constructor() : Text("…")

/**
 * Cuts a long top-level table into [TablePiece]s, so the reader binds a bounded number of cells per
 * frame instead of one View per cell of the whole table on the main thread (S8-A31: a table of very
 * many cells could exhaust the heap or freeze the reader, and an `OutOfMemoryError` is not an
 * `Exception`). Display only: the text in memory and the saved bytes are untouched (Safeguard 2), and
 * the PDF export keeps the table whole.
 *
 * A piece holds at most [PIECE_ROWS] body rows and at most [PIECE_CELLS] body cells, so a wide table
 * is cut sooner. A table that fits is left exactly as the parser built it, however many columns it has.
 * A table that does not fit (too many rows for a piece, or more than [PIECE_CELLS] cells) also loses its
 * columns past [MAX_COLUMNS], from every row of the reader's copy, header included: the cell count of a
 * row is otherwise bounded only by the file, and one row could still be a million Views. Search walks the
 * same copy, so what is not drawn is not counted. Only a top-level table is cut, as with the paragraph
 * and code splitters.
 *
 * Every top-level table, cut or not, also has each cell's text capped at [MAX_CELL_CHARS] characters
 * and a "…" (S8-A61): a cell is one TextView, measured and laid out on the main thread, and a cell of a
 * million characters froze the reader for good. Capping the reader's copy rather than the drawn view
 * keeps search counting exactly what is drawn, as for the column cap.
 */
object TableSplitter {
    /** Body rows a piece may hold. */
    const val PIECE_ROWS = 200

    /** Body cells a piece may hold: a wide table gets fewer rows per piece, never fewer than one. */
    const val PIECE_CELLS = 3_000

    /** Columns drawn of a table that has to be cut; the ones past it are not (see the class doc). */
    const val MAX_COLUMNS = 200

    /** Characters of text one cell draws; the rest is shown as "…", as [MAX_COLUMNS] is for columns. */
    const val MAX_CELL_CHARS = 10_000

    /**
     * Cut every long top-level table of [document] in place. [inlineSource]: see [InlineCut]; the reader
     * passes its maths and emoji source, so a cell is capped where search caps it.
     */
    fun split(document: Node, inlineSource: (Node) -> String? = { null }) {
        val inline = InlineCut(inlineSource)
        var block = document.firstChild
        while (block != null) {
            val next = block.next
            if (block is TableBlock && block !is TablePiece) splitTable(block, inline)
            block = next
        }
    }

    private fun splitTable(table: TableBlock, inline: InlineCut) {
        val head = table.firstChild as? TableHead ?: return
        val body = head.next as? TableBody
        val headRow = head.firstChild
        val rows = body?.let(::rowsOf).orEmpty()
        headRow?.let { capCells(it, inline) }
        rows.forEach { capCells(it, inline) }
        // A table that fits is not touched at all: the column cap is for a table that is too big anyway.
        // The header counts as a row, so a header alone wider than the cell limit is still capped.
        val wide = columnsOf(headRow)
        val cells = rows.size.coerceAtLeast(1).toLong() * wide
        if (rows.size <= rowsPerPiece(wide) && cells <= PIECE_CELLS) return
        headRow?.let(::capColumns)
        rows.forEach(::capColumns)
        val perPiece = rowsPerPiece(columnsOf(headRow))
        if (rows.size <= perPiece) return
        var anchor: Node = table
        rows.chunked(perPiece).forEachIndexed { index, chunk ->
            val piece = TablePiece(head, index)
            val pieceBody = TableBody()
            chunk.forEach { pieceBody.appendChild(it) }
            if (index == 0) piece.appendChild(head)
            piece.appendChild(pieceBody)
            anchor.insertAfter(piece)
            anchor = piece
        }
        table.unlink()
    }

    private fun columnsOf(row: Node?): Int = generateSequence(row?.firstChild) { it.next }.count()

    /** Body rows a piece of a table of [columns] columns may hold. */
    private fun rowsPerPiece(columns: Int): Int = (PIECE_CELLS / columns.coerceAtLeast(1)).coerceIn(1, PIECE_ROWS)

    private fun rowsOf(section: Node): List<Node> = generateSequence(section.firstChild) { it.next }.toList()

    private fun capCells(row: Node, inline: InlineCut) {
        generateSequence(row.firstChild) { it.next }.forEach { capCellText(it, inline) }
    }

    /**
     * Keeps the first [MAX_CELL_CHARS] characters of [cell]'s text and drops the rest, then adds a "…".
     * The text node the limit falls in is shortened where [LongLineCut.pieceEnd] says, and everything
     * after it in the cell goes, so a cut inside bold leaves the bold and puts the "…" after it. A
     * formula or emoji the limit falls in cannot be shortened, so it goes too.
     */
    private fun capCellText(cell: Node, inline: InlineCut) {
        val (cut, left) = textOverCap(cell, inline) ?: return
        var node: Node = cut
        while (node !== cell) {
            while (node.next != null) node.next.unlink()
            node = node.parent
        }
        when (cut) {
            is Text -> cut.literal = kept(cut.literal, left)
            is Code -> cut.literal = kept(cut.literal, left)
            else -> cut.unlink()
        }
        cell.appendChild(CellEllipsis())
    }

    /**
     * The first Text, Code, formula or emoji node of [cell] the cap falls in, and how many of its chars
     * still fit. A formula or emoji counts as its source, as in [InlineCut.chars].
     */
    private fun textOverCap(cell: Node, inline: InlineCut): Pair<Node, Int>? {
        var left = MAX_CELL_CHARS
        var over: Node? = null
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null && over == null) {
                val length = when (child) {
                    is Text, is Code -> inline.chars(child)
                    else -> inline.sourceLength(child)
                }
                when {
                    length == null -> walk(child)
                    length > left -> over = child
                    else -> left -= length
                }
                child = child.next
            }
        }
        walk(cell)
        return over?.let { it to left }
    }

    private fun kept(literal: String, chars: Int): String =
        if (chars == 0) "" else literal.substring(0, LongLineCut.pieceEnd(literal, 0, literal.length, chars))

    private fun capColumns(row: Node) {
        var cell = row.firstChild
        var kept = 0
        while (cell != null && kept < MAX_COLUMNS) {
            cell = cell.next
            kept++
        }
        while (cell != null) {
            val next = cell.next
            cell.unlink()
            cell = next
        }
    }
}
