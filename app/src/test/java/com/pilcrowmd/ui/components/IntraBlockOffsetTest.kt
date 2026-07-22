// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import com.pilcrowmd.rendering.matchOffsetsOutside
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Arithmetic that lands a focused search match's *line* in view after its block has been
 * scrolled to the viewport top. The line's distance from the viewport top is
 * `textViewTopInItem + paddingTop + lineTop`; we subtract a small breathing-room margin and never
 * scroll up (clamp at 0, so a match on the block's first line stays put).
 */
class IntraBlockOffsetTest {

    @Test
    fun firstLineNoMargin_isZero() {
        assertEquals(0, intraBlockScrollDelta(lineTop = 0, textViewTopInItem = 0, paddingTop = 0, topMargin = 0))
    }

    @Test
    fun deepLine_scrollsDownByComposedOffset() {
        // line 300px into the text, 16px top padding, 0 row offset, 48px margin → 268
        assertEquals(268, intraBlockScrollDelta(lineTop = 300, textViewTopInItem = 0, paddingTop = 16, topMargin = 48))
    }

    @Test
    fun nestedTextView_accountsForRowOffset() {
        // e.g. a code block whose TextView sits 24px below the row top
        assertEquals(324, intraBlockScrollDelta(lineTop = 300, textViewTopInItem = 24, paddingTop = 48, topMargin = 48))
    }

    @Test
    fun marginExceedingOffset_clampsToZero() {
        assertEquals(0, intraBlockScrollDelta(lineTop = 10, textViewTopInItem = 0, paddingTop = 0, topMargin = 64))
    }

    // matchOffsetsOutside: every case-insensitive query offset NOT inside an excluded (LaTeX-math)
    // range. The highlighter + scroll use this so math content is never a match.
    @Test
    fun matchOffsets_allWhenNothingExcluded() {
        assertEquals(listOf(0, 5, 10), matchOffsetsOutside("line line line", "line", emptyList()))
    }

    @Test
    fun matchOffsets_caseInsensitive() {
        // "Line and LINE": "Line"@0, "LINE"@9
        assertEquals(listOf(0, 9), matchOffsetsOutside("Line and LINE", "line", emptyList()))
    }

    @Test
    fun matchOffsets_skipsExcludedRanges() {
        // The middle "line" (offset 5..8) is inside a math span → dropped.
        assertEquals(listOf(0, 10), matchOffsetsOutside("line line line", "line", listOf(5 until 9)))
    }

    @Test
    fun matchOffsets_emptyWhenNoneOrBlankQuery() {
        assertEquals(emptyList<Int>(), matchOffsetsOutside("nothing here", "line", emptyList()))
        assertEquals(emptyList<Int>(), matchOffsetsOutside("abc", "", emptyList()))
    }

    @Test
    fun matchOffsets_excludesMatchOverlappingExcludedRange() {
        // "abcXYZ" with math covering XYZ (3 until 6): "cXY" straddles plain→math → excluded; "ab" kept.
        assertEquals(emptyList<Int>(), matchOffsetsOutside("abcXYZ", "cXY", listOf(3 until 6)))
        assertEquals(listOf(0), matchOffsetsOutside("abcXYZ", "ab", listOf(3 until 6)))
    }

    // locateFocusedMatch: a block can render to several TextViews (a table's cells). The focused
    // occurrence is a BLOCK-WIDE ordinal threaded row-major across them, so scroll-to-match must find
    // WHICH TextView holds it (and its index within that view) before measuring geometry — otherwise it
    // always measures the first matching cell and overshoots a match in a lower row (Defect 3).

    @Test
    fun locateFocusedMatch_firstOccurrenceInFirstView() {
        assertEquals(FocusedMatchLocation(0, 0), locateFocusedMatch(listOf(1, 0, 1, 1), 0))
    }

    @Test
    fun locateFocusedMatch_skipsEmptyViews() {
        // counts [1,0,1,1]: occ 1 lives in view index 2 (view 1 has none), at within-index 0.
        assertEquals(FocusedMatchLocation(2, 0), locateFocusedMatch(listOf(1, 0, 1, 1), 1))
        assertEquals(FocusedMatchLocation(3, 0), locateFocusedMatch(listOf(1, 0, 1, 1), 2))
    }

    @Test
    fun locateFocusedMatch_withinViewOffset() {
        // A single view holding two matches: occ 1 is the 2nd within that same view.
        assertEquals(FocusedMatchLocation(0, 1), locateFocusedMatch(listOf(2), 1))
    }

    @Test
    fun locateFocusedMatch_outOfRange_returnsNull() {
        assertEquals(null, locateFocusedMatch(listOf(1, 1), 2))
        assertEquals(null, locateFocusedMatch(emptyList(), 0))
        assertEquals(null, locateFocusedMatch(listOf(1), -1))
    }
}
