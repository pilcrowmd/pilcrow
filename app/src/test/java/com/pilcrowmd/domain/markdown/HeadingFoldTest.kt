// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.model.HeadingNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S8-A49: the Mac `OutlineCollapse` cases (Phase72dOutlineCollapseTests), plus skipped levels. */
class HeadingFoldTest {
    private fun headings(vararg levels: Int) = levels.mapIndexed { i, level -> HeadingNode(level, "H$level-$i", i) }

    @Test
    fun onlyHeadingsWithSubHeadingsFold() {
        // H1 > H2a > H3, H2b (leaf)
        assertEquals(setOf(0, 1), HeadingFold.foldable(headings(1, 2, 3, 2)))
        assertEquals(setOf(1), HeadingFold.foldable(headings(1, 1, 2, 2, 2)))
        assertTrue(HeadingFold.foldable(headings(1, 1, 1)).isEmpty())
        assertTrue(HeadingFold.foldable(emptyList()).isEmpty())
    }

    @Test
    fun foldingOneH2HidesItsH3sAndLeavesTheOtherH2Open() {
        // H1 > (H2a > H3a1, H3a2), H2b > H3b
        assertEquals(listOf(0, 1, 4, 5), HeadingFold.visible(headings(1, 2, 3, 3, 2, 3), folded = setOf(1)))
    }

    @Test
    fun aFoldedH1HidesEverythingUntilTheNextH1() {
        assertEquals(listOf(0, 3, 4), HeadingFold.visible(headings(1, 2, 3, 1, 2), folded = setOf(0)))
    }

    @Test
    fun foldingHidesEveryDeeperLevelNotOnlyTheNextOne() {
        // H1 > H2 > H3 > H4, then H2: folding the H2 hides the H3 and the H4 below it.
        assertEquals(listOf(0, 1, 4), HeadingFold.visible(headings(1, 2, 3, 4, 2), folded = setOf(1)))
    }

    @Test
    fun skippedLevelsCountAsSubHeadings() {
        // H1 then H3, then H2: the H3 is under the H1 even though no H2 sits between them.
        val outline = headings(1, 3, 2, 3)
        assertEquals(setOf(0, 2), HeadingFold.foldable(outline))
        assertEquals(listOf(0), HeadingFold.visible(outline, folded = setOf(0)))
        // An H3 then an H2: the H2 is not under the H3, so folding the H3 hides nothing.
        assertEquals(listOf(0, 1, 2), HeadingFold.visible(headings(3, 2, 3), folded = setOf(0)))
    }

    @Test
    fun theFoldedRowStaysAndAnInnerFoldUnderAFoldedRowStaysHidden() {
        val outline = headings(1, 2, 3, 2)
        // Both folded: only the H1. Unfold the H1: the H2 stays folded, its H3 still hidden.
        assertEquals(listOf(0), HeadingFold.visible(outline, folded = setOf(0, 1)))
        assertEquals(listOf(0, 1, 3), HeadingFold.visible(outline, folded = setOf(1)))
    }

    @Test
    fun nothingFoldedShowsEverything() {
        val outline = headings(1, 2, 3, 3, 2)
        assertEquals(outline.indices.toList(), HeadingFold.visible(outline, folded = emptySet()))
    }

    @Test
    fun collapseAllLeavesTheTopLevelAndExpandAllShowsEverything() {
        val outline = headings(1, 2, 3, 2, 3)
        assertEquals(listOf(0), HeadingFold.visible(outline, folded = HeadingFold.foldable(outline)))
        assertEquals(outline.indices.toList(), HeadingFold.visible(outline, folded = emptySet()))
        // Two top-level headings: collapse all leaves both.
        val two = headings(2, 3, 1, 2)
        assertEquals(listOf(0, 2), HeadingFold.visible(two, folded = HeadingFold.foldable(two)))
    }
}
