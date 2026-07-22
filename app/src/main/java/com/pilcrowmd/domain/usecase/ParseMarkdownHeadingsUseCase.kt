// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.FrontmatterBlockParserFactory
import com.pilcrowmd.domain.markdown.FrontmatterDetector
import com.pilcrowmd.domain.model.HeadingNode
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Document
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * UseCase for parsing markdown and extracting headings / block offsets.
 * Stateless; encapsulates the commonmark parsing logic extracted from MarkdownViewModel.
 *
 * Block model parity with the renderer. Both the TOC (adapter positions) and search
 * (block start offsets for scroll-to-match) must agree with the `MarkwonAdapter`, which renders
 * one RecyclerView item per top-level node of `markwon.parse(content)`. So this use case parses
 * with the same block-structuring extensions as MarkwonRenderer — GFM tables (a table
 * interrupts a paragraph identically), strikethrough, and the shared frontmatter `BlockParser`
 * (leading `---…---` is one `yaml` block, not a setext heading — which also removes it from the
 * TOC) — so the top-level node sequence maps 1:1 to the adapter's items.
 *
 * commonmark-java 0.13.0 carries no source spans, so a search match's raw-source offset is
 * reconstructed by [SearchMarkdownUseCase] from the document this use case parses (see [parseDocument]).
 */
class ParseMarkdownHeadingsUseCase {

    /**
     * A commonmark parser whose top-level block sequence matches the MarkwonAdapter's item list.
     * The frontmatter factory is gated on this exact [content] (same lookahead the renderer uses),
     * so an eligible leading `---…---` becomes a single `yaml` block here too.
     */
    private fun parityParser(content: String): Parser {
        val frontmatterEligible = FrontmatterDetector.hasWellFormedFrontmatter(content)
        return Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .customBlockParserFactory(FrontmatterBlockParserFactory { frontmatterEligible })
            .build()
    }

    /**
     * Parse [content] into a commonmark [Document] whose top-level children map 1:1 to the
     * MarkwonAdapter's RecyclerView items (same parity parser as [extractHeadings]). Shared with
     * [SearchMarkdownUseCase] so search walks the exact block sequence the renderer paints.
     * Returns `null` on a parse failure (graceful degradation — no search rather than a crash).
     */
    internal fun parseDocument(content: String): Document? = try {
        parityParser(content).parse(content) as? Document
    } catch (ignored: Exception) {
        null
    }

    /**
     * Extract all headings from markdown content.
     * Returns a list of HeadingNode with level, text, and adapter position.
     */
    fun extractHeadings(content: String): List<HeadingNode> {
        return try {
            val doc = parityParser(content).parse(content)

            val headings = mutableListOf<HeadingNode>()
            var blockIndex = 0
            var node = doc.firstChild

            while (node != null) {
                if (node is Heading) {
                    headings.add(
                        HeadingNode(
                            level = node.level,
                            text = extractTextFromNode(node),
                            adapterPosition = blockIndex,
                        ),
                    )
                }
                blockIndex++
                node = node.next
            }

            headings
        } catch (ignored: Exception) {
            // Graceful degradation: a parse failure yields no TOC rather than crashing.
            emptyList()
        }
    }

    /**
     * Extract all text content from a node (used by extractHeadings to get heading text).
     */
    private fun extractTextFromNode(node: Node): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }
        })
        return sb.toString()
    }
}
