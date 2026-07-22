// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView

/**
 * Search highlighting shared by every block entry so a match is highlighted no matter which
 * block type renders it — prose, table cells, code, or yaml (the old logic lived only in
 * ProseBlockEntry, so matches inside a table/code block were navigated-to but never painted).
 *
 * Layers [BackgroundColorSpan]s over the already-rendered Spanned (Markwon's own spans are
 * preserved). The focused block's focused occurrence is drawn in `focusedColor`; every other match —
 * including siblings sharing the block — uses `otherColor`, so next/prev visibly moves the focus
 * even when several matches share one block. Colors come from the token layer (Safeguard 4).
 * Wrapped so a bad range can never break rendering (Safeguard 3).
 */
object SearchHighlighter {

    /**
     * Highlight every occurrence of [highlight].query in [tv].
     *
     * @param blockIsFocused this TextView's block holds the focused match
     * @param occurrenceBase how many matches in this block precede this TextView — 0 for a
     *   single-TextView block; a running total threaded across a table's cells (row-major = source
     *   order) so the per-block occurrence ordinal stays continuous and the focused cell lights up.
     * @return the number of matches found in [tv].
     */
    fun highlight(tv: TextView, highlight: SearchHighlight, blockIsFocused: Boolean, occurrenceBase: Int): Int {
        val query = highlight.query
        if (query.isBlank()) return 0
        return try {
            val current = tv.text
            val spannable = if (current is SpannableString) current else SpannableString(current)
            // Skip matches inside a LaTeX math span (searchableMatchOffsets) so the highlighter's
            // occurrence ordinals stay consistent with the search use case (math is not a match).
            val offsets = searchableMatchOffsets(spannable, query)
            offsets.forEachIndexed { i, idx ->
                val occurrence = occurrenceBase + i
                val color = if (blockIsFocused && occurrence == highlight.focusedOccurrence) {
                    highlight.focusedColor
                } else {
                    highlight.otherColor
                }
                spannable.setSpan(
                    BackgroundColorSpan(color),
                    idx,
                    idx + query.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            tv.text = spannable
            offsets.size
        } catch (_: Exception) {
            // Never let highlighting break rendering.
            0
        }
    }
}
