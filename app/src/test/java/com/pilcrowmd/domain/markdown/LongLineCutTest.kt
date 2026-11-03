// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a run of text too long for one reader TextView is cut (S8-A61): after the last space in the
 * window when there is one, otherwise at the last whole character the window holds, never inside a
 * surrogate pair, a combining sequence or an emoji sequence. A small [limit] keeps each fixture to
 * the few characters that decide it.
 */
class LongLineCutTest {

    private val limit = 10

    /** Every piece [LongLineCut.pieceEnd] makes of [text], in order. */
    private fun pieces(text: String, limit: Int = this.limit): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (from < text.length) {
            val end = LongLineCut.pieceEnd(text, from, text.length, limit)
            assertTrue("each piece makes progress", end > from)
            out += text.substring(from, end)
            from = end
        }
        return out
    }

    @Test
    fun `the cut constant is the one the reader is sized for`() {
        assertEquals(8_000, LongLineCut.MAX_BLOCK_CHARS)
    }

    @Test
    fun `a run that fits is one piece`() {
        assertEquals(listOf("abcdefghij"), pieces("abcdefghij"))
    }

    @Test
    fun `a run with no space is cut at the limit`() {
        assertEquals(listOf("aaaaaaaaaa", "aaaaaaaaaa", "aaaaa"), pieces("a".repeat(25)))
    }

    @Test
    fun `a run with a space before the limit is cut just after the space`() {
        // The space sits at index 8, two short of the limit: the head keeps it, the next piece starts on the word.
        assertEquals(listOf("aaaaaaaa ", "bbbbbbbbbb", "bb"), pieces("aaaaaaaa " + "b".repeat(12)))
    }

    @Test
    fun `the last space in the window wins over an earlier one`() {
        assertEquals(listOf("a b c d ", "eeeeeeeeee"), pieces("a b c d " + "e".repeat(10)))
    }

    @Test
    fun `a surrogate pair across the limit is never split`() {
        // U+20000 (CJK Extension B) is two chars; it starts at index 9, so the limit falls inside it.
        val text = "a".repeat(9) + "𠀀" + "b".repeat(5)
        val result = pieces(text)
        assertEquals(text, result.joinToString(""))
        assertEquals("a".repeat(9), result[0])
        result.forEach { assertWhole(it) }
    }

    @Test
    fun `an emoji ZWJ sequence across the limit is never split`() {
        // Woman, ZWJ, laptop: five chars that draw as one glyph, starting at index 7.
        val family = "👩‍💻"
        val text = "a".repeat(7) + family + "b".repeat(6)
        val result = pieces(text)
        assertEquals(text, result.joinToString(""))
        assertEquals("a".repeat(7), result[0])
        assertTrue("the sequence travels whole", result[1].startsWith(family))
    }

    @Test
    fun `a flag across the limit is never split`() {
        val flag = "🇵🇱" // regional indicators P + L
        val text = "a".repeat(8) + flag + "b".repeat(4)
        val result = pieces(text)
        assertEquals("a".repeat(8), result[0])
        assertTrue(result[1].startsWith(flag))
    }

    @Test
    fun `a combining mark stays with its letter`() {
        // e + COMBINING ACUTE ACCENT at index 9..10: the limit falls between them.
        val text = "a".repeat(9) + "é" + "b".repeat(5)
        val result = pieces(text)
        assertEquals("a".repeat(9), result[0])
        assertTrue(result[1].startsWith("é"))
    }

    @Test
    fun `CJK with no space is cut at the limit and keeps every character`() {
        val text = "漢字".repeat(12)
        val result = pieces(text)
        assertEquals(listOf(10, 10, 4), result.map { it.length })
        assertEquals(text, result.joinToString(""))
    }

    @Test
    fun `one cluster longer than the window is still cut, never inside a surrogate pair`() {
        // A base letter followed by twenty combining marks is one grapheme: no boundary in the window.
        val text = "a" + "́".repeat(20) + "😀".repeat(3)
        val result = pieces(text)
        assertEquals(text, result.joinToString(""))
        result.forEach { assertWhole(it) }
    }

    @Test
    fun `a no-break space is not a place to cut`() {
        // The only whitespace in the window is a no-break space at index 4: it joins its neighbours, so
        // the cut falls at the limit instead of after it. Kotlin's isWhitespace() is true for all three.
        for (nbsp in listOf('\u00A0', '\u2007', '\u202F')) {
            val text = "aaaa" + nbsp + "b".repeat(12)
            assertEquals("U+%04X".format(nbsp.code), listOf(text.substring(0, 10), text.substring(10)), pieces(text))
        }
    }

    @Test
    fun `a space is not cut from the combining mark after it`() {
        // The last space in the window, at index 8, is followed by U+0301 at index 9: cutting after it
        // would start a piece with the mark. The earlier space at index 3 is the cut.
        val text = "aaa bbbb" + " \u0301" + "c".repeat(8)
        val result = pieces(text)
        assertEquals("aaa ", result[0])
        assertEquals(text, result.joinToString(""))
        result.forEach {
            val first = Character.getType(it.first())
            assertTrue("no piece starts with a combining mark", first != Character.NON_SPACING_MARK.toInt())
        }
    }

    @Test
    fun `a space and its mark with no earlier space travel whole`() {
        val text = "a".repeat(8) + " \u0301" + "b".repeat(6)
        assertEquals(listOf("a".repeat(8) + " \u0301", "b".repeat(6)), pieces(text))
    }

    @Test
    fun `a piece never ends past the end it was given`() {
        val text = "a".repeat(30) + "\n" + "b".repeat(30)
        assertEquals(10, LongLineCut.pieceEnd(text, 0, 30, limit))
        assertEquals(30, LongLineCut.pieceEnd(text, 25, 30, limit))
    }

    private fun assertWhole(piece: String) {
        assertTrue("no piece starts with a low surrogate: $piece", !piece.first().isLowSurrogate())
        assertTrue("no piece ends with a high surrogate: $piece", !piece.last().isHighSurrogate())
    }
}
