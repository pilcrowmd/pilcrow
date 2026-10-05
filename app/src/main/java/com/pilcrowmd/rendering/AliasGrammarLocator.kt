// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j

/**
 * Resolves fence languages the bundle has no grammar for, but can highlight under another name
 * (M-133). The kapt-generated [GrammarLocatorDef] is rewritten on every build, so aliases live
 * here, wrapped around it, rather than in generated code.
 *
 * `diff` and `patch` go to the bundled `git` grammar. A unified diff is what `git diff` prints, and
 * that grammar already tokenises its `inserted`, `deleted` and `coord` (`@@ … @@`) lines. `jsonc` is
 * not here: it has its own TextMate grammar (NEW-35).
 *
 * Markwon passes the whole info string, so it is read through [fenceLanguage] first: a fence with
 * attributes (```` ```python title="x" ````) is looked up as `python` (M-243). The PDF uses the same
 * aliases as the screen (M-178, NEW-22).
 */
class AliasGrammarLocator(
    private val delegate: GrammarLocator = GrammarLocatorDef(),
    private val aliases: Map<String, String> = ALIASES,
) : GrammarLocator {

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val name = fenceLanguage(language)
        return delegate.grammar(prism4j, aliases[name] ?: name)
    }

    override fun languages(): Set<String> = delegate.languages() + aliases.keys

    private companion object {
        val ALIASES = mapOf("diff" to "git", "patch" to "git")
    }
}
