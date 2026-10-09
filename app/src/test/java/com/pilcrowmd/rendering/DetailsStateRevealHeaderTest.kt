// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.DetailsSection
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-201: text a closed header carries after its summary is painted only while the section is open,
 * so a reveal aimed at that header row must open it. A header with no such text paints the same
 * open or closed, so a reveal there (a match in the summary) leaves it as it is, as a browser's find does.
 */
class DetailsStateRevealHeaderTest {

    private fun state(markdown: String): Pair<DetailsState, List<DetailsSection>> {
        val sections = Details.sections(Parser.builder().build().parse(markdown))
        return DetailsState().apply { load(markdown, sections) } to sections
    }

    @Test
    fun `reveal on a one-block section's header opens it`() {
        // 0 para · 1 <details><summary>S</summary>hidden text</details> · 2 para
        val (s, sections) = state("Before.\n\n<details><summary>S</summary>hidden text</details>\n\nAfter.")
        val oneBlock = DetailsSection(1, 1, closed = true, openByDefault = false, hasInlineBody = true)
        assertEquals(listOf(oneBlock), sections)
        assertFalse(s.isExpanded(1))
        assertEquals(1..1, s.reveal(1))
        assertTrue(s.isExpanded(1))
        assertNull("already open: nothing to re-bind", s.reveal(1))
    }

    @Test
    fun `reveal on a header with nothing after its summary leaves the section closed`() {
        // 0 <details><summary>Install</summary> · 1 body · 2 </details>
        val (s, sections) = state("<details>\n<summary>Install</summary>\n\nbody\n</details>")
        assertEquals(listOf(DetailsSection(0, 2, closed = true, openByDefault = false)), sections)
        assertNull(s.reveal(0))
        assertFalse(s.isExpanded(0))
        assertTrue(s.isHidden(1))
    }

    @Test
    fun `reveal on a nested header opens it and every closed section around it`() {
        // 0 Outer · 1 <details><summary>Inner</summary>hidden text</details> · 2 </details> · 3 para
        val (s, sections) = state(
            "<details>\n<summary>Outer</summary>\n\n" +
                "<details><summary>Inner</summary>hidden text</details>\n\n</details>\n\nAfter.",
        )
        assertEquals(
            listOf(
                DetailsSection(0, 2, closed = true, openByDefault = false),
                DetailsSection(1, 1, closed = true, openByDefault = false, hasInlineBody = true),
            ),
            sections,
        )
        assertEquals(0..2, s.reveal(1))
        assertTrue("outer", s.isExpanded(0))
        assertTrue("inner", s.isExpanded(1))
        assertFalse(s.isHidden(1))
    }

    @Test
    fun `reveal on a nested header with nothing after its summary opens only the section around it`() {
        // 0 Outer · 1 Inner header · 2 x · 3 inner </details> · 4 outer </details>
        val (s, _) = state(
            "<details>\n<summary>Outer</summary>\n\n<details>\n<summary>Inner</summary>\n\nx\n\n" +
                "</details>\n\n</details>",
        )
        assertEquals(0..4, s.reveal(1))
        assertTrue("outer", s.isExpanded(0))
        assertFalse("inner", s.isExpanded(1))
        assertFalse(s.isHidden(1))
    }
}
