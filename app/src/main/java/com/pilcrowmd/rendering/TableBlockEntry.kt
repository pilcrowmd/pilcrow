// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.FootnoteReference
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.ext.gfm.tables.TableRow as MdTableRow

/**
 * MarkwonAdapter.Entry for GFM tables. Markwon's default TableRowSpan fits the table to the
 * TextView width and wraps every cell — it cannot produce a wide table that scrolls. So we render
 * the table as a native Android TableLayout instead: each cell is a TextView sized to its content,
 * columns auto-align, and the whole TableLayout sits in a HorizontalScrollView.
 *
 * Cell inline content (bold, inline code, links) is rendered through the shared Markwon instance;
 * if that fails for a cell, it falls back to the cell's plain text. try/catch guards the whole
 * bind so a malformed table never crashes the adapter (Safeguard 3).
 */
class TableBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
) : MarkwonAdapter.Entry<TableBlock, TableBlockEntry.Holder>() {

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val root = inflater.inflate(R.layout.adapter_table_block, parent, false)
        // root IS the HorizontalScrollView — let a wide table pan sideways inside the RecyclerView.
        root.enableHorizontalNestedScroll()
        return Holder(root)
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: TableBlock) {
        val table = holder.table
        table.removeAllViews()
        try {
            // Pass 1: build every cell as a TextView, grouped into rows (header flag per row).
            val rows = mutableListOf<RowCells>()
            var section: Node? = node.firstChild
            while (section != null) {
                val header = section is TableHead
                if (section is TableHead || section is TableBody) {
                    var rowNode: Node? = section.firstChild
                    while (rowNode != null) {
                        if (rowNode is MdTableRow) {
                            val cells = mutableListOf<TextView>()
                            var cellNode: Node? = rowNode.firstChild
                            while (cellNode != null) {
                                if (cellNode is TableCell) cells.add(buildCell(markwon, cellNode, header))
                                cellNode = cellNode.next
                            }
                            rows.add(RowCells(cells))
                        }
                        rowNode = rowNode.next
                    }
                }
                section = section.next
            }
            if (rows.isEmpty()) return
            highlightSearchMatches(rows, holder.bindingAdapterPosition)
            // Pass 2: measure each cell (respects per-cell maxWidth) and take each column's widest.
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val columnCount = rows.maxOf { it.cells.size }
            val columnWidths = IntArray(columnCount)
            for (r in rows) {
                r.cells.forEachIndexed { c, tv ->
                    tv.measure(unspecified, unspecified)
                    if (tv.measuredWidth > columnWidths[c]) columnWidths[c] = tv.measuredWidth
                }
            }

            layoutRows(table, rows, columnWidths)
        } catch (e: Exception) {
            // Graceful-ignore: never crash on a malformed table (Safeguard 3).
            Log.e("TableBlockEntry", "render failed: ${e.message}", e)
            table.removeAllViews()
            table.addView(
                TextView(context).apply {
                    text = "[table could not be rendered]"
                    setTextColor(colorScheme.secondaryText.toArgb())
                },
            )
        }
    }

    /**
     * Pass 3: lay out rows as horizontal LinearLayouts with explicit per-column widths, so the
     * table's total width is the sum of its content columns (wider than the viewport ⇒ the
     * HScrollView scrolls). MATCH_PARENT cell height makes every cell fill the row so the borders
     * line up across columns of differing line counts.
     */
    private fun layoutRows(table: LinearLayout, rows: List<RowCells>, columnWidths: IntArray) {
        for (r in rows) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r.cells.forEachIndexed { c, tv ->
                row.addView(tv, LinearLayout.LayoutParams(columnWidths[c], LinearLayout.LayoutParams.MATCH_PARENT))
            }
            table.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * Highlight search matches in every cell. Walking row-major (header row, then body rows
     * top-to-bottom, cells left-to-right) matches the source order the search use case counts
     * occurrences in, so the threaded occurrence base lands the focused colour on the right cell
     * when several matches share this table block. BackgroundColorSpans don't change metrics,
     * so this runs before the measure pass.
     */
    private fun highlightSearchMatches(rows: List<RowCells>, blockPosition: Int) {
        val blockFocused = blockPosition == searchHighlight.focusedPosition
        var occurrenceBase = 0
        for (r in rows) {
            for (tv in r.cells) {
                occurrenceBase += SearchHighlighter.highlight(tv, searchHighlight, blockFocused, occurrenceBase)
            }
        }
    }

    private class RowCells(val cells: List<TextView>)

    private fun buildCell(markwon: Markwon, cell: TableCell, header: Boolean): TextView {
        val serif = ResourcesCompat.getFont(context, fontSet.readingRegular)
        return TextView(context).apply {
            text = renderCell(markwon, cell)
            setTextColor(colorScheme.primaryText.toArgb())
            // Apply fontScale multiplier to table cell text (15sp base, body-like)
            val scaledTextSize = PilcrowTypography.TABLE_FONT_SIZE_SP * fontScale
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledTextSize)
            setTypeface(serif, if (header) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            gravity = when (cell.alignment) {
                TableCell.Alignment.CENTER -> Gravity.CENTER
                TableCell.Alignment.RIGHT -> Gravity.END
                else -> Gravity.START
            }
            // Very wide cells (e.g. a "Notes" column) wrap instead of bloating the table.
            maxWidth = dp(240f)
            background = GradientDrawable().apply {
                setColor(
                    (
                        if (header) {
                            colorScheme.secondarySurface
                        } else {
                            colorScheme.primaryBackground
                        }
                        ).toArgb(),
                )
                setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
            }
        }
    }

    /** Inline-rendered cell content, falling back to plain text if Markwon can't render it. */
    private fun renderCell(markwon: Markwon, cell: TableCell): CharSequence = try {
        val spanned = markwon.render(cell)
        // Same accent treatment a marker gets in prose — a footnote in a table cell must not read
        // as a different thing from the identical footnote one paragraph above it.
        if (spanned.isNullOrEmpty()) plainText(cell) else tintFootnoteMarkers(spanned, colorScheme.accent.toArgb())
    } catch (e: Exception) {
        plainText(cell)
    }

    /**
     * The cell's visible text, used when Markwon renders nothing for it.
     *
     * This walk mirrors `SearchMarkdownUseCase.appendVisible`, and must keep mirroring it. A node
     * whose text lives in a PROPERTY rather than in a [Text] child has to contribute that text here,
     * or two things break at once: the author's content is silently DELETED from the page
     * (Safeguard 1 — the same reason an unreferenced definition still renders), and search, which
     * models this exact string, targets offsets the cell never painted.
     *
     * Recursing in the `else` branch is what keeps it non-lossy by default: an unknown container
     * still yields its children instead of disappearing. Only a node that genuinely paints nothing —
     * an image's alt text — is dropped on purpose, matching `appendVisible`'s early return.
     */
    private fun plainText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node?) {
            var c = n
            while (c != null) {
                when (c) {
                    is Text -> sb.append(c.literal)
                    is Code -> sb.append(c.literal)
                    is HtmlInline -> sb.append(c.literal)
                    // A resolved marker paints its ordinal — the string search models for it.
                    is FootnoteReference -> sb.append(c.ordinal)
                    is SoftLineBreak -> sb.append(' ')
                    is HardLineBreak -> sb.append('\n')
                    is Image -> Unit
                    else -> walk(c.firstChild)
                }
                c = c.next
            }
        }
        walk(node.firstChild)
        return sb.toString()
    }

    /** ViewHolder holding the vertical LinearLayout (rows) inside the HorizontalScrollView. */
    class Holder(itemView: View) : MarkwonAdapter.Holder(itemView) {
        val table: LinearLayout = requireView(R.id.table_layout)
    }
}
