// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.rendering.searchableMatchOffsets
import com.pilcrowmd.storage.ScrollAnchor

/**
 * How far to scroll *down* so a focused search match's line is in view, once its block has
 * been placed at the viewport top via `scrollToPositionWithOffset(blockPos, 0)`.
 *
 * The match line's distance from the viewport top is the sum of:
 *  - [textViewTopInItem] — the matched TextView's top relative to the viewport (0 when the row is
 *    itself the TextView; positive for a TextView nested in a code-block row),
 *  - [paddingTop] — the TextView's top padding (where its text starts), and
 *  - [lineTop] — `Layout.getLineTop(line)` for the match's line.
 *
 * We subtract a small [topMargin] for breathing room and clamp at 0, so a match on the block's
 * first line never scrolls the block back up.
 */
fun intraBlockScrollDelta(lineTop: Int, textViewTopInItem: Int, paddingTop: Int, topMargin: Int): Int =
    (textViewTopInItem + paddingTop + lineTop - topMargin).coerceAtLeast(0)

/**
 * The current reading position as a (first-visible-item index, that item's top offset)
 * anchor. Offset is negative when the first visible block is partially scrolled above the fold.
 */
fun RecyclerView.currentScrollAnchor(): ScrollAnchor {
    val lm = layoutManager as? LinearLayoutManager ?: return ScrollAnchor()
    val index = lm.findFirstVisibleItemPosition()
    if (index == RecyclerView.NO_POSITION) return ScrollAnchor()
    val top = lm.findViewByPosition(index)?.top ?: 0
    return ScrollAnchor(index, top - paddingTop)
}

/**
 * Which rendered TextView of a block holds the focused match, and that match's index within it.
 * A block can render to several TextViews (a table's cells); [textViewIndex] is into the block's
 * TextViews in document order, [withinTextViewOccurrence] is the 0-based ordinal inside that view.
 */
data class FocusedMatchLocation(val textViewIndex: Int, val withinTextViewOccurrence: Int)

/**
 * Resolve a block-wide [focusedOccurrence] ordinal against per-TextView match [occurrenceCounts]
 * (document order). Returns which TextView holds it and the within-view index, or null when the
 * ordinal is out of range — so a table match in a lower row is measured against its OWN cell rather
 * than the first matching cell (which overshot, Defect 3). Pure arithmetic; unit-tested.
 */
fun locateFocusedMatch(occurrenceCounts: List<Int>, focusedOccurrence: Int): FocusedMatchLocation? {
    if (focusedOccurrence < 0) return null
    var base = 0
    for (i in occurrenceCounts.indices) {
        val count = occurrenceCounts[i]
        if (focusedOccurrence < base + count) return FocusedMatchLocation(i, focusedOccurrence - base)
        base += count
    }
    return null
}

/**
 * Pixel delta to bring the focused match's line into view within block [position]'s row,
 * assuming the row is already at the viewport top. [occurrenceInBlock] selects WHICH match in the
 * block to land on — threaded across the block's TextViews (e.g. a table's cells row-major), so a
 * match in a lower cell is measured against that cell, not the first matching one. Returns 0 when
 * nothing measurable is found (holder not laid out, ordinal out of range, or no layout yet).
 */
fun RecyclerView.intraBlockMatchDelta(position: Int, query: String, occurrenceInBlock: Int): Int {
    val (tv, charIdx) = focusedMatchTarget(position, query, occurrenceInBlock) ?: return 0
    val layout = tv.layout ?: return 0
    val lineTop = layout.getLineTop(layout.getLineForOffset(charIdx))
    val rvLoc = IntArray(2).also { getLocationInWindow(it) }
    val tvLoc = IntArray(2).also { tv.getLocationInWindow(it) }
    val tvTopInRv = tvLoc[1] - rvLoc[1] - paddingTop
    val margin = (BREATHING_ROOM_DP * resources.displayMetrics.density).toInt()
    return intraBlockScrollDelta(lineTop, tvTopInRv, tv.totalPaddingTop, margin)
}

/**
 * The TextView holding block [position]'s focused match and that match's char offset within it, or
 * null when nothing measurable is found (blank query, holder not laid out, or ordinal out of range).
 * Threads [occurrenceInBlock] across the block's TextViews so a table match resolves to its own cell.
 */
private fun RecyclerView.focusedMatchTarget(
    position: Int,
    query: String,
    occurrenceInBlock: Int,
): Pair<TextView, Int>? {
    if (query.isBlank()) return null
    val itemView = findViewHolderForAdapterPosition(position)?.itemView ?: return null
    val textViews = collectTextViews(itemView)
    // searchableMatchOffsets skips LaTeX-math spans, so the per-TextView counts and the chosen char
    // offset match the search use case + highlighter (math is not a match — consistent ordinals).
    val counts = textViews.map { searchableMatchOffsets(it.text, query).size }
    val location = locateFocusedMatch(counts, occurrenceInBlock) ?: return null
    val tv = textViews[location.textViewIndex]
    val charIdx = searchableMatchOffsets(tv.text, query).getOrNull(location.withinTextViewOccurrence)
    return if (charIdx == null) null else tv to charIdx
}

/** Every TextView under [view], depth-first in child order (row-major for a table's cells). */
private fun collectTextViews(view: View): List<TextView> {
    val out = mutableListOf<TextView>()
    fun walk(v: View) {
        if (v is TextView) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(view)
    return out
}

private const val BREATHING_ROOM_DP = 24f
