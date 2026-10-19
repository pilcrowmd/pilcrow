// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.CalloutBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `a custom title is searched as painted, anchored on the marker, and the kind's title is not`() {
        val content = "> [!NOTE] Custom callout title\n> Body text"
        val title = search.findSearchMatches(content, "custom").single()
        assertEquals(content.indexOf("[!NOTE]"), title.startIndex)
        assertEquals("the default title is not painted", 0, search.findSearchMatches(content, "note").size)
        assertEquals("the title and body are separate lines", 0, search.findSearchMatches(content, "title body").size)
        val body = search.findSearchMatches(content, "body").single()
        assertEquals(content.indexOf("Body"), body.startIndex)
        assertEquals(0, body.occurrenceInBlock)
        // The body repeats the title, so a cursor left inside the title line would place it there.
        val repeated = "> [!NOTE] Same words\n> Same words"
        assertEquals(
            listOf(repeated.indexOf("[!NOTE]"), repeated.lastIndexOf("Same words")),
            search.findSearchMatches(repeated, "same").map { it.startIndex },
        )
    }

    @Test
    fun `a fold sign is not searchable`() {
        val content = "> [!TIP]- Collapsible\n> Body"
        assertEquals(1, search.findSearchMatches(content, "collapsible").size)
        assertEquals(0, search.findSearchMatches(content, "-").size)
        assertEquals(0, search.findSearchMatches(content, "tip").size)
        assertEquals(content.indexOf("Body"), search.findSearchMatches(content, "body").single().startIndex)
    }

    @Test
    fun `an admonition is searched by its painted title, and its syntax is not`() {
        val content = "Before\n\n!!! info \"Fieldwork note\"\n    Rainfall was measured.\n\nAfter"
        val title = search.findSearchMatches(content, "fieldwork").single()
        assertEquals(content.indexOf("!!!"), title.startIndex)
        assertEquals(1, title.adapterPosition)
        assertEquals(0, search.findSearchMatches(content, "info").size)
        assertEquals(0, search.findSearchMatches(content, "!!!").size)
        assertEquals(0, search.findSearchMatches(content, "\"").size)
        val body = search.findSearchMatches(content, "rainfall").single()
        assertEquals(content.indexOf("Rainfall"), body.startIndex)
        assertEquals(1, body.adapterPosition)
        assertEquals(2, search.findSearchMatches(content, "after").single().adapterPosition)
        val repeated = "!!! note \"Same words\"\n    Same words"
        assertEquals(
            listOf(repeated.indexOf("!!!"), repeated.lastIndexOf("Same words")),
            search.findSearchMatches(repeated, "same").map { it.startIndex },
        )
    }

    @Test
    fun `a fenced admonition is searched by its kind's title, and its fences are not`() {
        val content = "::: warning\nBody\n:::\n\nAfter"
        assertEquals(content.indexOf(":::"), search.findSearchMatches(content, "warning").single().startIndex)
        assertEquals(0, search.findSearchMatches(content, ":").size)
        assertEquals(content.indexOf("Body"), search.findSearchMatches(content, "body").single().startIndex)
        val after = search.findSearchMatches(content, "after").single()
        assertEquals(1, after.adapterPosition)
        assertEquals(content.indexOf("After"), after.startIndex)
    }

    @Test
    fun `a marker search cannot find still has its header line skipped`() {
        // `&#93;` is `]`, so the parsed marker `[!NOTE]` is not in the source and is not located. The
        // title is anchored where the walk stands; the body, which repeats the title, must still be
        // placed on its own line, not inside the header.
        val content = "> [!NOTE&#93; Same\n> Same"
        assertTrue(ParseMarkdownHeadingsUseCase().parseDocument(content)!!.firstChild is CalloutBlock)
        assertEquals(
            listOf(0, content.lastIndexOf("Same")),
            search.findSearchMatches(content, "same").map { it.startIndex },
        )
    }
}
