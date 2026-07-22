// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan

/**
 * Start offsets of every case-insensitive [query] match in [text] that is NOT inside a LaTeX math
 * span. Markwon paints inline/display math as a `JLatexAsyncDrawableSpan` over the formula's latex
 * *source* placeholder text — so a naive scan would "match" inside a formula, where the highlight is
 * hidden under the image and the focus/scroll ordinals would diverge from the search use case (which
 * excludes math). Skipping latex-span regions here keeps the highlighter and scroll consistent with
 * search: math content is never a match (inline math renders as an image, not text).
 */
fun searchableMatchOffsets(text: CharSequence, query: String): List<Int> {
    val excluded = if (text is Spanned) {
        text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)
            .map { text.getSpanStart(it) until text.getSpanEnd(it) }
    } else {
        emptyList()
    }
    return matchOffsetsOutside(text.toString(), query, excluded)
}

/**
 * Pure core: start offsets of every case-insensitive [query] match in [text] whose start is not in
 * any [excluded] range (step by 1 so self-overlapping queries are all found — parity with the search
 * use case). Extracted so the offset logic is unit-testable without building spanned text.
 */
fun matchOffsetsOutside(text: String, query: String, excluded: List<IntRange>): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = mutableListOf<Int>()
    var from = 0
    while (true) {
        val idx = text.indexOf(query, from, ignoreCase = true)
        if (idx < 0) break
        // Exclude a match that OVERLAPS a math span at all (not only one that starts inside it) — a
        // query straddling plain text into a formula must not highlight a half-covered, broken span.
        val matchEnd = idx + query.length - 1
        if (excluded.none { idx <= it.last && matchEnd >= it.first }) out.add(idx)
        from = idx + 1
    }
    return out
}
