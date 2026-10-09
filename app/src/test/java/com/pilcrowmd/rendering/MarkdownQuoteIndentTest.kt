// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * M-188 — in a ```` ```markdown ```` block, the text after `>    ` is part of the quote, not indented
 * `code`. Tokenised by a [Prism4j] over [AliasGrammarLocator], which is how the reader and the PDF
 * build theirs ([buildPilcrowMarkwon]).
 *
 * Each case is one line, so the only tokens that can claim its text are `blockquote` and `code`: a
 * `code` token appears exactly when the quote pattern leaves four spaces or a tab behind.
 */
class MarkdownQuoteIndentTest {

    private val prism4j = Prism4j(AliasGrammarLocator())

    private fun types(nodes: List<Prism4j.Node>): List<String> = nodes.filterIsInstance<Prism4j.Syntax>()
        .flatMap { listOf(it.type()) + types(it.children()) }

    private fun tokenTypes(line: String): List<String> = types(prism4j.tokenize(line, prism4j.grammar("markdown")!!))

    private fun assertNoCode(line: String) = assertFalse("'$line': ${tokenTypes(line)}", "code" in tokenTypes(line))

    @Test
    fun fourSpacesAfterTheMarkerAreQuoteText() = assertNoCode(">    How it looks")

    @Test
    fun aTabAfterTheMarkerIsQuoteText() = assertNoCode(">\tHow it looks")

    @Test
    fun fiveSpacesAfterTheMarkerAreStillIndentedCode() {
        val line = ">     How it looks"
        assertTrue("'$line': ${tokenTypes(line)}", "code" in tokenTypes(line))
    }

    @Test
    fun oneAndTwoSpacesAfterTheMarkerAreUnchanged() {
        assertEquals(listOf("blockquote"), tokenTypes("> How it looks"))
        assertEquals(listOf("blockquote"), tokenTypes(">  How it looks"))
    }

    @Test
    fun listMarkersAreUnchanged() {
        assertNoCode("-    How it looks")
        assertNoCode("1.    How it looks")
    }

    @Test
    fun theGrammarIsPatchedOnceNotStacked() {
        val first = prism4j.grammar("markdown")!!
        val size = first.tokens().size
        val second = prism4j.grammar("md")!!
        assertSame(first, second)
        assertEquals(size, second.tokens().size)
        assertEquals(1, second.tokens().count { it.name() == "blockquote" })
        assertNoCode(">    How it looks")
    }

    @Test
    fun theLibrarysOwnGrammarIsNotChanged() {
        val library = GrammarLocatorDef()
        val original = library.grammar(prism4j, "markdown")!!
        fun quotePattern(grammar: Prism4j.Grammar) =
            grammar.tokens().first { it.name() == "blockquote" }.patterns().single().regex().pattern()
        val before = quotePattern(original)
        val patched = AliasGrammarLocator(library).grammar(prism4j, "markdown")!!
        assertNotSame(original, patched)
        assertEquals(before, quotePattern(original))
    }

    @Test
    fun aMarkdownGrammarWithNoBlockquoteIsLeftAsIs() {
        val bare = Prism4j.grammar("markdown", Prism4j.token("code", Prism4j.pattern(Pattern.compile("x"))))
        val delegate = object : GrammarLocator {
            override fun grammar(prism4j: Prism4j, language: String) = bare
            override fun languages() = setOf("markdown")
        }
        val grammar = AliasGrammarLocator(delegate).grammar(prism4j, "markdown")
        assertSame(bare, grammar)
        assertEquals(listOf("code"), grammar!!.tokens().map { it.name() })
    }
}
