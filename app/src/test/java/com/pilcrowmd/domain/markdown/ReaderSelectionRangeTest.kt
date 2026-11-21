// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSelectionRangeTest {

    private val blocks = listOf("Hello", "World wide", "```code```", "Last one\n")
    private fun textOf(block: Int): CharSequence? = blocks.getOrNull(block)

    private fun range(a: TextPoint, b: TextPoint) = ReaderSelectionRange(a, b)

    @Test
    fun `either end may come first`() {
        val r = range(TextPoint(1, 3), TextPoint(0, 2))
        assertEquals(TextPoint(0, 2), r.start)
        assertEquals(TextPoint(1, 3), r.end)
    }

    @Test
    fun `each block gets its own part`() {
        val r = range(TextPoint(0, 2), TextPoint(2, 4))
        assertEquals(2 to 5, r.rangeIn(0, 5))
        assertEquals(0 to 10, r.rangeIn(1, 10))
        assertEquals(0 to 4, r.rangeIn(2, 10))
        assertNull(r.rangeIn(3, 9))
    }

    @Test
    fun `a block's end clamps to its length, and a start at its end leaves it out`() {
        assertEquals(0 to 9, range(TextPoint(0, 0), TextPoint(3, TextPoint.END)).rangeIn(3, 9))
        assertNull(range(TextPoint(0, TextPoint.END), TextPoint(1, 2)).rangeIn(0, 5))
    }

    @Test
    fun `text runs across blocks, one blank line between them`() {
        val r = range(TextPoint(0, 2), TextPoint(1, 5))
        assertEquals("llo\n\nWorld", r.text(::textOf))
    }

    @Test
    fun `text drops trailing line breaks, pictures and blocks with nothing selected`() {
        val texts = listOf("a\uFFFCb\n", "", "c\n")
        val r = range(TextPoint(0, 0), TextPoint(2, TextPoint.END))
        assertEquals("ab\n\nc", r.text { texts.getOrNull(it) })
    }

    @Test
    fun `a code block's padding lines are not copied`() {
        // As Markwon renders a fenced block: a no-break space line above and below the code.
        val texts = listOf("Before", "\u00A0\ncode 1\n\u00A0", "After")
        val r = range(TextPoint(0, 0), TextPoint(2, TextPoint.END))
        assertEquals("Before\n\ncode 1\n\nAfter", r.text { texts.getOrNull(it) })
    }

    @Test
    fun `a block with no text is skipped`() {
        val r = range(TextPoint(0, 0), TextPoint(2, 3))
        assertEquals("Hello\n\n```", r.text { if (it == 1) null else textOf(it) })
    }

    @Test
    fun `wordAt finds the word around an offset`() {
        val text = "Reader likes writer's friend."
        assertEquals(7 to 12, ReaderSelectionRange.wordAt(text, 9))
        assertEquals(7 to 12, ReaderSelectionRange.wordAt(text, 7))
        assertEquals("just past the word's last letter", 7 to 12, ReaderSelectionRange.wordAt(text, 12))
        assertEquals("an apostrophe between letters", 13 to 21, ReaderSelectionRange.wordAt(text, 15))
        assertEquals("past the end, the last word", 22 to 28, ReaderSelectionRange.wordAt(text, 99))
        assertEquals("off a word, the one character", 2 to 3, ReaderSelectionRange.wordAt("a - b", 2))
        assertEquals(0 to 0, ReaderSelectionRange.wordAt("", 3))
    }
}
