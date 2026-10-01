// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M-161: search walks what the reader PAINTS. A callout paints its title where the `[!NOTE]` marker
 * was; a `<details>` header paints its summary, not its tags; a `</details>` block paints nothing.
 */
class SearchCalloutsAndDetailsTest {

    private val search = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())

    @Test
    fun `a callout's title is found, anchored on its marker, and the marker text is not`() {
        val content = "> [!NOTE]\n> Body text"
        val title = search.findSearchMatches(content, "note")
        assertEquals(1, title.size)
        assertEquals(content.indexOf("[!NOTE]"), title.single().startIndex)
        assertEquals(0, search.findSearchMatches(content, "[!").size)
        val body = search.findSearchMatches(content, "body").single()
        assertEquals(content.indexOf("Body"), body.startIndex)
        assertEquals("the title is occurrence 0 of the block, so the body's is its own", 0, body.occurrenceInBlock)
    }

    @Test
    fun `a details header is searched by its summary, and its tags are not`() {
        val content = "<details>\n<summary>Install notes</summary>\n\nHidden body\n</details>\n\nAfter"
        val summary = search.findSearchMatches(content, "install").single()
        assertEquals(0, summary.adapterPosition)
        assertEquals(0, search.findSearchMatches(content, "summary").size)
        assertEquals("the </details> block paints nothing", 0, search.findSearchMatches(content, "details").size)
        val body = search.findSearchMatches(content, "hidden").single()
        assertEquals("the body is its own block, which a jump there opens", 1, body.adapterPosition)
        assertEquals(content.indexOf("Hidden"), body.startIndex)
        assertEquals(3, search.findSearchMatches(content, "after").single().adapterPosition)
    }

    @Test
    fun `the one-line form is searched as its summary, then its body`() {
        val content = "<details><summary>Tight</summary>Its body.</details>"
        assertEquals(1, search.findSearchMatches(content, "tight").size)
        assertEquals(1, search.findSearchMatches(content, "body").size)
        assertEquals("summary and body are not run together", 0, search.findSearchMatches(content, "tightits").size)
    }

    @Test
    fun `a header that also closes the section before it is searched by its summary`() {
        val content = "<details>\n<summary>A</summary>\n\nBody A\n\n" +
            "</details>\n<details>\n<summary>Bravo</summary>\n\nB"
        assertEquals(2, search.findSearchMatches(content, "bravo").single().adapterPosition)
        assertEquals("its tags are not painted", 0, search.findSearchMatches(content, "details").size)
    }
}
