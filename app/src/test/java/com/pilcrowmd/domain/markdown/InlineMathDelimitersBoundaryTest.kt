// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The string-end and index-0 edges of the `$` rules. Written against mutants that survived
 * [InlineMathDelimitersTest] (mutation testing, hardening item (d)); each was seen to fail with its
 * mutant applied. Several of those mutants turn a bounds check into an index past the end, so an
 * edge that is not tested here is a crash in search and rendering (Safeguard 3), not a wrong answer.
 */
class InlineMathDelimitersBoundaryTest {

    @Test fun aDollarAtTheVeryEndOpensNothing() {
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("costs 5 \$"))
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("x \$", 2))
    }

    @Test fun anOpenerWithNoCloserBeforeTheEndDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$x", 0))
    }

    @Test fun openerFollowedByTabDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$\tx\$", 0))
    }

    @Test fun closerPrecededByTabDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$x\t\$", 0))
    }

    @Test fun closerFollowedByDollarDeclines() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$x\$\$", 0))
    }

    /** The closing `$` of one formula must not be re-read as the opener of the next. */
    @Test fun scanningResumesAfterTheClosingDollar() {
        assertEquals(listOf(0..2), InlineMathDelimiters.mathRanges("\$a\$b\$"))
    }

    /**
     * A digit after the opener blocks it even when a valid closer follows: currency then a later
     * `$`. The existing digit test has no closer at all, so it passed with the digit rule deleted,
     * and "$5 then a$ b" rendered as a formula.
     */
    @Test fun openerFollowedByDigitDeclinesEvenWithAValidCloserLater() {
        assertEquals(null, InlineMathDelimiters.singleDollarCloser("\$5x\$", 0))
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("pay \$5 then a\$ b"))
    }

    /** A backslash at the start escapes the `$` right after it. */
    @Test fun aBackslashAtTheStartEscapes() {
        assertEquals(emptyList<IntRange>(), InlineMathDelimiters.mathRanges("\\\$x\$"))
    }
}
