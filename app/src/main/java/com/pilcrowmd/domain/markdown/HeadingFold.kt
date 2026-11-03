// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.model.HeadingNode

/**
 * Folding in the Contents panel (S8-A49), a port of the Mac app's `OutlineCollapse`. A heading is
 * named by its index in the list. Only the panel folds; the reading text never does.
 */
object HeadingFold {
    /** The headings that have sub-headings: the only rows with an arrow, the only ones that fold. */
    fun foldable(headings: List<HeadingNode>): Set<Int> =
        headings.indices.filter { i -> i + 1 < headings.size && headings[i + 1].level > headings[i].level }.toSet()

    /**
     * The indices still shown. A folded heading hides every heading below it at a deeper level, up
     * to the next heading at its own level or above; the folded row itself stays.
     */
    fun visible(headings: List<HeadingNode>, folded: Set<Int>): List<Int> {
        val shown = ArrayList<Int>(headings.size)
        // The headings above the current one that contain it, outermost first.
        val parents = ArrayDeque<Int>()
        headings.forEachIndexed { i, heading ->
            while (parents.isNotEmpty() && headings[parents.last()].level >= heading.level) parents.removeLast()
            if (parents.none { it in folded }) shown += i
            parents.addLast(i)
        }
        return shown
    }
}
