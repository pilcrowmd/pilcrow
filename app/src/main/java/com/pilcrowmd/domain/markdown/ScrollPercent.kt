// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import kotlin.math.roundToInt

/**
 * The scroll percent shown while reading a long document, worked out from the text and not from
 * measured heights: the reader lays out only the blocks near the screen, so the full height is not
 * known. Each block's share of the text is counted once when the model is prepared ([textStarts]),
 * and the number is then a lookup plus one division per scroll event.
 *
 * Pure arithmetic over a block list; nothing here touches a view or writes to the document.
 */
object ScrollPercent {

    /** A heading, image or rule has little text but still takes about one line, so it counts as this much. */
    const val MIN_BLOCK_WEIGHT = 40

    /** A document is "long" when it is taller than this many screens. */
    const val SCREENS = 6

    private const val END = 100

    /**
     * Where each block starts in the text: `starts[i]` is the weight of the blocks before block `i`,
     * and the last entry is the total. Size is `blocks.size + 1`.
     */
    fun textStarts(blocks: List<Node>): IntArray = startsOf(blocks.size) { weightOf(blocks[it]) }

    /** [textStarts] for a plain-text document, whose blocks are the chunks with these [literals]. */
    fun textStartsOf(literals: List<String>): IntArray =
        startsOf(literals.size) { maxOf(MIN_BLOCK_WEIGHT, literals[it].length) }

    /**
     * [starts] with the blocks for which [hidden] is true counted as nothing: a block inside a closed
     * `<details>` section holds text but takes no room, so it must not weigh on the total or on the
     * share above the screen. Same shape as [starts], so [percent] takes it as is.
     */
    fun withoutHidden(starts: IntArray, hidden: (Int) -> Boolean): IntArray {
        val blocks = starts.size - 1
        if (blocks <= 0) return starts
        return startsOf(blocks) { if (hidden(it)) 0 else starts[it + 1] - starts[it] }
    }

    /**
     * The percent to show. [firstBlock] is the first visible block and [fractionPastFirst] how far
     * (0 to 1) its top is above the top of the list. The very top is always 0 and the very end
     * always 100 (the end wins if both hold); in between the answer stays within 1 to 99, so a
     * rounding never says "done" or "not started" while there is more to scroll.
     */
    fun percent(starts: IntArray, firstBlock: Int, fractionPastFirst: Float, atTop: Boolean, atEnd: Boolean): Int {
        if (atEnd) return END
        if (atTop) return 0
        val blocks = starts.size - 1
        val total = if (blocks > 0) starts[blocks] else 0
        if (total <= 0) return 0
        val first = firstBlock.coerceIn(0, blocks - 1)
        val fraction = fractionPastFirst.coerceIn(0f, 1f)
        val passed = starts[first] + fraction.toDouble() * (starts[first + 1] - starts[first])
        return (passed * END / total).roundToInt().coerceIn(1, END - 1)
    }

    /**
     * What [estimatedRows] needs of each block, counted once with the model: [chars] the characters
     * of prose that wraps to the list width, [lines] the lines that each take a row of their own.
     */
    class RowCounts(val chars: IntArray, val lines: IntArray)

    /**
     * Row counts for Markdown [blocks]. Prose counts its text and inline code. A code block counts
     * its lines (a diagram fence, drawn as a picture, counts none), and a table its rows. HTML, and
     * a paragraph holding a `$` (it may be a formula, drawn as one picture), count nothing: they
     * can paint less than their source, and the estimate must never be too tall.
     */
    fun rowCounts(blocks: List<Node>): RowCounts {
        val chars = IntArray(blocks.size)
        val lines = IntArray(blocks.size)
        for (i in blocks.indices) {
            val counter = RowCounter()
            blocks[i].accept(counter)
            chars[i] = if (counter.mayBeMath) 0 else counter.chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            lines[i] = counter.lines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return RowCounts(chars, lines)
    }

    /** [rowCounts] for a plain-text document: each chunk is its lines, one row apiece at least. */
    fun rowCountsOf(literals: List<String>): RowCounts =
        RowCounts(IntArray(literals.size), IntArray(literals.size) { linesIn(literals[it]) })

    /**
     * How many text rows the document takes at [charsPerLine] characters to a row, counting a block
     * as the larger of its [RowCounts.lines] and its prose wrapped to that width, with no gap rows.
     * A block for which [hidden] is true (inside a closed `<details>` section) takes no room.
     */
    fun estimatedRows(counts: RowCounts, hidden: (Int) -> Boolean, charsPerLine: Int): Long {
        val perLine = maxOf(1, charsPerLine).toLong()
        var rows = 0L
        for (i in counts.chars.indices) {
            if (hidden(i)) continue
            val wrapped = (counts.chars[i] + perLine - 1) / perLine
            rows += maxOf(counts.lines[i].toLong(), wrapped)
        }
        return rows
    }

    /**
     * Whether the document is taller than [SCREENS] screens: its [estimatedRows] times [lineHeightPx]
     * against [SCREENS] viewports of [viewportHeightPx]. This is a lower bound: wrapping, headings,
     * margins, pictures and wider text only add height, so a document judged long is long, and a
     * short document is never judged long. [lineHeightPx] must be the shortest line the reader
     * paints. False for no blocks or a size of zero.
     */
    fun isLong(
        counts: RowCounts,
        hidden: (Int) -> Boolean,
        charsPerLine: Int,
        lineHeightPx: Int,
        viewportHeightPx: Int,
    ): Boolean {
        if (counts.chars.isEmpty() || charsPerLine <= 0) return false
        if (lineHeightPx <= 0 || viewportHeightPx <= 0) return false
        val rows = estimatedRows(counts, hidden, charsPerLine)
        return rows * lineHeightPx > SCREENS.toLong() * viewportHeightPx
    }

    private fun startsOf(count: Int, weight: (Int) -> Int): IntArray {
        val starts = IntArray(count + 1)
        var sum = 0L
        for (i in 0 until count) {
            starts[i] = sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            sum += weight(i)
        }
        starts[count] = sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return starts
    }

    private fun weightOf(block: Node): Int {
        val counter = LiteralCounter()
        block.accept(counter)
        return maxOf(MIN_BLOCK_WEIGHT.toLong(), counter.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Adds up the length of every literal under a block. */
    private class LiteralCounter : AbstractVisitor() {
        var length = 0L
            private set

        override fun visit(text: Text) {
            length += text.literal.length
        }

        override fun visit(code: Code) {
            length += code.literal.length
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            length += fencedCodeBlock.literal.length
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            length += indentedCodeBlock.literal.length
        }

        override fun visit(htmlBlock: HtmlBlock) {
            length += htmlBlock.literal.length
        }

        override fun visit(htmlInline: HtmlInline) {
            length += htmlInline.literal.length
        }

        override fun visit(customBlock: CustomBlock) {
            visitChildren(customBlock)
        }

        override fun visit(customNode: CustomNode) {
            visitChildren(customNode)
        }
    }
}

private fun linesIn(literal: String): Int =
    if (literal.isEmpty()) 0 else literal.removeSuffix("\n").count { it == '\n' } + 1

private fun rowsOfTable(node: Node): Int {
    var rows = 0
    var child = node.firstChild
    while (child != null) {
        rows += if (child is TableRow) 1 else rowsOfTable(child)
        child = child.next
    }
    return rows
}

/** Counts the prose characters and the code or table lines under one block. */
private class RowCounter : AbstractVisitor() {
    var chars = 0L
        private set
    var lines = 0L
        private set
    var mayBeMath = false
        private set

    override fun visit(text: Text) {
        if ('$' in text.literal) mayBeMath = true
        chars += text.literal.length
    }

    override fun visit(code: Code) {
        chars += code.literal.length
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        if (fenceLanguage(fencedCodeBlock.info) != MERMAID_LANGUAGE) lines += linesIn(fencedCodeBlock.literal)
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        lines += linesIn(indentedCodeBlock.literal)
    }

    // A picture's alt text is not painted as text; HTML is not painted from its source.
    override fun visit(image: Image) = Unit

    override fun visit(htmlBlock: HtmlBlock) = Unit

    override fun visit(htmlInline: HtmlInline) = Unit

    override fun visit(customBlock: CustomBlock) {
        if (customBlock is TableBlock) lines += rowsOfTable(customBlock) else visitChildren(customBlock)
    }

    override fun visit(customNode: CustomNode) {
        visitChildren(customNode)
    }
}
