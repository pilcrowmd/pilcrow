// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.FrontmatterBlockParserFactory
import com.pilcrowmd.domain.markdown.FrontmatterDetector
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.parser.Parser

/**
 * Markwon glue for YAML frontmatter rendering.
 *
 * The pure commonmark parsing primitives ([FrontmatterDetector], [FrontmatterBlockParserFactory])
 * live in the domain layer (`com.pilcrowmd.domain.markdown.Frontmatter`) so the renderer and the
 * search/TOC parse share one definition of block structure. This plugin only wires them into the
 * Markwon parser: detect eligibility per render (without mutating the source), then expose it to
 * the block-parser factory. `processMarkdown` runs immediately before `parse` on the same thread,
 * so the [ThreadLocal] gate is correct even if a background pre-warm render overlaps.
 */
class FrontmatterPlugin : AbstractMarkwonPlugin() {

    private val eligible = ThreadLocal.withInitial { false }

    override fun processMarkdown(markdown: String): String {
        eligible.set(FrontmatterDetector.hasWellFormedFrontmatter(markdown))
        return markdown // never mutate — keeps char offsets aligned with the editor (Safeguard 2)
    }

    override fun configureParser(builder: Parser.Builder) {
        builder.customBlockParserFactory(FrontmatterBlockParserFactory { eligible.get() == true })
    }
}
