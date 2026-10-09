// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * Resolves fence languages the bundle has no grammar for, but can highlight under another name
 * (M-133, M-187). The kapt-generated [GrammarLocatorDef] is rewritten on every build, so aliases live
 * here, wrapped around it, rather than in generated code.
 *
 * `diff` and `patch` go to the bundled `git` grammar. A unified diff is what `git diff` prints, and
 * that grammar already tokenises its `inserted`, `deleted` and `coord` (`@@ … @@`) lines. `jsonc` is
 * not here: it has its own TextMate grammar (NEW-35). The other aliases are the common short or
 * versioned names of a bundled grammar (`python3`, `kt`, `yml`, `c++`, …, M-187); a name that has a
 * TextMate grammar ([TextMateCodeHighlighter.scopeForFence]) is never an alias here.
 *
 * Markwon passes the whole info string, so it is read through [fenceLanguage] first: a fence with
 * attributes (```` ```python title="x" ````) is looked up as `python` (M-243), and `Python` as
 * `python`. The PDF uses the same aliases as the screen (M-178, NEW-22).
 *
 * The `markdown` grammar's `blockquote` also takes one space or tab after the last `>` (M-188).
 * Prism4j's own pattern stops at the `>`, so in `>    text` the rest of the line started with four
 * spaces and was tokenised as indented `code`. CommonMark reads it as a quote around a paragraph;
 * only five or more spaces after `>` are indented code inside a quote, and those still are.
 */
class AliasGrammarLocator(
    private val delegate: GrammarLocator = GrammarLocatorDef(),
    private val aliases: Map<String, String> = ALIASES,
) : GrammarLocator {

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val name = fenceLanguage(language)
        val grammar = delegate.grammar(prism4j, aliases[name] ?: name)
        if (grammar?.name() != "markdown") return grammar
        patchedMarkdown?.takeIf { it.first === grammar }?.let { return it.second }
        return withQuoteIndent(grammar).also { patchedMarkdown = grammar to it }
    }

    /** The delegate's `markdown` grammar and this locator's copy of it (M-188), built once. */
    @Volatile
    private var patchedMarkdown: Pair<Prism4j.Grammar, Prism4j.Grammar>? = null

    override fun languages(): Set<String> = delegate.languages() + aliases.keys

    private companion object {
        val ALIASES = mapOf(
            "diff" to "git",
            "patch" to "git",
            "python3" to "python",
            "py" to "python",
            "py3" to "python",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "yml" to "yaml",
            "md" to "markdown",
            "h" to "c",
            "cc" to "cpp",
            "cxx" to "cpp",
            "c++" to "cpp",
            "hpp" to "cpp",
            "cs" to "csharp",
            "c#" to "csharp",
            "golang" to "go",
            "clj" to "clojure",
            "gradle" to "groovy",
            "tex" to "latex",
            "make" to "makefile",
        )

        /** Prism4j's `^>(?:[\t ]*>)*`, plus one optional space or tab after the last `>` (M-188). */
        const val BLOCKQUOTE = "^>(?:[\\t ]*>)*[\\t ]?"

        /**
         * A copy of [markdown] whose `blockquote` uses [BLOCKQUOTE], with the original's regex flags
         * (`MULTILINE`). A copy, because the delegate caches its grammar objects and they are not ours
         * to change. A grammar with no single-pattern `blockquote` token is returned as is.
         */
        fun withQuoteIndent(markdown: Prism4j.Grammar): Prism4j.Grammar {
            val tokens = markdown.tokens()
            val index = tokens.indexOfFirst { it.name() == "blockquote" }
            if (index < 0) return markdown
            val pattern = tokens[index].patterns().singleOrNull() ?: return markdown
            val blockquote = Prism4j.token(
                "blockquote",
                Prism4j.pattern(
                    Pattern.compile(BLOCKQUOTE, pattern.regex().flags()),
                    pattern.lookbehind(),
                    pattern.greedy(),
                    pattern.alias(),
                    pattern.inside(),
                ),
            )
            val patched = tokens.mapIndexed { i, token -> if (i == index) blockquote else token }
            return Prism4j.grammar(markdown.name(), patched)
        }
    }
}
