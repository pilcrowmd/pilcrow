// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.BlockLines
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.PlainTextChunks
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node

/**
 * Issue #15 (M-388, NEW-105): a Read <-> Edit switch lands on the same text. The reader counts in
 * blocks, one item each; the editor counts in characters. This translates between the two for the
 * way the document is shown: Markdown blocks ([ParseMarkdownHeadingsUseCase]) or the plain-text
 * chunks ([PlainTextChunks]), so both sides agree with what the reader actually paints.
 *
 * [blocks] are the reader model's blocks when the caller has them, so the document is not parsed
 * again; [plain] says the reader shows the text as chunks.
 */
class ReadEditLanding(private val headings: ParseMarkdownHeadingsUseCase) {

    /** A place in the reader: block [block], [fraction] of the way down it (0 = top, 1 = bottom). */
    data class ReaderPoint(val block: Int, val fraction: Float)

    /** The reader block holding the character at [offset], or null when there is none to point at. */
    fun blockForOffset(content: String, plain: Boolean, offset: Int, blocks: List<Node>? = null): Int? {
        if (!plain) return headings.readerBlockForOffset(content, offset, blocks)
        val starts = PlainTextChunks.chunkStarts(content)
        if (starts.isEmpty()) return null
        return chunkHolding(starts, offset.coerceIn(0, content.length))
    }

    /**
     * Where the editor lands for [point]: the start of the reader line [ReaderPoint.fraction] of the
     * way down block [ReaderPoint.block]. Prose blocks land on their first line; the lines of a plain
     * chunk or a code block are counted, because one such block can be a screen or more tall.
     * [keepGoing] bounds the Markdown search (see [ParseMarkdownHeadingsUseCase.offsetOfReaderBlock]).
     */
    fun offsetForBlock(
        content: String,
        plain: Boolean,
        point: ReaderPoint,
        blocks: List<Node>? = null,
        keepGoing: () -> Boolean = { true },
    ): Int? = if (plain) {
        offsetInChunk(content, point)
    } else {
        headings.offsetOfReaderBlock(content, point.block, blocks, keepGoing)?.let { start ->
            val code = blocks?.getOrNull(point.block) as? FencedCodeBlock
            if (code == null) start else offsetInCode(content, start, code, point.fraction)
        }
    }

    /**
     * Where [offsetForBlock] would probably land, from the blocks' sizes alone ([BlockLines]) with no
     * parsing, so it costs next to nothing: what a switch settles for when it cannot wait for the
     * search. Plain chunks are exact. A guess, which can be some lines off; null with no block.
     */
    fun estimatedOffsetForBlock(content: String, plain: Boolean, point: ReaderPoint, blocks: List<Node>): Int? {
        if (plain) return offsetInChunk(content, point)
        if (blocks.isEmpty()) return null
        val block = point.block.coerceIn(0, blocks.size - 1)
        val start = lineStartBelow(content, 0, content.length, BlockLines.starts(blocks)[block])
        val code = blocks[block] as? FencedCodeBlock
        return if (code == null) start else offsetInCode(content, start, code, point.fraction)
    }

    private fun offsetInChunk(content: String, point: ReaderPoint): Int? {
        val starts = PlainTextChunks.chunkStarts(content)
        if (starts.isEmpty()) return null
        val chunk = point.block.coerceIn(0, starts.size - 1)
        val end = if (chunk + 1 < starts.size) starts[chunk + 1] - 1 else content.length
        val lines = lineCount(content, starts[chunk], end)
        return lineStartBelow(content, starts[chunk], end, lineAtFraction(point.fraction, lines))
    }

    /**
     * The reader shows a code block's lines without its opening fence; a later piece of a long block
     * starts at its first code line, with no fence above it.
     */
    private fun offsetInCode(content: String, start: Int, code: FencedCodeBlock, fraction: Float): Int {
        val laterPiece = code is CodeBlockPiece && !code.isFirst
        val fenceText = code.fenceChar.toString().repeat(MIN_FENCE_LENGTH)
        val fence = if (!laterPiece && content.startsWith(fenceText, start.skipIndent(content))) 1 else 0
        val shownLines = code.literal.orEmpty().count { it == '\n' }.coerceAtLeast(1)
        return lineStartBelow(content, start, content.length, fence + lineAtFraction(fraction, shownLines))
    }

    private fun chunkHolding(starts: IntArray, offset: Int): Int {
        val found = starts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }

    /** Which of [lines] lines sits [fraction] of the way down them. */
    private fun lineAtFraction(fraction: Float, lines: Int): Int =
        (fraction.coerceIn(0f, 1f) * lines).toInt().coerceIn(0, lines - 1)

    private fun lineCount(content: String, from: Int, end: Int): Int {
        var lines = 1
        for (i in from until end) if (content[i] == '\n') lines++
        return lines
    }

    /** The start of the line [below] lines under the one starting at [from], never past [end]. */
    private fun lineStartBelow(content: String, from: Int, end: Int, below: Int): Int {
        var at = from
        repeat(below) {
            val next = content.indexOf('\n', at)
            if (next < 0 || next >= end) return at
            at = next + 1
        }
        return at
    }

    private fun Int.skipIndent(content: String): Int {
        var i = this
        while (i < content.length && content[i] == ' ') i++
        return i
    }
}

/** A code fence opens with at least three backticks or tildes (CommonMark). */
private const val MIN_FENCE_LENGTH = 3
