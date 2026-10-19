// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.AdmonitionBlockParserFactory
import com.pilcrowmd.domain.markdown.AdmonitionFences
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.CodeBlockSplitter
import com.pilcrowmd.domain.markdown.FootnoteBlockParserFactory
import com.pilcrowmd.domain.markdown.FrontmatterBlockParserFactory
import com.pilcrowmd.domain.markdown.FrontmatterDetector
import com.pilcrowmd.domain.markdown.NestingLimit
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.model.HeadingNode
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
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
 * TOC) — so the top-level node sequence maps 1:1 to the adapter's items once numbered by
 * [AdapterBlocks.of], which drops the link reference definitions the adapter's reducer drops (M-214).
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
    private fun parityParser(content: String, fences: AdmonitionFences = AdmonitionFences.scan(content)): Parser {
        val frontmatterEligible = FrontmatterDetector.hasWellFormedFrontmatter(content)
        return Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .customBlockParserFactory(FrontmatterBlockParserFactory { frontmatterEligible })
            // Same factory the renderer registers (FootnotePlugin) — shared so the top-level block
            // sequences stay 1:1. ParseParityTest fails if one side ever gains it without the other.
            .customBlockParserFactory(FootnoteBlockParserFactory())
            // M-251: `!!!` and `:::` admonitions, the same factory CalloutPlugin registers, with the
            // same per-document answer about which `:::` openers are closed.
            .customBlockParserFactory(AdmonitionBlockParserFactory { fences })
            // NEW-11: a tree too deep to walk comes back empty, so the TOC finds nothing in it instead
            // of overflowing the stack. The reader shows that document as plain text, and search
            // reads it the same way.
            .postProcessor(NestingLimit.postProcessor)
            .build()
    }

    /**
     * Parse [content] into a commonmark [Document] whose top-level children map 1:1 to the
     * MarkwonAdapter's RecyclerView items (same parity parser as [extractHeadings]). Shared with
     * [SearchMarkdownUseCase] so search walks the exact block sequence the renderer paints.
     * Returns `null` on a parse failure (graceful degradation — no search rather than a crash).
     * [fences]: which `:::` openers close; [readerBlockForOffset] passes the whole document's for a prefix.
     */
    internal fun parseDocument(content: String, fences: AdmonitionFences = AdmonitionFences.scan(content)): Document? =
        try {
            // The SAME post-parse pass the renderer applies (the PDF skips only the long-paragraph
            // split), so search walks the exact node tree that gets painted, footnote markers and the
            // pieces of a split paragraph included (M-328).
            (ReaderDocument.transform(parityParser(content, fences).parse(content)) as? Document)
        } catch (ignored: Exception) {
            null
        }

    /**
     * The parity parser's raw tree for [content] — no post-parse pass. The default parse for a
     * `PrepareReaderModelUseCase` built without the reader's own parser (tests); same block sequence.
     */
    internal fun parseTree(content: String): Node = parityParser(content).parse(content)

    /**
     * The reader adapter position of the block holding the character at [offset] (an editor caret),
     * or `null` when there is no block to point at: an empty document, or one the reader shows as
     * plain text (the nesting-limit fallback parses to an empty document too).
     *
     * commonmark 0.13 carries no source spans, so a block cannot be asked where it started. Instead
     * the document is parsed only up to the end of [offset]'s line: that prefix has exactly as many
     * top-level blocks as the full document has up to the caret's block, so the last one is the
     * caret's. The count is clamped into the full document's block range.
     *
     * Known approximations: a caret on a blank line, or on a link reference definition (which takes
     * no adapter position, [AdapterBlocks]), maps to the block before it. A caret inside frontmatter
     * maps to block 0, because a half-cut `---` fence is no longer frontmatter in the prefix parse.
     * A caret on a GFM table header row that directly follows a paragraph (no blank line) maps to
     * that paragraph. Display math `$$…$$` containing a blank line counts as several blocks in the
     * parity parser (`parseDocument` omits ext-latex's block parser), so carets after it map too
     * high; Contents and search inherit the same limit.
     */
    fun readerBlockForOffset(content: String, offset: Int): Int? {
        val full = adapterBlocks(content)
        val fullBlocks = full.size
        if (fullBlocks == 0) return null
        val caret = offset.coerceIn(0, content.length)
        val lineEnd = content.indexOf('\n', caret).let { if (it < 0) content.length else it }
        val prefix = content.substring(0, lineEnd)
        val insideFrontmatter = FrontmatterDetector.hasWellFormedFrontmatter(content) &&
            !FrontmatterDetector.hasWellFormedFrontmatter(prefix)
        if (insideFrontmatter) return 0
        // A `:::` block is closed by a fence that may lie past the caret, so the prefix is parsed with the
        // whole document's answer about which openers close (M-251); it meets the same openers in order.
        val prefixBlocks = adapterBlocks(prefix, AdmonitionFences.scan(content))
        return (codePieceForCaret(prefixBlocks, full) ?: (prefixBlocks.size - 1)).coerceIn(0, fullBlocks - 1)
    }

    /**
     * Issue #17: the caret's block is the prefix's last one, and when it is a code block the prefix cut
     * it short. Short, it may not be long enough to be cut into pieces, while the whole block is, so the
     * prefix's block count would point at the block's first piece. Pieces are cut from the block's start
     * ([CodeBlockSplitter]), so the piece holding the caret is the whole block's piece at the end of the
     * prefix's part of it. Null when the caret is not in a code block that the whole document cuts.
     */
    private fun codePieceForCaret(prefixBlocks: List<Node>, full: List<Node>): Int? {
        val last = prefixBlocks.lastOrNull() as? FencedCodeBlock ?: return null
        val piece = last as? CodeBlockPiece
        val start = prefixBlocks.size - 1 - (piece?.index ?: 0)
        val group = (full.getOrNull(start) as? CodeBlockPiece)?.takeIf { it.isFirst }?.group ?: return null
        val shown = (piece?.group?.literal ?: last.literal).orEmpty()
        return start + group.pieceAt(shown.length - 1)
    }

    /** The reader adapter items [content] makes; empty for an empty, too-deep or unparseable document. */
    private fun adapterBlocks(content: String, fences: AdmonitionFences = AdmonitionFences.scan(content)): List<Node> =
        parseDocument(content, fences)?.let { AdapterBlocks.of(it) }.orEmpty()

    /**
     * Extract all headings from markdown content.
     * Returns a list of HeadingNode with level, text, and adapter position.
     */
    fun extractHeadings(content: String): List<HeadingNode> {
        return try {
            val doc = ReaderDocument.transform(parityParser(content).parse(content))
            headingsOf(AdapterBlocks.of(doc))
        } catch (ignored: Exception) {
            // Graceful degradation: a parse failure yields no TOC rather than crashing.
            emptyList()
        }
    }

    /**
     * The headings among a document's top-level [blocks] — numbered by [AdapterBlocks.of], so link
     * reference definitions are already gone (M-214) — each with its index in that list as the
     * adapter position. Shared with `PrepareReaderModelUseCase`, which takes the TOC from the reader's
     * own blocks, so the two can never disagree on a position (M-179: one parse, not two).
     *
     * [inlineText] reads an inline custom node as text — the reader's tree carries each inline
     * formula as one, and the TOC keeps its source without the delimiters.
     * Null leaves the node to its children, as before.
     */
    fun headingsOf(blocks: List<Node>, inlineText: (Node) -> String? = { null }): List<HeadingNode> =
        blocks.mapIndexedNotNull { index, node ->
            (node as? Heading)?.let {
                HeadingNode(level = it.level, text = extractTextFromNode(it, inlineText), adapterPosition = index)
            }
        }

    /**
     * Extract all text content from a node (used by extractHeadings to get heading text):
     * text, inline code literals, and a single space for each line break (M-264).
     */
    private fun extractTextFromNode(node: Node, inlineText: (Node) -> String? = { null }): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                sb.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                sb.append(' ')
            }

            override fun visit(customNode: CustomNode) {
                inlineText(customNode)?.let { sb.append(it) } ?: visitChildren(customNode)
            }
        })
        return sb.toString()
    }
}
