// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
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
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.image.AsyncDrawableSpan
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
 * Cell inline content (bold, inline code, links, maths) is rendered through the shared Markwon
 * instance and set the way prose sets it (M-17, M-32; [TableCellRenderPlugin] makes a single cell
 * renderable). If that fails for a cell, it falls back to the cell's plain text. try/catch guards
 * the whole bind so a malformed table never crashes the adapter (Safeguard 3).
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
            // The cap protects the main thread. Off it (the PDF export binds on Dispatchers.IO) it
            // would only clip formulas in print, so every formula is built there.
            val onMainThread = Looper.myLooper() == Looper.getMainLooper()
            val mathBudget = MathBudget(if (onMainThread) MAX_SYNC_FORMULAS_PER_TABLE else Int.MAX_VALUE)
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
                                if (cellNode is TableCell) cells.add(buildCell(markwon, cellNode, header, mathBudget))
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

    /** How many more formulas this bind may still build on the main thread. */
    private class MathBudget(var remaining: Int)

    private fun buildCell(markwon: Markwon, cell: TableCell, header: Boolean, mathBudget: MathBudget): TextView {
        val serif = ResourcesCompat.getFont(context, fontSet.readingRegular)
        return TextView(context).apply {
            // As prose sets its text (ProseBlockEntry): through setParsedMarkdown, so a link gets its
            // movement method and the plugins' after-set hooks run, then the wider footnote hit area.
            markwon.setParsedMarkdown(this, renderCell(markwon, cell, mathBudget))
            enableGenerousFootnoteTaps()
            // That movement method consumes a drag, so a drag that starts on the cell would not pan a
            // wide table (the same cause as in code blocks). Only a cell with something to tap
            // keeps it. Clearing it also makes the cell not clickable or focusable again.
            val shown = text as? Spanned
            if (shown == null || shown.getSpans(0, shown.length, ClickableSpan::class.java).isEmpty()) {
                movementMethod = null
            }
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
    private fun renderCell(markwon: Markwon, cell: TableCell, mathBudget: MathBudget): Spanned = try {
        val spanned = markwon.render(cell)
        // Same accent treatment a marker gets in prose — a footnote in a table cell must not read
        // as a different thing from the identical footnote one paragraph above it.
        if (spanned.isNullOrEmpty()) {
            SpannableString(plainText(cell))
        } else {
            val cellText = imagesAsAltText(spanned)
            // M-32: the column is measured once and then fixed, so each formula is given its real
            // size now, at the size and colour the same formula has in a paragraph on screen.
            resolveCellMaths(cellText, mathBudget)
            tintFootnoteMarkers(cellText, colorScheme.footnoteMarker.toArgb())
        }
    } catch (e: Exception) {
        SpannableString(plainText(cell))
    }

    /**
     * Builds the cell's formulas now, on the main thread, while the table's budget lasts. Nothing is
     * cached between binds, so a table with very many formulas would stall every bind; past the
     * budget a cell's formulas are left to the plugin's background loader, as in a paragraph, and may
     * be clipped by a column measured before they arrived. A cell's formulas are resolved all or
     * none, so the budget is spent in reading order.
     */
    private fun resolveCellMaths(cellText: Spanned, mathBudget: MathBudget) {
        val pending = cellText.getSpans(0, cellText.length, JLatexAsyncDrawableSpan::class.java)
            .count { !it.drawable.hasResult() }
        if (pending == 0) return
        if (pending > mathBudget.remaining) {
            mathBudget.remaining = 0
            return
        }
        mathBudget.remaining -= pending
        resolveLatexSynchronously(
            cellText,
            PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * context.resources.displayMetrics.density * fontScale,
            colorScheme.primaryText.toArgb(),
        )
    }

    /**
     * M-250: a picture in a cell shows its alt text where a paragraph would show the picture. A
     * picture's size is known only once it has loaded, after the columns are measured and fixed, so
     * it could only be squeezed into a column as wide as its alt text. The image span and its
     * "Tap to show" target are removed, and the alt text stays as text
     * ([ImagePlaceholderDrawable.LABEL_NO_ALT] when there is none, the placeholder's word).
     * [ImageAltTextSpan] marks it so search highlighting skips it, as search itself does
     * (`SearchMarkdownUseCase.appendVisible`), so painted and searched counts agree.
     */
    private fun imagesAsAltText(text: Spanned): Spanned {
        val editable = text as? Editable ?: return text
        val images = editable.getSpans(0, editable.length, AsyncDrawableSpan::class.java)
            .filterNot { it is JLatexAsyncDrawableSpan }
        for (span in images) {
            val start = editable.getSpanStart(span)
            var end = editable.getSpanEnd(span)
            editable.removeSpan(span)
            if (editable.subSequence(start, end).toString() == OBJECT_REPLACEMENT) {
                editable.replace(start, end, ImagePlaceholderDrawable.LABEL_NO_ALT)
                end = start + ImagePlaceholderDrawable.LABEL_NO_ALT.length
            }
            editable.setSpan(ImageAltTextSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        editable.getSpans(0, editable.length, RelativeImageTapSpan::class.java).forEach(editable::removeSpan)
        return editable
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
     * still yields its children instead of disappearing. An image's alt text is dropped here, matching
     * `appendVisible`'s early return; the rendered path shows it but keeps it out of search
     * ([imagesAsAltText]).
     */
    @VisibleForTesting
    internal fun plainText(node: Node): String {
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

    private companion object {
        /** What the image visitor writes for an image with no alt text, for its span to draw over. */
        const val OBJECT_REPLACEMENT = "\uFFFC"

        /** Formulas per table built during a bind on the main thread (see [resolveCellMaths]). */
        const val MAX_SYNC_FORMULAS_PER_TABLE = 24
    }
}

/**
 * Marks an image's alt text shown in a table cell (M-250). Search does not count alt text, so the
 * highlighter skips it too (`searchableMatchOffsets`).
 */
class ImageAltTextSpan
