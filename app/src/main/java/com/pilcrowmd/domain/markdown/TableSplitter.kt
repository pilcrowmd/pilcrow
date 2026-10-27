// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.node.Node

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
 */
object TableSplitter {
    /** Body rows a piece may hold. */
    const val PIECE_ROWS = 200

    /** Body cells a piece may hold: a wide table gets fewer rows per piece, never fewer than one. */
    const val PIECE_CELLS = 3_000

    /** Columns drawn of a table that has to be cut; the ones past it are not (see the class doc). */
    const val MAX_COLUMNS = 200

    /** Cut every long top-level table of [document] in place. */
    fun split(document: Node) {
        var block = document.firstChild
        while (block != null) {
            val next = block.next
            if (block is TableBlock && block !is TablePiece) splitTable(block)
            block = next
        }
    }

    private fun splitTable(table: TableBlock) {
        val head = table.firstChild as? TableHead ?: return
        val body = head.next as? TableBody
        val headRow = head.firstChild
        val rows = body?.let(::rowsOf).orEmpty()
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
