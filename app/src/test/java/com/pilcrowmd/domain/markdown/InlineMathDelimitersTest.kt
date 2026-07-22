// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the shared `$` delimiter rules (renderer + search both depend on these). */
class InlineMathDelimitersTest {

    // --- singleDollarCloser ---

    @Test fun simpleClosesAtSecondDollar() {
        // "$x$" → opener 0, closer 2
        assertEquals(2, InlineMathDelimiters.singleDollarCloser("\$x\$", 0))
    }

    @Test fun openerFollowedByDigitDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$5", 0))
    }

    @Test fun openerFollowedBySpaceDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$ x\$", 0))
    }

    @Test fun closerFollowedByDigitDeclines() {
        // "$US$5" → first $ at 0 opens, closer candidate at 3 is followed by '5' → not a closer
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$US\$5", 0))
    }

    @Test fun closerPrecededBySpaceDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$x \$", 0))
    }

    @Test fun doubleDollarDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$\$x\$\$", 0))
    }

    @Test fun newlineAborts() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$a\nb\$", 0))
    }

    @Test fun escapedInnerDollarIsSkipped() {
        // "$a\$b$" → inner \$ (escaped) is not the closer; the final $ at index 5 is
        assertEquals(5, InlineMathDelimiters.singleDollarCloser("\$a\\\$b\$", 0))
    }

    // --- mathRanges ---

    @Test fun rangesForInlineAndDisplay() {
        // "x $a$ y $$b$$ z": inline $a$ at 2..4, display $$b$$ at 8..12
        val ranges = InlineMathDelimiters.mathRanges("x \$a\$ y \$\$b\$\$ z")
        assertEquals(listOf(2..4, 8..12), ranges)
    }

    @Test fun rangesExcludeCurrency() {
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("buy at \$5 and \$10 now"))
    }

    @Test fun rangesExcludeEscaped() {
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("pay \\\$x\\\$ literal"))
    }

    @Test fun unterminatedDisplayIsNotMath() {
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("start \$\$ never closes"))
    }
}
