// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.DetailsSection

/**
 * Which `<details>` sections of the open document are expanded (M-161), by top-level block index —
 * the same index the adapter, search and the headings drawer use, which is why a section's body
 * stays as sibling blocks that are HIDDEN rather than removed: no position ever moves.
 *
 * Shared, mutable UI state in the same way as [SearchHighlight]: the reader creates one per
 * screen, the entries read it at bind time, and a tap or a navigation writes it. Not saved: every
 * open of a file starts from each section's own default (closed unless it says `open`).
 */
class DetailsState {

    private var content: String? = null
    var sections: List<DetailsSection> = emptyList()
        private set
    private val expanded = mutableSetOf<Int>()

    /**
     * Take the sections of a freshly parsed [content]. The same content keeps what the user opened —
     * a pinch-zoom or a theme change re-parses the same text — and new content starts over.
     */
    fun load(content: String, sections: List<DetailsSection>) {
        val sameDocument = content == this.content && sections == this.sections
        this.content = content
        this.sections = sections
        if (!sameDocument) {
            expanded.clear()
            sections.filter { it.openByDefault }.forEach { expanded += it.header }
        }
    }

    fun sectionAt(header: Int): DetailsSection? = sections.firstOrNull { it.header == header }

    fun isExpanded(header: Int): Boolean = header in expanded

    /**
     * Whether the block at [position] takes no space: a `</details>` block always (it paints
     * nothing), and any block inside a section that is closed, however deeply nested.
     */
    fun isHidden(position: Int): Boolean = sections.any { section ->
        position == section.closeBlock ||
            (position in bodyOf(section) && section.header !in expanded)
    }

    /** Open or close the section headed at [header]; returns the blocks whose layout changed. */
    fun toggle(header: Int): IntRange {
        val section = sectionAt(header) ?: return IntRange.EMPTY
        if (!expanded.remove(header)) expanded += header
        return section.header..section.last
    }

    /**
     * Open every closed section that hides [position], so a jump to it lands on something visible
     * (a search match, a heading, a footnote). Returns the blocks to re-bind, or null if none.
     *
     * A target that IS a header opens that section too when the header carries text after its
     * summary, since that text is painted only while open; a header with none paints the same either
     * way, so a match there (in its summary) leaves the section as it is.
     */
    fun reveal(position: Int): IntRange? {
        val hiding = sections.filter {
            (position in bodyOf(it) || (position == it.header && it.hasInlineBody)) && it.header !in expanded
        }
        if (hiding.isEmpty()) return null
        hiding.forEach { expanded += it.header }
        return hiding.minOf { it.header }..hiding.maxOf { it.last }
    }

    /** How many sections [position] sits inside, its own header's section excluded. */
    fun depth(position: Int): Int = sections.count { position in bodyOf(it) || position == it.closeBlock }

    private fun bodyOf(section: DetailsSection): IntRange =
        if (section.closed) section.header + 1 until section.last else section.header + 1..section.last
}
