// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.model.SearchMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-A94 (large-documents spec, Rule 4): a search keeps at most [SearchMatches.MAX_MATCHES] matches, the
 * first ones in document order, and says when there were more ("10,000+"). Searching "e" in a 20 MB
 * file found millions, each an object; that alone could run the phone out of memory.
 */
class SearchMatchCapTest {

    private val search = SearchMarkdownUseCase(ReaderWords.ENGLISH)

    @Test
    fun `the markdown search stops at the cap and says there are more`() {
        val markdown = "a ".repeat(SearchMatches.MAX_MATCHES + 5)
        val matches = search.findSearchMatches(markdown, "a")

        assertEquals(SearchMatches.MAX_MATCHES, matches.size)
        assertTrue(matches.capped)
        val lastStart = 2 * (SearchMatches.MAX_MATCHES - 1)
        assertEquals("the last one kept is the 10,000th", lastStart, matches.last().startIndex)
    }

    @Test
    fun `exactly the cap is not more than the cap`() {
        val matches = search.findSearchMatches("a ".repeat(SearchMatches.MAX_MATCHES), "a")

        assertEquals(SearchMatches.MAX_MATCHES, matches.size)
        assertFalse(matches.capped)
    }

    @Test
    fun `the plain search stops at the cap too`() {
        val text = "a\n".repeat(SearchMatches.MAX_MATCHES + 1)
        val matches = search.findPlainSearchMatches(text, PlainTextChunks.split(text), "a")

        assertEquals(SearchMatches.MAX_MATCHES, matches.size)
        assertTrue(matches.capped)
    }

    @Test
    fun `capped matches are the first ones, in order, across blocks`() {
        val markdown = "one aa\n\ntwo aa\n\nthree aa\n"
        val all = search.findSearchMatches(markdown, "a")
        val firstFour = search.findSearchMatches(markdown, "a", limit = 4)

        assertEquals(6, all.size)
        assertEquals(all.take(4), firstFour.toList())
        assertTrue(firstFour.capped)
        assertEquals(SearchMatch("a", 13, 1, 1), firstFour[3])
    }

    @Test
    fun `each match reads back the case it was found in`() {
        val matches = search.findSearchMatches("Aa aA\n", "aa")

        assertEquals(listOf("Aa", "aA"), matches.map { it.content })
        assertEquals(listOf(0, 3), matches.map { it.startIndex })
    }
}
