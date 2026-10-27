// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.drawnRows
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Node

/**
 * The table [TableBlockEntry] shows when binding it fails, in an Exception or in an `OutOfMemoryError`
 * (an Error, which the Exception catch does not see, and which ends the process from the main thread):
 * the failure line, then the first rows as tab-separated text, capped at [MAX_CHARS] so it is one small
 * TextView however large the table, so the content stays readable. If even that cannot be built, the
 * failure line stands alone.
 */
internal class TableFallback(
    private val context: Context,
    private val colorScheme: PilcrowColorScheme,
    private val fontScale: Float,
) {
    /** Replace whatever [table] holds with the degraded view of [node]; [cellText] is a cell's plain text. */
    fun show(table: LinearLayout, node: TableBlock, cellText: (Node) -> String) {
        table.removeAllViews()
        val failed = context.getString(R.string.render_table_failed)
        val rowsText = try {
            tabSeparatedRows(node, cellText)
        } catch (e: OutOfMemoryError) {
            Log.e("TableBlockEntry", "no memory for the table as text: ${e.message}", e)
            ""
        }
        val body = if (rowsText.isEmpty()) failed else failed + BLANK_LINE + rowsText
        table.addView(
            TextView(context).apply {
                text = body
                setTextColor(colorScheme.secondaryText.toArgb())
                // The degraded path takes the font scale like every other block. Without this it
                // renders at the theme default, so at any zoom other than 100% a failed table
                // sits at a visibly different size from the document around it — and during a
                // pinch it is scaled and then snaps back when the rebuild re-creates it.
                setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    PilcrowTypography.TABLE_FONT_SIZE_SP * fontScale,
                )
            },
        )
    }

    /** Rows of cell text joined by tabs, one row per line, stopping at the first row past [MAX_CHARS]. */
    private fun tabSeparatedRows(node: TableBlock, cellText: (Node) -> String): String {
        val sb = StringBuilder()
        for (drawn in node.drawnRows()) {
            if (sb.length >= MAX_CHARS) break
            val cells = generateSequence(drawn.row.firstChild) { it.next }.filterIsInstance<TableCell>()
            sb.append(cells.joinToString("\t") { cellText(it) }).append('\n')
        }
        return sb.toString()
    }

    private companion object {
        const val BLANK_LINE = "\n\n"

        /** Characters of the table as text. */
        const val MAX_CHARS = 10_000
    }
}
