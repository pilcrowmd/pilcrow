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
 * that grammar already tokenises its `inserted`, `deleted` and `coord` (`@@ … @@`) lines.
 */
class AliasGrammarLocator(private val delegate: GrammarLocator = GrammarLocatorDef()) : GrammarLocator {

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? =
        delegate.grammar(prism4j, ALIASES[language] ?: language)

    override fun languages(): Set<String> = delegate.languages() + ALIASES.keys

    private companion object {
        val ALIASES = mapOf("diff" to "git", "patch" to "git")
    }
}
