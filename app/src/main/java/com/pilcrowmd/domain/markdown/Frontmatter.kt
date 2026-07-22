// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Block
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockParserFactory
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/**
 * YAML frontmatter parsing primitives, the offset-safe way.
 *
 * Frontmatter (`---…---` at document start) is parsed into a `FencedCodeBlock(info="yaml")` node
 * straight from a custom commonmark [BlockParser] — the source is never rewritten, so TOC, search,
 * and the Sora editor all see the identical string end-to-end (Safeguard 2). These primitives are
 * pure commonmark-java (no Android / Markwon), so they live in the domain layer and are shared
 * by both the renderer's `FrontmatterPlugin` (presentation) and the search/TOC parse
 * (`ParseMarkdownHeadingsUseCase`), keeping a single source of truth for block structure
 * (same parser config ⇒ block indices align 1:1 with the MarkwonAdapter).
 *
 * Rollback safety: commonmark-java 0.13.0 has no block-parser rollback, so a naive greedy parser
 * would swallow the whole document when the closing `---` is missing. We sidestep this with a
 * full-string lookahead in [FrontmatterDetector] that decides eligibility; the factory's `tryStart`
 * returns [BlockStart.none] when no well-formed frontmatter exists, so the CommonMark core correctly
 * evaluates a leading `---` as an ordinary thematic break.
 */

/** Pure, JVM-testable detection of well-formed frontmatter. */
object FrontmatterDetector {

    /**
     * Well-formed frontmatter = line 1 is exactly `---`, then zero or more body lines, then a
     * line that is exactly `---`. Anchored to the document start (no `MULTILINE`, so `^` pins to
     * index 0). Trailing spaces/tabs on a delimiter line are tolerated; CRLF and LF both match.
     */
    private val FRONTMATTER = Regex("""^---[ \t]*\r?\n([\s\S]*?\r?\n)?---[ \t]*(\r?\n|$)""")

    fun hasWellFormedFrontmatter(markdown: String): Boolean = FRONTMATTER.find(markdown)?.range?.first == 0
}

/**
 * Leaf block parser that accumulates the lines between the opening and closing `---` fences into
 * a `FencedCodeBlock` with info `"yaml"`. Only ever started when [FrontmatterDetector] has already
 * confirmed a closing fence exists, so it cannot run away to EOF.
 */
private class FrontmatterBlockParser : AbstractBlockParser() {

    private val block = FencedCodeBlock()
    private val body = StringBuilder()
    private var sawOpeningFence = false

    override fun getBlock(): Block = block

    override fun tryContinue(state: ParserState): BlockContinue = if (sawOpeningFence && isFence(state.line)) {
        // Closing fence: finish the block and consume the line (it is not added as content).
        BlockContinue.finished()
    } else {
        // Keep consuming body lines verbatim.
        BlockContinue.atIndex(state.index)
    }

    override fun addLine(line: CharSequence) {
        if (!sawOpeningFence) {
            // The first line handed to us is the opening `---`; drop it from the body.
            sawOpeningFence = true
            return
        }
        body.append(line).append('\n')
    }

    override fun closeBlock() {
        block.info = "yaml"
        block.literal = body.toString()
    }

    private fun isFence(line: CharSequence): Boolean = line.toString().trimEnd() == "---"
}

/**
 * Factory gated by [enabled] (driven by a per-render/per-parse lookahead). Starts a
 * [FrontmatterBlockParser] only for a `---` line that is the very first block of the document
 * (line 1, column 0).
 */
class FrontmatterBlockParserFactory(private val enabled: () -> Boolean) : BlockParserFactory {

    // commonmark-java 0.13.0 uses `null` as the "no block" sentinel (BlockStart.none() == null),
    // so the return type must be nullable.
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        val parent = matchedBlockParser.matchedBlockParser.block
        // Start only for an unindented `---` that is the document's very first block (line 1).
        val opensFrontmatter = enabled() &&
            parent is Document &&
            parent.firstChild == null &&
            state.indent == 0 &&
            state.line.subSequence(state.index, state.line.length).toString().trimEnd() == "---"
        return if (opensFrontmatter) {
            BlockStart.of(FrontmatterBlockParser()).atIndex(state.index)
        } else {
            BlockStart.none()
        }
    }
}

/**
 * One displayed metadata row: a [key] label and its [value]. A line with no usable key (no colon,
 * or a leading colon) yields an empty [key] and the whole line as [value] (rendered label-less).
 * Backs the reader's metadata card and the PDF header.
 */
data class FrontmatterField(val key: String, val value: String)

/**
 * Split raw YAML frontmatter [literal] into displayable [FrontmatterField]s — one per non-blank
 * line, key = text before the first colon, value = the remainder (colons in the value are kept).
 * Pure (no Android / YAML lib): handles arbitrary keys for the metadata card and degrades a
 * non-`key: value` line to a label-less value rather than failing (Safeguard 3, graceful).
 */
fun parseFrontmatterFields(literal: String): List<FrontmatterField> = literal.lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
        val colon = line.indexOf(':')
        if (colon > 0) {
            FrontmatterField(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        } else {
            FrontmatterField("", line)
        }
    }
