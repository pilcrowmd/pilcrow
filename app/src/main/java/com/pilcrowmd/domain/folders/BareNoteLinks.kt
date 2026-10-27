// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/**
 * A K13/K14 candidate in plain text: [start] inclusive, [end] exclusive. [destination] is the target
 * alone, any K16 title already dropped, ready for [NoteLinkResolver.resolve].
 */
data class BareLink(val start: Int, val end: Int, val label: String, val destination: String)

/**
 * M-160: the note links strict Markdown leaves as plain text (notes-folder spec K13, K14).
 *
 * Only finds candidates. One becomes a link ONLY when [NoteLinkResolver] returns a Note for it;
 * otherwise it stays plain text. The file is never changed.
 */
object BareNoteLinks {

    /** K16: a quoted title after spaces or tabs, dropped from the destination. */
    private const val TITLE = "(?:[ \\t]+(?:\"[^\"\\n]*\"|'[^'\\n]*'))?"

    /**
     * K13 `[label](target)`: target starts with a non-space, ends in .md/.markdown, may hold raw spaces
     * and a quoted title, no newline, no parentheses, no brackets in the label. K14 `<name.md>`: starts
     * with a non-space, no newline, `<` or `>` inside. Neither part may start on whitespace, so no two
     * runs of the pattern compete for the same blanks (no quadratic backtracking).
     */
    private val CANDIDATE = Regex(
        "\\[([^\\[\\]\\n]*)]\\(([^()\\s][^()\\n]*?\\.(?:md|markdown))$TITLE[ \\t]*\\)" +
            "|<([^<>\\s][^<>\\n]*?\\.(?:md|markdown))>",
        RegexOption.IGNORE_CASE,
    )

    /** Every candidate in [text], non-overlapping, left to right. */
    fun find(text: String): List<BareLink> = CANDIDATE.findAll(text).map { match ->
        // An unmatched alternative's groups read as "", and a K14 name always ends in ".md" or ".markdown".
        val (label, target, bareName) = match.destructured
        if (bareName.isNotEmpty()) {
            BareLink(match.range.first, match.range.last + 1, bareName, bareName)
        } else {
            BareLink(match.range.first, match.range.last + 1, label, target)
        }
    }.toList()
}
