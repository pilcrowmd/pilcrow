// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/**
 * Pure (no-Android) LaTeX `$` delimiter rules, shared by the renderer's inline processor and the
 * search use case so both agree on exactly which source spans are math.
 *
 * Single-`$` disambiguation (KaTeX/remark-math, tuned to keep `$`-prices literal): an opener `$` is
 * only an opener if the next char is not `$`, whitespace, or a digit; the first *unescaped* `$` after
 * it is the only closer candidate, valid only if it is not preceded by whitespace, not followed by a
 * digit (kills `$US$5`), and not part of a `$$`; content is non-empty and single-line. `\$` is an
 * escaped literal dollar, never a delimiter.
 */
object InlineMathDelimiters {

    /**
     * Index of the closing `$` for a valid single-`$` formula opening at [openerIndex] in [s], or
     * null if the `$` there does not open inline math. [openerIndex] must point at a `$`.
     */
    fun singleDollarCloser(s: String, openerIndex: Int): Int? {
        if (!opensMath(s, openerIndex)) return null
        var i = openerIndex + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\n') return null // single-line only
            if (c == '$' && !isEscaped(s, i)) return validateCloser(s, i)
            i++
        }
        return null // no closer on this line
    }

    /** A `$` at [openerIndex] opens math only if the next char is not `$`, whitespace, or a digit. */
    private fun opensMath(s: String, openerIndex: Int): Boolean {
        if (openerIndex + 1 >= s.length) return false
        val after = s[openerIndex + 1]
        val blockedAfter = after == ' ' || after == '\t' || after.isDigit()
        return after != '$' && !blockedAfter
    }

    /** The first unescaped `$` after the opener closes math only if it passes the closer rules. */
    private fun validateCloser(s: String, closerIndex: Int): Int? {
        val prev = s[closerIndex - 1]
        val next = if (closerIndex + 1 < s.length) s[closerIndex + 1] else ' '
        val precededBySpace = prev == ' ' || prev == '\t'
        return if (precededBySpace || next.isDigit() || next == '$') null else closerIndex
    }

    /**
     * All raw-source character ranges (inclusive) that render as LaTeX math: display `$$…$$` and
     * inline `$…$`. Used by search to exclude math from searchable text (it renders as a formula
     * image, not text). Escaped `\$` is never a delimiter. Ranges are non-overlapping, left-to-right.
     */
    fun mathRanges(content: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < content.length) {
            val range = mathAt(content, i)
            if (range != null) {
                ranges.add(range)
                i = range.last + 1
            } else {
                i++
            }
        }
        return ranges
    }

    /** The math range that STARTS at [i] (display `$$…$$` or inline `$…$`), or null if none does. */
    private fun mathAt(content: String, i: Int): IntRange? {
        if (content[i] != '$' || isEscaped(content, i)) return null
        if (i + 1 < content.length && content[i + 1] == '$') {
            // Display `$$…$$`: close at the next `$$` (mirrors ext-latex's (\${2})([\s\S]+?)\1).
            val close = content.indexOf("$$", i + 2)
            return if (close >= 0) i..(close + 1) else null
        }
        return singleDollarCloser(content, i)?.let { i..it }
    }

    /** A `$` is escaped iff preceded by an odd run of backslashes. */
    private fun isEscaped(s: String, index: Int): Boolean {
        var backslashes = 0
        var j = index - 1
        while (j >= 0 && s[j] == '\\') {
            backslashes++
            j--
        }
        return backslashes % 2 == 1
    }
}
