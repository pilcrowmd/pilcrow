// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-A61: a line longer than [LongLineCut.MAX_BLOCK_CHARS] is cut inside the line, so no plain-text
 * chunk hands one TextView a paragraph it must line-break on the main thread for seconds. The line
 * caps count lines, so a file of one line is the fixture only the character limit can cut.
 */
class PlainTextChunksLongLineTest {

    private val maxChars = LongLineCut.MAX_BLOCK_CHARS

    /** [content] rebuilt from its chunks: a '\n' between two chunks only where a line cut dropped one. */
    private fun rebuilt(content: String): String {
        val literals = PlainTextChunks.split(content)
        val starts = PlainTextChunks.chunkStarts(content)
        assertEquals(literals.size, starts.size)
        return buildString {
            literals.forEachIndexed { i, literal ->
                val atStart = content.substring(starts[i], starts[i] + literal.length)
                assertEquals("chunk $i starts where chunkStarts says", literal, atStart)
                append(literal)
                val next = starts.getOrNull(i + 1) ?: return@forEachIndexed
                val gap = content.substring(starts[i] + literal.length, next)
                assertTrue("between two chunks: nothing or the dropped newline, got '$gap'", gap == "" || gap == "\n")
                append(gap)
            }
        }
    }

    @Test
    fun `one 20K line with no space becomes at least three chunks within the limit`() {
        val content = "x".repeat(20_000)

        val pieces = PlainTextChunks.pieces(content)

        assertTrue("at least three chunks, got ${pieces.size}", pieces.size >= 3)
        pieces.forEach { assertTrue(it.literal.length <= maxChars) }
        assertFalse(pieces[0].forcedBefore)
        pieces.drop(1).forEach { assertTrue("a cut inside a line is a visible seam", it.forcedBefore) }
        assertEquals("byte for byte", content, rebuilt(content))
    }

    @Test
    fun `a long line is cut after its last space within the window`() {
        val head = "a".repeat(maxChars - 10) + " "
        val content = head + "b".repeat(12_000)

        assertEquals(head, PlainTextChunks.split(content)[0])
        assertEquals(content, rebuilt(content))
    }

    @Test
    fun `short lines around a long one keep their own newlines`() {
        // "x" in front of the emoji, so a cut at the bare limit would land inside a pair.
        val content = "first line\nsecond\nx" + "😀".repeat(9_000) + "\nlast line\n"

        val literals = PlainTextChunks.split(content)

        assertTrue(literals.size >= 3)
        literals.forEach {
            assertFalse(it.first().isLowSurrogate())
            assertFalse(it.last().isHighSurrogate())
            // A chunk may hold many lines; what the limit bounds is each line, one paragraph to lay out.
            it.split('\n').forEach { line -> assertTrue(line.length <= maxChars) }
        }
        assertTrue(literals.first().startsWith("first line\nsecond\n"))
        assertTrue(literals.last().endsWith("\nlast line\n"))
        assertEquals("byte for byte", content, rebuilt(content))
    }

    @Test
    fun `a file whose lines all fit chunks exactly as before`() {
        val content = (0 until 500).joinToString("\n") { "line $it " + "w".repeat(200) }

        val literals = PlainTextChunks.split(content)

        assertEquals("joined with newlines, as before", content, literals.joinToString("\n"))
        assertEquals(listOf(400, 100), literals.map { it.count { c -> c == '\n' } + 1 })
    }
}
