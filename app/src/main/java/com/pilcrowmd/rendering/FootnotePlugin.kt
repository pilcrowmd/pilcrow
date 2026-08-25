// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import com.pilcrowmd.domain.markdown.FootnoteBlockParserFactory
import com.pilcrowmd.domain.markdown.FootnoteReference
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.parser.Parser

/**
 * Markwon glue for footnote definitions.
 *
 * Deliberately thin, and deliberately NOT where the work happens. The parsing primitive
 * ([FootnoteBlockParserFactory]) lives in the domain layer so the renderer and the search/TOC parse
 * share one definition of block structure; this only registers it on Markwon's parser. Unlike
 * [FrontmatterPlugin] there is no `processMarkdown` gate, because a footnote definition is bounded by
 * construction — it ends at the first non-blank unindented line — so it can never run away to EOF and
 * needs no whole-document lookahead. The source is never mutated (Safeguard 2).
 *
 * Reference resolution and numbering are NOT done here. They need the whole parsed document, which a
 * per-block `beforeRender` hook cannot see, so they run as a post-parse pass at the parse sites
 * (`Footnotes.transform`) — see `Footnotes.kt`.
 */
class FootnotePlugin : AbstractMarkwonPlugin() {

    override fun configureParser(builder: Parser.Builder) {
        builder.customBlockParserFactory(FootnoteBlockParserFactory())
    }

    /**
     * Paint a resolved reference as its ordinal, superscript and small, and make it tappable.
     *
     * The emitted text is the ordinal ALONE — `SearchMarkdownUseCase` models the node as exactly
     * those digits, and search's per-block text has to match what is painted.
     *
     * A marker that reaches here without a visitor would render as nothing at all (Markwon's
     * visitor falls through to `visitChildren`, and this node has no children), which is why the
     * registration and the transform ship together rather than one behind the other.
     */
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(FootnoteReference::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.builder().append(node.ordinal.toString())
            visitor.setSpans(
                start,
                arrayOf<Any>(
                    SuperscriptSpan(),
                    RelativeSizeSpan(FOOTNOTE_MARKER_SCALE),
                    FootnoteJumpSpan(node.definitionBlockIndex),
                ),
            )
        }
    }
}
