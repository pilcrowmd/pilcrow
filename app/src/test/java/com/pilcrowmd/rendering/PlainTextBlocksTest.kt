// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain-text render model: verbatim literals, blank-line-boundary chunking, and the
 * lossless reconstruction invariant — joining chunk literals with "\n" reproduces the input.
 */
class PlainTextBlocksTest {

    private fun chunks(content: String): List<String> {
        val doc = PlainTextBlocks.build(content)
        val out = mutableListOf<String>()
        var node = doc.firstChild
        while (node != null) {
            out += (node as PlainTextChunk).literal
            node = node.next
        }
        return out
    }

    private fun assertLossless(content: String) =
        assertEquals("lossless reconstruction", content, chunks(content).joinToString("\n"))

    // --- verbatim literals: Markdown syntax means nothing ---

    @Test
    fun markdownSyntaxStaysLiteral() {
        val src = "# not a heading\n**not bold**\n- not a list\n| not | a table |\n`not code`"
        assertEquals(listOf(src), chunks(src))
    }

    // --- lossless invariant across shapes ---

    @Test fun losslessSimple() = assertLossless("alpha\nbeta\n\ngamma")

    @Test fun losslessTrailingNewline() = assertLossless("alpha\nbeta\n")

    @Test fun losslessBlankRuns() = assertLossless("a\n\n\n\nb\n\n")

    @Test fun losslessWhitespaceOnlyLines() = assertLossless("a\n   \n\t\nb")

    @Test fun losslessLeadingBlanks() = assertLossless("\n\nstarts after blanks")

    @Test
    fun losslessLargeMixed() {
        val src = (1..450).joinToString("\n") { i -> if (i % 37 == 0) "" else "line $i **md** #x" }
        assertLossless(src)
    }

    // --- chunking rules ---

    @Test
    fun emptyContentYieldsNoChunks() {
        assertEquals(emptyList<String>(), chunks(""))
    }

    @Test
    fun shortContentIsOneChunk() {
        assertEquals(1, chunks("a\n\nb\n\nc").size)
    }

    @Test
    fun noBlankLineNeverSplits() {
        val src = (1..500).joinToString("\n") { "line $it" }
        assertEquals("blank-line-free run stays unsplit", 1, chunks(src).size)
    }

    @Test
    fun splitsAtBlankBoundaryAfterTarget() {
        // 220 text lines, a blank, then more text: the break lands after the blank run.
        val head = (1..220).map { "line $it" }
        val tail = (1..50).map { "tail $it" }
        val src = (head + "" + tail).joinToString("\n")
        val result = chunks(src)
        assertEquals(2, result.size)
        assertTrue("blank run stays at the END of the previous chunk", result[0].endsWith("line 220\n"))
        assertTrue("next chunk starts non-blank", result[1].startsWith("tail 1"))
        assertLossless(src)
    }

    @Test
    fun multiBlankRunCanSplitBetweenBlanks() {
        // A break is allowed BETWEEN blank lines too (both sides of the seam are empty
        // space, so it stays invisible) — required so all-blank files can still chunk.
        val head = (1..210).map { "line $it" }
        val tail = (1..30).map { "tail $it" }
        val src = (head + listOf("", "", "") + tail).joinToString("\n")
        val result = chunks(src)
        assertEquals(2, result.size)
        assertTrue("break lands inside the blank run", result[0].endsWith("line 210\n"))
        assertTrue("next chunk starts with the remaining blanks", result[1].startsWith("\n"))
        assertLossless(src)
    }

    @Test
    fun allBlankFileStillChunks() {
        // Hostile fixture: thousands of blank lines must not become one giant TextView.
        val src = "\n".repeat(1999) // 2000 blank lines
        val result = chunks(src)
        assertTrue("blank-only file splits into bounded chunks, got ${result.size}", result.size >= 5)
        result.forEach { chunk ->
            assertTrue(
                "every chunk stays near the target size",
                chunk.count { it == '\n' } + 1 <= PlainTextBlocks.TARGET_CHUNK_LINES + 1,
            )
        }
        assertLossless(src)
    }

    @Test
    fun blankBeforeTargetDoesNotSplit() {
        val src = ((1..50).map { "a$it" } + "" + (1..50).map { "b$it" }).joinToString("\n")
        assertEquals(1, chunks(src).size)
    }
}
