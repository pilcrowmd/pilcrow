// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.roundtrip.RoundTripCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * M-401: 1.0.14 crashed in search (Play Vitals, `StringIndexOutOfBoundsException` in
 * [codeOpensInMath]). A decoded entity's literal (`&lt;` → `<`) snapped the scan's raw-source cursor to
 * a later verbatim `<`, and every literal after it then missed and pushed the cursor further, past the
 * end of the document, where the next inline code span read outside it.
 */
class SearchCursorOverrunTest {
    private val useCase = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())
    private val emojiUseCase = SearchMarkdownUseCase(
        ParseMarkdownHeadingsUseCase(),
        EmojiShortcodes { alias -> if (alias == "smile") "😄" else null },
    )

    @Test
    fun `an entity whose character comes again at the end does not throw the scan past it`() {
        val content = "&lt;`bbbbbbbbbb` `c` <"

        val matches = useCase.findSearchMatches(content, "c")

        assertEquals(listOf(content.indexOf("`c`") + 1), matches.map { it.startIndex })
    }

    @Test
    fun `text after a decoded entity keeps its own source offset`() {
        val content = "&lt;`x` foo\n\n<b>bar</b>"

        assertEquals(listOf(content.indexOf("foo")), useCase.findSearchMatches(content, "foo").map { it.startIndex })
        assertEquals(listOf(content.indexOf("bar")), useCase.findSearchMatches(content, "bar").map { it.startIndex })
    }

    @Test
    fun `a code span broken across lines is placed at itself, not at the same text in a later block`() {
        // ripgrep's README, cut down: the parser reads `cargo\nbinstall` as "cargo binstall".
        val content = "use [`cargo\nbinstall`](https://x) to install\n\n```\n\$ cargo binstall ripgrep\n```\n"

        // The span's own "binstall", then " to install" after it: placed at themselves, not in the fence.
        assertEquals(content.indexOf("binstall`"), useCase.findSearchMatches(content, "binstall").first().startIndex)
        assertEquals(content.indexOf(" to install") + 4, useCase.findSearchMatches(content, "install")[1].startIndex)
    }

    @Test
    fun `a code span broken by a CRLF or a lone CR is placed at itself too`() {
        for (eol in listOf("\r\n", "\r")) {
            val content = "use [`cargo${eol}binstall`](https://x) to install$eol$eol" +
                "```$eol\$ cargo binstall ripgrep$eol```$eol"

            val second = useCase.findSearchMatches(content, "install")[1]
            assertEquals(content.indexOf(" to install") + 4, second.startIndex)
        }
    }

    @Test
    fun `the ripgrep README searches without throwing`() {
        val readme = RoundTripCorpus.realWorld.first { it.name == "ripgrep.md" }.bytes.toString(Charsets.UTF_8)

        val matches = useCase.findSearchMatches(readme, "c")

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { readme.regionMatches(it.startIndex, "c", 0, 1, ignoreCase = true) })
    }

    @Test
    fun `a code start past the end is not inside a formula`() {
        val content = "a `b`"

        assertFalse(codeOpensInMath(content, BooleanArray(content.length), content.length + 5))
    }

    @Test
    fun `no document in the corpus or generated mix throws or yields an offset outside it`() {
        val corpus = (RoundTripCorpus.commonMarkExamples + RoundTripCorpus.gfmExamples + RoundTripCorpus.realWorld)
            .map { it.bytes.toString(Charsets.UTF_8) }
        val documents = corpus + corpus.take(SPEC_EXAMPLES_IN_CRLF).map { it.replace("\n", "\r\n") } + generated()

        for (document in documents) assertScansWithin(document)
    }

    private fun assertScansWithin(document: String) {
        for (query in QUERIES) {
            for (search in listOf(useCase, emojiUseCase)) {
                val matches = runCatching { search.findSearchMatches(document, query) }.getOrElse {
                    throw AssertionError("search for \"$query\" threw on:\n$document", it)
                }
                val outside = matches.firstOrNull { it.startIndex !in 0..document.length }
                assertTrue("offset ${outside?.startIndex} outside:\n$document", outside == null)
            }
        }
    }

    // Seeded, so a failure names the same document on every run.
    private fun generated(): List<String> {
        val random = Random(SEED)
        return List(GENERATED_DOCUMENTS) {
            buildString {
                repeat(random.nextInt(1, MAX_FRAGMENTS)) {
                    append(FRAGMENTS.random(random))
                    append(SEPARATORS.random(random))
                }
            }
        }
    }

    private companion object {
        const val SEED = 401
        const val GENERATED_DOCUMENTS = 4000
        const val MAX_FRAGMENTS = 14
        const val SPEC_EXAMPLES_IN_CRLF = 400
        val QUERIES = listOf("c", "<", "a b")
        val SEPARATORS = listOf("", "", " ", "\n", "\n\n", "\r\n", "\t")
        val FRAGMENTS = listOf(
            "&lt;", "&gt;", "&amp;", "&quot;", "&#60;", "&nbsp;", "\\<", "\\*", "\\`", "\\:smile:", "\\$",
            "`c`", "`` a ` b ``", "` \$x\$ `", "`", "\$x\$", "\$\$\ny\n\$\$", "\$a **b** c\$", "\$c",
            "<b>", "</b>", "<br>", "<", ">", "\"", "<https://e.com/:smile:>", "[c](http://x \"t\")",
            "![c](p.png)", "[^1]", "[^1]: c", ":smile:", "**c**", "_c_", "~~c~~", "> c", "> [!NOTE] c",
            "> [!TIP]- c", "!!! note \"c\"", "::: tip c", ":::", "<details><summary>c</summary>",
            "</details>", "| a | b |\n|---|---|\n| `c` | &lt; |", "- c", "1. c", "    c", "```\nc\n```",
            "```", "  \n", "c", "a b", "# c", "c\n===",
        )
    }
}
