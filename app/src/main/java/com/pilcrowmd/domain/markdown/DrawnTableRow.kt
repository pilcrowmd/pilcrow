// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.node.Node
import org.commonmark.ext.gfm.tables.TableRow as MdTableRow

/**
 * One row of a table as the reader draws it: [row], whether it is a [header] row, and whether it is a
 * [repeated] header, the first piece's, drawn again above a later [TablePiece] (S8-A31). Search counts a
 * header once, so a repeated one is drawn but never highlighted.
 */
class DrawnTableRow(val row: MdTableRow, val header: Boolean, val repeated: Boolean)

/** The rows of this table in drawing order: header row, then body rows; a later piece starts with the repeat. */
fun TableBlock.drawnRows(): Sequence<DrawnTableRow> = sequence {
    val piece = this@drawnRows as? TablePiece
    if (piece != null && piece.repeatsHeader) {
        yieldAll(rowsOf(piece.head, header = true, repeated = true))
    }
    var section: Node? = firstChild
    while (section != null) {
        if (section is TableHead || section is TableBody) yieldAll(rowsOf(section, section is TableHead, false))
        section = section.next
    }
}

private fun rowsOf(section: Node, header: Boolean, repeated: Boolean): Sequence<DrawnTableRow> =
    generateSequence(section.firstChild) { it.next }
        .filterIsInstance<MdTableRow>()
        .map { DrawnTableRow(it, header, repeated) }
