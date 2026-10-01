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

/** M-161: which blocks a closed section hides, and how a tap or a jump opens it. */
class DetailsStateTest {

    // Blocks: 0 para · 1 outer header · 2 body · 3 inner header · 4 inner body · 5 inner close ·
    // 6 outer close · 7 para.
    private val outer = DetailsSection(header = 1, last = 6, closed = true, openByDefault = false)
    private val inner = DetailsSection(header = 3, last = 5, closed = true, openByDefault = false)

    private fun state(vararg sections: DetailsSection, content: String = "doc") =
        DetailsState().apply { load(content, sections.toList()) }

    @Test
    fun `a closed section hides its body and its close block, never its header`() {
        val s = state(outer)
        assertFalse(s.isHidden(0))
        assertFalse(s.isHidden(1))
        assertTrue(s.isHidden(2))
        assertTrue(s.isHidden(6))
        assertFalse(s.isHidden(7))
    }

    @Test
    fun `an open section shows its body, and its close block still paints nothing`() {
        val s = state(outer)
        assertEquals(1..6, s.toggle(1))
        assertFalse(s.isHidden(2))
        assertTrue(s.isHidden(6))
        s.toggle(1)
        assertTrue("a second tap closes it again", s.isHidden(2))
    }

    @Test
    fun `a nested body stays hidden while either section is closed`() {
        val s = state(outer, inner)
        s.toggle(1)
        assertFalse(s.isHidden(3))
        assertTrue("inner still closed", s.isHidden(4))
        s.toggle(3)
        assertFalse(s.isHidden(4))
        s.toggle(1)
        assertTrue("outer closed again hides the open inner body too", s.isHidden(4))
    }

    @Test
    fun `reveal opens every closed section around a target, and nothing when it is visible`() {
        val s = state(outer, inner)
        assertEquals(1..6, s.reveal(4))
        assertFalse(s.isHidden(4))
        assertNull(s.reveal(4))
        assertNull(s.reveal(7))
    }

    @Test
    fun `depth counts the sections a block sits inside`() {
        val s = state(outer, inner)
        assertEquals(0, s.depth(1))
        assertEquals(1, s.depth(2))
        assertEquals(1, s.depth(3))
        assertEquals(2, s.depth(4))
    }

    @Test
    fun `open marks a section open, the same content keeps what the user opened, new content resets`() {
        assertFalse(state(outer.copy(openByDefault = true)).isHidden(2))
        val s = state(outer)
        s.toggle(1)
        s.load("doc", listOf(outer))
        assertFalse("a re-parse of the same text (zoom, theme) keeps the section open", s.isHidden(2))
        s.load("edited doc", listOf(outer))
        assertTrue("a different document starts closed", s.isHidden(2))
    }

    private fun hiddenWhenAllClosed(markdown: String): List<Int> {
        val sections = Details.sections(Parser.builder().build().parse(markdown))
        val s = state(*sections.toTypedArray())
        return (0..sections.maxOf { it.last }).filter { s.isHidden(it) }
    }

    /** Back-to-back sections where the first has no body: the next header must not be hidden in it. */
    @Test
    fun `an empty section closed by the next header hides nothing`() {
        // 0 <details>A · 1 </details><details>B · 2 Body B · 3 </details>
        val markdown = "<details>\n<summary>A</summary>\n\n" +
            "</details>\n<details>\n<summary>B</summary>\n\nBody B\n\n</details>"
        assertEquals(listOf(2, 3), hiddenWhenAllClosed(markdown))
    }

    /** Several close tags before the next header end every section they close, innermost first. */
    @Test
    fun `several close tags before a header end each section they close`() {
        // 0 Outer · 1 Inner · 2 x · 3 </details></details><details>Next · 4 y · 5 </details>
        val markdown = "<details>\n<summary>Outer</summary>\n\n<details>\n<summary>Inner</summary>\n\nx\n\n" +
            "</details>\n</details>\n<details>\n<summary>Next</summary>\n\ny\n\n</details>"
        assertEquals(listOf(1, 2, 4, 5), hiddenWhenAllClosed(markdown))
    }
}
