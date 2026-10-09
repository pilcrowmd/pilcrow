// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
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
    fun blankFreeRunWithinCapStaysUnsplit() {
        val src = (1..PlainTextChunks.MAX_CHUNK_LINES).joinToString("\n") { "line $it" }
        assertEquals("a run of exactly the cap is one chunk", 1, chunks(src).size)
        assertEquals("one line over the cap splits", 2, chunks("$src\nline extra").size)
    }

    private fun chunkNodes(content: String): List<PlainTextChunk> {
        val out = mutableListOf<PlainTextChunk>()
        var node = PlainTextBlocks.build(content).firstChild
        while (node != null) {
            out += node as PlainTextChunk
            node = node.next
        }
        return out
    }

    private fun lineCount(chunk: String) = chunk.count { it == '\n' } + 1

    @Test
    fun blankFreeRunIsSplitAtTheCapWithSeamFlags() {
        // M-365: a long run with no blank line used to stay ONE TextView, recorded in full on every
        // draw (Play ANR in TextView.onDraw). It now splits every MAX_CHUNK_LINES lines.
        val src = (1..1000).joinToString("\n") { "line $it" }
        val nodes = chunkNodes(src)
        assertEquals(listOf(400, 400, 200), nodes.map { lineCount(it.literal) })
        assertEquals("lossless reconstruction", src, nodes.joinToString("\n") { it.literal })
        assertEquals(
            "continuesPrevious / continuesNext per chunk",
            listOf(false to true, true to true, true to false),
            nodes.map { it.continuesPrevious to it.continuesNext },
        )
        assertEquals("PlainTextChunks.split agrees with build", nodes.map { it.literal }, PlainTextChunks.split(src))
    }

    @Test
    fun blankLineSplitBeforeTheCapKeepsBothFlagsFalse() {
        // 250 text lines, a blank, then 300 more: breaks at the blank (>= target), not at the cap.
        val src = ((1..250).map { "a$it" } + "" + (1..300).map { "b$it" }).joinToString("\n")
        val nodes = chunkNodes(src)
        assertEquals(2, nodes.size)
        assertEquals(251, lineCount(nodes[0].literal))
        assertTrue("first chunk ends with the blank", nodes[0].literal.endsWith("a250\n"))
        nodes.forEach {
            assertEquals(false, it.continuesPrevious)
            assertEquals(false, it.continuesNext)
        }
        assertLossless(src)
    }

    @Test
    fun forcedSplitAfterABlankLineIsNotASeam() {
        // 399 text lines, then a blank at line 400: the cap lands right after a blank line, so the
        // boundary is a normal blank-line split and neither chunk continues.
        val src = ((1..399).map { "a$it" } + "" + (1..10).map { "b$it" }).joinToString("\n")
        val nodes = chunkNodes(src)
        assertEquals(2, nodes.size)
        nodes.forEach {
            assertEquals(false, it.continuesPrevious)
            assertEquals(false, it.continuesNext)
        }
        assertLossless(src)
    }

    @Test
    fun capLinesPlusTrailingNewlineLeavesAnEmptyContinuationChunk() {
        // 400 non-blank lines then "\n": the split yields a 401st, empty line, which is cut off as a
        // chunk of its own (the previous line is not blank, so the seam is forced) and joins back.
        val src = (1..400).joinToString("\n") { "line $it" } + "\n"
        val nodes = chunkNodes(src)
        assertEquals(listOf(400, 1), nodes.map { lineCount(it.literal) })
        assertEquals("", nodes[1].literal)
        assertEquals(
            listOf(false to true, true to false),
            nodes.map { it.continuesPrevious to it.continuesNext },
        )
        assertLossless(src)
    }

    @Test
    fun maxChunkLinesParameterLetsACallerOptOutOfTheCap() {
        val src = (1..1000).joinToString("\n") { "line $it" }
        val nodes = generateSequence(PlainTextBlocks.build(src, maxChunkLines = Int.MAX_VALUE).firstChild) { it.next }
            .map { it as PlainTextChunk }.toList()
        assertEquals(1, nodes.size)
        assertEquals(false to false, nodes.single().continuesPrevious to nodes.single().continuesNext)
        assertEquals("search keeps the reader's split", 3, PlainTextChunks.split(src).size)
    }

    @Test
    fun crlfBlankFreeRunSplitsAndJoinsBackByteExact() {
        val src = (1..1000).joinToString("") { "line $it\r\n" }
        val result = chunks(src)
        assertEquals(3, result.size)
        assertLossless(src)
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
                chunk.count { it == '\n' } + 1 <= PlainTextChunks.TARGET_CHUNK_LINES + 1,
            )
        }
        assertLossless(src)
    }

    @Test
    fun blankBeforeTargetDoesNotSplit() {
        val src = ((1..50).map { "a$it" } + "" + (1..50).map { "b$it" }).joinToString("\n")
        assertEquals(1, chunks(src).size)
    }

    @Test
    fun searchFindsTextRightAfterAForcedSplit() {
        // The first line of the second chunk: the plain search runs over the same literals the
        // adapter shows, so the match must carry that chunk's adapter position and raw offset.
        val src = (1..1000).joinToString("\n") { "line $it" }
        val literals = PlainTextChunks.split(src)
        val matches = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findPlainSearchMatches(literals, "line 401")
        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals("second chunk's adapter position", 1, match.adapterPosition)
        assertEquals("raw offset into the whole content", src.indexOf("line 401"), match.startIndex)
        val offsetInChunk = match.startIndex - (literals[0].length + 1)
        assertEquals("offset points at the text inside that chunk literal", 0, offsetInChunk)
        assertEquals("line 401", literals[1].substring(offsetInChunk, offsetInChunk + "line 401".length))
    }

    @Test
    fun searchAndDisplayAgreeOnACappedFile() {
        // A 1,000-line blank-free document is capped into 3 chunks. Search and the painter take the
        // same split, so a hit on line 900 resolves to the adapter position of the chunk that shows it.
        val src = (1..1000).joinToString("\n") { "line $it" }
        val split = PlainTextChunks.split(src)
        assertEquals(3, split.size)
        assertEquals("display children are the search split", split, chunks(src))

        // Too deep to render, so findSearchMatches searches the plain-text chunks (PlainTextChunks.split).
        val tooDeep = ">".repeat(150) + " head\n" + src
        val matches = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(tooDeep, "line 900")
        assertEquals(1, matches.size)
        assertEquals("line 900 is in the third chunk", 2, matches.single().adapterPosition)

        // The same position through the plain-mode entry point over the painter's literals.
        val plain = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findPlainSearchMatches(split, "line 900")
        assertEquals(2, plain.single().adapterPosition)
    }
}
