// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak

/**
 * Issue #15: a guess of the source line each reader block starts on, worked out from the blocks alone
 * (commonmark 0.13 keeps no source spans). It seeds the search that finds a block's true line, so the
 * search starts close and a search cut short is still near. It is a guess: blank-line runs, a setext
 * heading's underline or a table's delimiter row are not counted, and a loose list's blank lines are
 * not either.
 */
internal object BlockLines {

    /** Per block, the estimated 0-based line it starts on. */
    fun starts(blocks: List<Node>): IntArray {
        val starts = IntArray(blocks.size)
        var line = 0
        for ((i, block) in blocks.withIndex()) {
            starts[i] = line
            line += lines(block) + gapAfter(block)
        }
        return starts
    }

    /** Blocks are separated by one blank line, except the pieces of one long code block. */
    private fun gapAfter(block: Node): Int = if (block is CodeBlockPiece && !block.isLast) 0 else 1

    /** The lines [block] covers: a code block's lines and fences, a paragraph's breaks, a container's children. */
    private fun lines(block: Node): Int = when (block) {
        is CodeBlockPiece -> codeLines(block) + (if (block.isFirst) 1 else 0) + (if (block.isLast) 1 else 0)
        is FencedCodeBlock -> codeLines(block) + 2
        is IndentedCodeBlock -> block.literal.orEmpty().count { it == '\n' }.coerceAtLeast(1)
        is HtmlBlock -> block.literal.orEmpty().count { it == '\n' }.coerceAtLeast(1)
        is Paragraph -> 1 + lineBreaks(block)
        is BlockQuote -> childLines(block)
        else -> if (block.firstChild == null) 1 else childLines(block)
    }

    private fun codeLines(block: FencedCodeBlock): Int = block.literal.orEmpty().count { it == '\n' }

    private fun childLines(node: Node): Int {
        var sum = 0
        var child = node.firstChild
        while (child != null) {
            sum += lines(child)
            child = child.next
        }
        return sum.coerceAtLeast(1)
    }

    private fun lineBreaks(node: Node): Int {
        var count = 0
        var child = node.firstChild
        while (child != null) {
            if (child is SoftLineBreak || child is HardLineBreak) count++
            count += lineBreaks(child)
            child = child.next
        }
        return count
    }
}
