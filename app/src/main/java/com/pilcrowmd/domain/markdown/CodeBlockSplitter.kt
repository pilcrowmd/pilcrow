// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node

/**
 * One long code block, cut by [CodeBlockSplitter]: its whole [literal] (what Copy copies and what is
 * coloured, in one pass, so a string or comment that runs over a cut is coloured as one) and where
 * each piece starts in it. Compared by identity.
 */
class CodeBlockGroup internal constructor(val info: String?, val literal: String, private val starts: IntArray) {
    val pieceCount: Int get() = starts.size

    /** Where piece [index] starts in [literal]. */
    fun start(index: Int): Int = starts[index]

    /** Where piece [index] ends in [literal] (exclusive). */
    fun end(index: Int): Int = if (index + 1 < starts.size) starts[index + 1] else literal.length

    /** The piece holding the character at [offset] of [literal]. */
    fun pieceAt(offset: Int): Int {
        val found = starts.binarySearch(offset.coerceIn(0, literal.length))
        return if (found >= 0) found else -found - 2
    }
}

/**
 * A piece of a long code block: an ordinary [FencedCodeBlock] whose literal is lines
 * [CodeBlockGroup.start] to [CodeBlockGroup.end] of the [group]'s, so search, the headings drawer and
 * every other walker see a code block and count it as one. Only the reader needs [group] and [index].
 */
class CodeBlockPiece internal constructor(val group: CodeBlockGroup, val index: Int, original: FencedCodeBlock) :
    FencedCodeBlock() {
    init {
        fenceChar = original.fenceChar
        fenceLength = original.fenceLength
        fenceIndent = original.fenceIndent
        info = original.info
        literal = group.literal.substring(group.start(index), group.end(index))
    }

    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == group.pieceCount - 1
}

/**
 * Cuts a long top-level code block into [CodeBlockPiece]s of about [PIECE_LINES] lines, so the reader
 * lays out, colours and records a bounded block per frame instead of one TextView holding the whole
 * block (issue #17: drawing a 100 KB block held the main thread over a second, with or without its
 * colours). Display only: the text in memory and the saved bytes are untouched (Safeguard 2), and the
 * PDF keeps the block whole.
 *
 * Only a block longer than [LONG_BLOCK_CHARS] or [LONG_BLOCK_LINES] is cut; a shorter one stays one
 * reader block, coloured as before. Front matter (drawn as its own card) and a Mermaid fence (drawn
 * as a picture) are never cut. Cuts fall only at line ends, so a single line longer than a piece stays
 * whole. Every cut is decided from the block's start, so a prefix of a block is cut at the same places
 * as the whole block (the Edit to Read caret mapping relies on this).
 */
object CodeBlockSplitter {
    /** A block longer than this is cut. Prism4j's colour-at-bind limit ([LONG_BLOCK_CHARS] chars, NEW-99a). */
    const val LONG_BLOCK_CHARS = 10_000

    /** A block with more lines than this is cut, whatever its length (the old TextMate line limit). */
    const val LONG_BLOCK_LINES = 2_000

    /** Lines per piece: about two screens of code on a phone. */
    const val PIECE_LINES = 100

    /** A piece also ends at the first line end past this many chars, so long lines keep pieces small. */
    const val PIECE_CHARS = 8_000

    /** Cut every long top-level code block of [document] in place. */
    fun split(document: Node) {
        var block = document.firstChild
        while (block != null) {
            val next = block.next
            if (block is FencedCodeBlock && block !is CodeBlockPiece && isCut(block)) splitBlock(block)
            block = next
        }
    }

    private fun isCut(block: FencedCodeBlock): Boolean {
        if (block.isFrontmatter() || fenceLanguage(block.info) == MERMAID_LANGUAGE) return false
        val literal = block.literal ?: return false
        return literal.length > LONG_BLOCK_CHARS || lineCount(literal) > LONG_BLOCK_LINES
    }

    private fun splitBlock(block: FencedCodeBlock) {
        val literal = block.literal
        val group = CodeBlockGroup(block.info, literal, pieceStarts(literal))
        var anchor: Node = block
        for (index in 0 until group.pieceCount) {
            val piece = CodeBlockPiece(group, index, block)
            anchor.insertAfter(piece)
            anchor = piece
        }
        block.unlink()
    }

    /** Piece starts: 0, then the start of the line after every [PIECE_LINES] lines or [PIECE_CHARS] chars. */
    internal fun pieceStarts(literal: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        var pieceStart = 0
        var lines = 0
        var lineEnd = literal.indexOf('\n')
        while (lineEnd >= 0 && lineEnd + 1 < literal.length) {
            lines++
            val next = lineEnd + 1
            if (lines >= PIECE_LINES || next - pieceStart >= PIECE_CHARS) {
                starts += next
                pieceStart = next
                lines = 0
            }
            lineEnd = literal.indexOf('\n', next)
        }
        return starts.toIntArray()
    }

    private fun lineCount(literal: String): Int {
        var count = 0
        var at = literal.indexOf('\n')
        while (at >= 0) {
            count++
            at = literal.indexOf('\n', at + 1)
        }
        return count
    }
}
