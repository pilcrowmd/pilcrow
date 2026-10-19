// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Block
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Paragraph
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockParserFactory
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/** Four spaces of indent makes an indented code block. */
private const val CODE_INDENT = 4

/** The shortest fence: three colons, or three backticks or tildes for a code fence. */
private const val MIN_FENCE = 3

/**
 * Admonitions from other Markdown dialects, parsed into the same [CalloutBlock] a GitHub alert
 * becomes. **M-251.**
 *
 *  - Python-Markdown: `!!! type "Optional title"`, then a body indented four spaces, which may hold
 *    blank lines and several blocks. Material's collapsible `???`/`???+` is read the same way, shown
 *    open, as every callout is (M-227).
 *  - Fenced (markdown-it-container, Docusaurus): `::: type Optional title` … `:::`. The closing fence
 *    needs at least as many colons as the opening one. **An opening fence with no closing fence after
 *    it is not an admonition**: it and every line after it parse exactly as they would without this
 *    factory, so a stray `::: note` line cannot swallow the rest of the document. Which openers are
 *    closed is decided for the whole document up front, by [AdmonitionFences], because a block parser
 *    only ever sees one line.
 *
 * Only the types in [KINDS] qualify; any other type is left exactly as it parsed before (a paragraph),
 * so no new kind, colour or token is needed. Top-level only, like a GitHub alert: the box is a
 * background on one top-level adapter item, which a block inside a list or quote does not have.
 *
 * Why a block parser and not a rewrite after parsing, as [Callouts] is: an indented body with a blank
 * line in it has already become an indented code block by then. And the body is parsed as ordinary
 * child blocks, so the reader, the PDF and search paint and walk it exactly as a `> [!NOTE]` body. The
 * factory is registered on every parser (`CalloutPlugin` and the search/TOC parity parser), each with
 * the [AdmonitionFences] of the document it is parsing, which `ParseParityTest` checks. Pure
 * commonmark, no Markwon; the source is never touched (Safeguard 2).
 */
class AdmonitionBlockParserFactory(private val fences: () -> AdmonitionFences) : BlockParserFactory {

    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        // Four spaces of indent is an indented code block, which an admonition must not hijack.
        if (state.indent >= CODE_INDENT || !isTopLevel(matchedBlockParser)) return BlockStart.none()
        val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
        val parser = indented(line) ?: fenced(line, state.line.toString()) ?: return BlockStart.none()
        // The header line is consumed whole; the body starts on the next line.
        return BlockStart.of(parser).atIndex(state.line.length)
    }

    /** The document itself, or a paragraph directly in it, which an admonition may interrupt. */
    private fun isTopLevel(matched: MatchedBlockParser): Boolean {
        val block = matched.matchedBlockParser.block
        return block is Document || (block is Paragraph && block.parent is Document)
    }

    private fun indented(line: String): IndentedAdmonitionParser? {
        val (marker, type, title) = INDENTED_HEADER.matchEntire(line)?.destructured ?: return null
        val kind = KINDS[type.lowercase()] ?: return null
        return IndentedAdmonitionParser(CalloutBlock(kind, marker, title.takeIf { it.isNotEmpty() }))
    }

    private fun fenced(line: String, wholeLine: String): FencedAdmonitionParser? {
        val (block, fenceLength) = fencedHeader(line) ?: return null
        return if (fences().closes(wholeLine)) FencedAdmonitionParser(block, fenceLength) else null
    }

    internal companion object {
        // Groups: 1 the marker, 2 the type, 3 the quoted title's text.
        val INDENTED_HEADER = Regex("""^(!!!|\?\?\?\+?)[ \t]+([A-Za-z]+)(?:[ \t]+"(.*)")?[ \t]*$""")

        // Groups: 1 the fence, 2 the type, 3 the rest of the line as the title, trailing blanks
        // included: a lazy group before `[ \t]*$` backtracks quadratically over a long run of blanks.
        private val FENCED_HEADER = Regex("""^(:{3,})[ \t]*([A-Za-z]+)(?:[ \t]+(.*))?[ \t]*$""")

        /** Every admonition type that has a callout kind; anything else stays as it parses. */
        val KINDS: Map<String, CalloutKind> = buildMap {
            listOf("note", "info", "abstract", "summary", "todo", "question", "help", "faq")
                .forEach { put(it, CalloutKind.NOTE) }
            listOf("tip", "hint", "success").forEach { put(it, CalloutKind.TIP) }
            put("important", CalloutKind.IMPORTANT)
            listOf("warning", "attention").forEach { put(it, CalloutKind.WARNING) }
            listOf("danger", "error", "caution", "failure", "bug").forEach { put(it, CalloutKind.CAUTION) }
        }

        /**
         * [line] (from its first non-space character) as a fenced admonition's header: the block and
         * its fence length, or null. A line that also ends in `:::` (`::: Summary :::`) is not one.
         */
        fun fencedHeader(line: String): Pair<CalloutBlock, Int>? {
            val (fence, type, title) = FENCED_HEADER.matchEntire(line)?.destructured ?: return null
            if (line.substring(fence.length).trimEnd().endsWith(":::")) return null
            val kind = KINDS[type.lowercase()] ?: return null
            val trimmed = title.trimEnd(' ', '\t')
            return CalloutBlock(kind, fence, trimmed.takeIf { it.isNotEmpty() }) to fence.length
        }

        /** How many colons [rest] (a line from its first non-space character) closes with, or 0. */
        fun closingFenceLength(rest: String): Int {
            val colons = rest.takeWhile { it == ':' }.length
            return if (colons >= MIN_FENCE && rest.substring(colons).isBlank()) colons else 0
        }
    }
}

/**
 * Which `:::` opening fences of one document have a closing fence after them, worked out once before
 * the parse. The parser asks [closes] about each top-level opener it meets, in document order; an
 * opener with no closer stays prose.
 *
 * commonmark-java 0.13.0 gives a block parser no line numbers, so an opener is identified by its text,
 * matched forward from the last one asked about. The scan skips what the parser would not offer as a
 * top-level opener: lines inside a fenced code block, and lines inside a `:::` admonition it has
 * already opened. What it cannot see (a `:::` line inside a list item or an HTML block, a code fence
 * inside a list) can make it answer for an earlier identical line; the worst outcome is the old one,
 * an opener read as closed when it is not, which runs to the end of the document. Both parse sites
 * build it from the same text, so they always agree.
 *
 * One instance per parse: [closes] moves forward through the document.
 */
class AdmonitionFences private constructor(private val openers: List<Pair<String, Boolean>>) {

    private var next = 0

    /** Whether the opener [line] (the whole line, as the parser holds it) has a closing fence. */
    internal fun closes(line: String): Boolean {
        for (i in next until openers.size) {
            if (openers[i].first == line) {
                next = i + 1
                return openers[i].second
            }
        }
        return false
    }

    companion object {
        private val LINE_BREAK = Regex("\r\n|\r|\n")

        /** The answers for [markdown], split into lines as commonmark splits them. */
        fun scan(markdown: String): AdmonitionFences {
            val lines = markdown.split(LINE_BREAK)
            val inCode = codeFenceLines(lines)
            val closer = IntArray(lines.size) { i ->
                if (inCode[i]) 0 else unindented(lines[i])?.let(::closeLength) ?: 0
            }
            // The longest closing fence at or after each line: an opener is closed by one long enough.
            val longestFrom = IntArray(lines.size + 1)
            for (i in lines.indices.reversed()) longestFrom[i] = maxOf(closer[i], longestFrom[i + 1])
            val openers = mutableListOf<Pair<String, Boolean>>()
            var open = 0
            lines.forEachIndexed { i, line ->
                when {
                    inCode[i] -> Unit
                    // Inside an admonition the parser never offers an opener; the first long enough closer ends it.
                    open > 0 -> if (closer[i] >= open) open = 0
                    else -> unindented(line)?.let(AdmonitionBlockParserFactory::fencedHeader)?.let { (_, fence) ->
                        val closed = longestFrom[i + 1] >= fence
                        openers += line to closed
                        if (closed) open = fence
                    }
                }
            }
            return AdmonitionFences(openers)
        }

        private fun closeLength(rest: String) = AdmonitionBlockParserFactory.closingFenceLength(rest)

        /** [line] from its first non-space character, or null when it is indented as code. */
        private fun unindented(line: String): String? {
            val rest = line.trimStart(' ')
            return rest.takeIf { line.length - it.length < CODE_INDENT }
        }

        /** Which lines belong to a fenced code block, its fence lines included. */
        private fun codeFenceLines(lines: List<String>): BooleanArray {
            val inCode = BooleanArray(lines.size)
            var fence: String? = null
            lines.forEachIndexed { i, line ->
                val rest = unindented(line)
                val open = fence
                if (open != null) {
                    inCode[i] = true
                    if (rest != null && rest.startsWith(open) && rest.trimStart(open[0]).isBlank()) fence = null
                } else {
                    fence = rest?.let(::openingCodeFence)
                    inCode[i] = fence != null
                }
            }
            return inCode
        }

        /** The fence [rest] opens a fenced code block with (three or more backticks or tildes), or null. */
        private fun openingCodeFence(rest: String): String? {
            val char = rest.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
            val run = rest.takeWhile { it == char }
            // A backtick fence's info string cannot hold a backtick.
            val badInfo = char == '`' && rest.substring(run.length).contains('`')
            return run.takeIf { it.length >= MIN_FENCE && !badInfo }
        }
    }
}

/** A container for the body of an [AdmonitionBlockParserFactory] block; its children parse as usual. */
private abstract class AdmonitionParser(private val block: CalloutBlock) : AbstractBlockParser() {

    override fun getBlock(): Block = block

    override fun isContainer(): Boolean = true

    override fun canContain(childBlock: Block): Boolean = true
}

/**
 * `!!! type` and the lines indented under it. Bounded by construction, like a footnote definition:
 * it ends at the first non-blank line indented less than four spaces.
 */
private class IndentedAdmonitionParser(block: CalloutBlock) : AdmonitionParser(block) {

    override fun tryContinue(state: ParserState): BlockContinue? = when {
        // A blank line does not end the body: its paragraphs are separated by one.
        state.isBlank -> BlockContinue.atIndex(state.nextNonSpaceIndex)
        state.indent >= BODY_INDENT -> BlockContinue.atColumn(state.column + BODY_INDENT)
        else -> BlockContinue.none()
    }

    private companion object {
        const val BODY_INDENT = 4
    }
}

/** `::: type` up to a closing `:::` at least [fenceLength] colons long, which [AdmonitionFences] promised. */
private class FencedAdmonitionParser(block: CalloutBlock, private val fenceLength: Int) : AdmonitionParser(block) {

    private var closed = false

    override fun tryContinue(state: ParserState): BlockContinue? {
        if (closed) return BlockContinue.none()
        val rest = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
        // A `:::` line inside a code block in the body is code, not the closing fence.
        val inCode = state.activeBlockParser.block is FencedCodeBlock
        val isFence = state.indent < CODE_INDENT && AdmonitionBlockParserFactory.closingFenceLength(rest) >= fenceLength
        if (isFence && !inCode) {
            // commonmark-java 0.13.0 cannot finish a container that still has an open child (its
            // `finalize` leaves both on the stack), so the fence line is consumed as an empty line,
            // which closes any open paragraph inside, and the block ends on the line after it.
            closed = true
            return BlockContinue.atIndex(state.line.length)
        }
        return BlockContinue.atIndex(state.index)
    }
}
