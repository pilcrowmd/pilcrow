// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #17: json is tokenised in windows of about 20,000 chars, and the runs must be the single pass's,
 * character for character. The one place a cut can change a token is Prism4j's json lookahead
 * `(?=\s*:)`, which tells a `property` from a `string` and runs over blank lines: a window must not end
 * before a line whose first non-blank char is a `:`.
 *
 * The single pass is the same grammar under another name, which the highlighter does not window (it
 * windows the grammar called `json`), so both sides use the same token-to-run code.
 */
class Prism4jJsonWindowTest {

    private val windowed = Prism4jCodeHighlighter()

    private val singlePass = Prism4jCodeHighlighter(
        object : GrammarLocator {
            override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? =
                AliasGrammarLocator().grammar(prism4j, "json")?.let { Prism4j.grammar("json-whole", it.tokens()) }

            override fun languages(): Set<String> = setOf("json-whole")
        },
    )

    /**
     * Keys whose `:` is on the next line, or after blank lines, are all over the block, so a window cut
     * (the first line end past 20,000 chars on) meets one of them unless the highlighter looks.
     */
    private val json: String = buildString {
        append("{\n")
        var i = 0
        while (length < FOUR_WINDOWS_AND_MORE) {
            append(
                when (i % 3) {
                    0 -> "  \"key$i\"\n  : $i,\n"
                    1 -> "  \"key$i\"\n\n   \n\t: \"value $i\",\n"
                    else -> "  \"key$i\": [true, null, -1.5e3, \"text $i\"],\n"
                },
            )
            i++
        }
        append("  \"last\": 0\n}\n")
    }

    @Test
    fun `windowed json runs are the single pass's, character for character`() {
        check(json.length > 3 * WINDOW) { "more than three windows: ${json.length} chars" }
        val whole = singlePass.tokenize("json-whole", json)!!
        val cut = windowed.tokenize("json", json)!!

        assertTrue("the single pass finds properties, or no cut can matter", whole.any { it.role == CodeRole.VARIABLE })
        val at = (0 until maxOf(whole.size, cut.size)).firstOrNull { whole.getOrNull(it) != cut.getOrNull(it) }
        assertNull(
            "first differing run #$at: single pass ${whole.getOrNull(at ?: 0)}, windowed ${cut.getOrNull(at ?: 0)}",
            at,
        )
    }

    @Test
    fun `the windows come one at a time, each with every run so far, and the cache holds the whole`() = runBlocking {
        val whole = singlePass.tokenize("json-whole", json)!!
        val ready = ArrayList<Pair<List<CodeRun>, Int>>()

        val result = windowed.tokenizeProgressively("jsonp", json) { runs, covered -> ready += runs to covered }

        assertEquals(whole, result)
        assertTrue("more than three windows: ${ready.size}", ready.size > 3)
        assertEquals("the last call covers the block", json.length, ready.last().second)
        assertEquals(ready.map { it.second }.sorted().distinct(), ready.map { it.second })
        ready.forEach { (runs, covered) ->
            assertEquals("every run so far, none past `covered`", whole.filter { it.end <= covered }, runs)
        }
        assertSame("the cache holds the whole, under the one key", result, windowed.cachedRuns("jsonp", json))
        assertSame(result, windowed.tokenize("jsonp", json))
    }

    @Test
    fun `a block that is not json is one pass`() = runBlocking {
        val kotlin = "val x = \"a\"\n".repeat(FOUR_WINDOWS_AND_MORE / 12)
        val ready = ArrayList<Int>()

        windowed.tokenizeProgressively("kotlin", kotlin) { _, covered -> ready += covered }

        assertEquals(listOf(kotlin.length), ready)
    }

    private companion object {
        const val WINDOW = 20_000
        const val FOUR_WINDOWS_AND_MORE = 90_000
    }
}
