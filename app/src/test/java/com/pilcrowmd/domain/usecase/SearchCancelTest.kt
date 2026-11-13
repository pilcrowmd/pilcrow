// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.ReaderWords
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-A95: a scan the user has typed past stops within one block (one table cell, one plain chunk),
 * so it no longer holds its memory while the next keystroke's scan runs. Each fixture's every block
 * holds a match, so a scan that ignored the cancel would find them all.
 */
class SearchCancelTest {

    private val search = SearchMarkdownUseCase(ReaderWords.ENGLISH)

    /** Says "cancelled" from the [after]+1-th question on. */
    private class CancelAfter(private val after: Int) : () -> Boolean {
        var asked = 0
        override fun invoke(): Boolean = asked++ >= after
    }

    private fun blocksOf(markdown: String) =
        AdapterBlocks.of(requireNotNull(ParseMarkdownHeadingsUseCase().parseDocument(markdown)))

    @Test
    fun `a cancelled markdown scan stops at the next block`() {
        val markdown = "a one\n\na two\n\na three\n\na four\n"
        val matches = search.findSearchMatches(markdown, blocksOf(markdown), "a", isCancelled = CancelAfter(1))

        assertEquals("only the first block was scanned", 1, matches.size)
    }

    @Test
    fun `a cancelled scan stops at the next table cell`() {
        val markdown = "| a | a |\n|---|---|\n| a | a |\n"
        // Asked once before the table block, then once per cell.
        val matches = search.findSearchMatches(markdown, blocksOf(markdown), "a", isCancelled = CancelAfter(2))

        assertEquals("only the first cell was scanned", 1, matches.size)
    }

    @Test
    fun `a cancelled plain scan stops at the next chunk`() {
        val text = "a\n".repeat(PlainTextChunks.split("a\n".repeat(5000)).size.coerceAtLeast(1) * 5000)
        val chunks = PlainTextChunks.split(text)
        val all = search.findPlainSearchMatches(text, chunks, "a")
        val cut = search.findPlainSearchMatches(text, chunks, "a", isCancelled = CancelAfter(1))

        check(chunks.size > 1) { "fixture must have several chunks" }
        assertEquals("only the first chunk was scanned", chunks.first().count { it == 'a' }, cut.size)
        check(cut.size < all.size)
    }

    @Test
    fun `a scan never cancelled finds everything`() {
        val markdown = "a one\n\na two\n"
        assertEquals(2, search.findSearchMatches(markdown, blocksOf(markdown), "a").size)
    }
}
