// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.markdown.LongLineCut.MAX_BLOCK_CHARS
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Where the pieces of a paragraph cut by [ParagraphSplitter] meet. A piece that continues the
 * previous one and a piece that continues into the next are drawn with no gap at that seam, so the
 * cut reads as a line break, not a paragraph break. Keyed by node identity: a block that is not a
 * piece answers false to both, so a document with no split paragraph needs nothing from this.
 */
class ParagraphSeams internal constructor(continuesPrevious: Collection<Node>, continuesNext: Collection<Node>) {
    private val continuesPrevious: Set<Node> = identitySet(continuesPrevious)
    private val continuesNext: Set<Node> = identitySet(continuesNext)

    fun continuesPrevious(block: Node): Boolean = block in continuesPrevious

    fun continuesNext(block: Node): Boolean = block in continuesNext

    companion object {
        val NONE = ParagraphSeams(emptyList(), emptyList())

        private fun identitySet(nodes: Collection<Node>): Set<Node> =
            Collections.newSetFromMap(IdentityHashMap<Node, Boolean>()).also { it.addAll(nodes) }
    }
}

/**
 * How much of a paragraph's children one piece of [ParagraphSplitter] may take: at most
 * [ParagraphSplitter.MAX_INLINE_CHILDREN] children and [MAX_BLOCK_CHARS] characters, a carried word
 * counting as one child in front.
 */
private class PieceBudget(private val children: List<Node>, inline: InlineCut) {
    // charsBefore[i]: the characters of children 0 until i.
    private val charsBefore = IntArray(children.size + 1).also { sums ->
        children.forEachIndexed { i, child -> sums[i + 1] = sums[i] + inline.chars(child) }
    }

    /** Whether the piece of children [start] until [end], with [carried] in front, fits. */
    fun fits(start: Int, end: Int, carried: Text?): Boolean {
        val count = (if (carried == null) 0 else 1) + end - start
        val chars = (carried?.literal?.length ?: 0) + charsBefore[end] - charsBefore[start]
        return count <= ParagraphSplitter.MAX_INLINE_CHILDREN && chars <= MAX_BLOCK_CHARS
    }

    /**
     * The end of the window of a piece starting at [start]: the last child index that still fits, at
     * least one past [start] and before the last child, so the child at it can be looked at.
     */
    fun windowEnd(start: Int, carried: Text?): Int {
        var limit = start + 1
        while (limit + 1 < children.size && fits(start, limit + 1, carried)) limit++
        return limit
    }
}

/**
 * Cuts a top-level paragraph with more than [MAX_INLINE_CHILDREN] inline children, or more than
 * [MAX_BLOCK_CHARS] characters, into several ordinary [Paragraph] blocks, so no single reader
 * TextView has to render, lay out and record thousands of spans, or line-break one enormous run, in
 * one frame on the main thread (M-328: a paragraph of 4,000 spans or 2,000 formulas froze it for
 * over a second per phase, the second Play Vitals ANR; S8-A61: one unbroken line of 200 KB froze it
 * for 8 s in the line breaker, the minikin ANR). Display only: the text in memory and the saved
 * bytes are untouched (Safeguard 2), and the PDF export keeps the paragraph whole.
 *
 * The pieces are plain paragraphs, not a node class of their own, so every tree walker (search, the
 * headings drawer, footnotes, anchors, Markwon's own paragraph visitor) sees ordinary paragraphs and
 * nothing has to learn a new node. Only the reader needs to know where the seams are, and it gets
 * them from the returned [ParagraphSeams].
 *
 * Span-safe: only the paragraph's DIRECT children are partitioned, and a link, emphasis, code span
 * or formula is one child, so a cut between two children never splits one. A cut prefers a line
 * break in the back half of the window (the break itself is dropped: the seam stands in for it),
 * else the last text child there, cut at its last space so the next piece starts on a word.
 *
 * The one exception is a child longer than [MAX_BLOCK_CHARS] on its own: a run of text, a code span,
 * or a link, emphasis or strikethrough around one. That child is first cut into several of its own
 * kind, where [LongLineCut.pieceEnd] says, so a bold run stays bold and a link keeps its destination
 * on both sides of the cut. Anything else (a formula) is never cut.
 *
 * Nothing here throws on a tree commonmark built. Ordinary prose never comes near either limit: 150
 * inline children is about 75 formatted words, and 8,000 characters is a page and a half of text.
 */
object ParagraphSplitter {
    /** Direct inline children a top-level paragraph may keep as one reader block. */
    const val MAX_INLINE_CHILDREN = 150

    /**
     * Cut every oversized top-level paragraph of [document] in place and say where the seams are.
     * [inlineSource]: see [InlineCut]; the reader passes its maths and emoji source.
     */
    fun split(document: Node, inlineSource: (Node) -> String? = { null }): ParagraphSeams {
        val inline = InlineCut(inlineSource)
        val continuesPrevious = ArrayList<Node>()
        val continuesNext = ArrayList<Node>()
        var block = document.firstChild
        while (block != null) {
            val next = block.next
            if (block is Paragraph) splitParagraph(block, inline, continuesPrevious, continuesNext)
            block = next
        }
        return ParagraphSeams(continuesPrevious, continuesNext)
    }

    private fun splitParagraph(
        paragraph: Paragraph,
        inline: InlineCut,
        continuesPrevious: MutableList<Node>,
        continuesNext: MutableList<Node>,
    ) {
        var children = childrenOf(paragraph)
        val chars = children.sumOf { inline.chars(it) }
        if (children.size <= MAX_INLINE_CHILDREN && chars <= MAX_BLOCK_CHARS) return
        // A child too long on its own is cut into children of its own kind first (S8-A61).
        if (children.any { inline.chars(it) > MAX_BLOCK_CHARS }) {
            children.forEach { child ->
                val cut = inline.cutToSize(child)
                if (cut.size != 1 || cut[0] !== child) replace(child, cut)
            }
            children = childrenOf(paragraph)
        }
        val textCuts = ArrayList<Pair<Text, String>>()
        val groups = partition(children, PieceBudget(children, inline), textCuts)
        // Planned above, applied from here on: appendChild moves each child out of the paragraph.
        textCuts.forEach { (text, head) -> text.literal = head }
        var anchor: Node = paragraph
        groups.forEachIndexed { index, group ->
            val piece = Paragraph()
            group.forEach { piece.appendChild(it) }
            anchor.insertAfter(piece)
            anchor = piece
            if (index > 0) continuesPrevious += piece
            if (index < groups.lastIndex) continuesNext += piece
        }
        paragraph.unlink()
    }

    /**
     * The children of each piece, in order; a dropped line break belongs to no piece. [children] is
     * never changed, so [openInlineTags] stays in step with it: a word carried over a cut is held
     * aside as [carried] and counts as the first child of the next piece. A piece's window ends
     * before the child that would take it past [MAX_INLINE_CHILDREN] children or [MAX_BLOCK_CHARS]
     * characters, and always takes at least one new child.
     */
    private fun partition(
        children: List<Node>,
        budget: PieceBudget,
        textCuts: MutableList<Pair<Text, String>>,
    ): List<List<Node>> {
        val groups = ArrayList<List<Node>>()
        val openTags = openInlineTags(children)
        var start = 0
        var carried: Text? = null
        while (start + 1 < children.size && !budget.fits(start, children.size, carried)) {
            // Where the piece starts if the carried word were a child: the window is measured from here.
            val first = if (carried == null) start else start - 1
            val limit = budget.windowEnd(start, carried)
            val half = first + (limit - first) / 2
            val lineBreak = (half..limit).lastOrNull { children[it].isLineBreak() && openTags[it] == 0 }
            if (lineBreak != null && (lineBreak > start || carried != null)) {
                groups += listOfNotNull(carried) + children.subList(start, lineBreak)
                carried = null
                start = lineBreak + 1
            } else {
                val lastText = (half until limit).lastOrNull { children[it] is Text && openTags[it] == 0 }
                val end = if (lastText != null) lastText + 1 else limit
                groups += listOfNotNull(carried) + children.subList(start, end)
                carried = cutAtWordBoundary(children, end, textCuts)
                start = end
            }
        }
        if (start < children.size || carried != null) {
            groups += listOfNotNull(carried) + children.subList(start, children.size)
        }
        return groups
    }

    private fun replace(node: Node, with: List<Node>) {
        with.forEach { node.insertBefore(it) }
        node.unlink()
    }

    private fun childrenOf(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    /**
     * How many inline HTML tags are still open after each child. `<u>text</u>` parses as three
     * siblings (two [HtmlInline] tags around the text), and a piece rendered on its own would lose
     * the styling past a cut between them, so a cut is only taken where nothing is open. A window
     * with no such place is cut at its limit anyway: a bounded block matters more than one tag.
     */
    private fun openInlineTags(children: List<Node>): IntArray {
        var depth = 0
        return IntArray(children.size) { index ->
            val literal = (children[index] as? HtmlInline)?.literal
            when {
                literal == null || literal.isNotHalfOfATagPair() -> Unit
                literal.startsWith("</") -> depth = maxOf(0, depth - 1)
                else -> depth++
            }
            depth
        }
    }

    /**
     * The cut falls after `children[end - 1]`. If that is a text that runs straight into the next
     * child mid-word, its last word goes to the next piece: a new [Text] for it is returned, and the
     * shortened literal of the old one is queued in [textCuts]. Null when nothing is carried.
     */
    private fun cutAtWordBoundary(children: List<Node>, end: Int, textCuts: MutableList<Pair<Text, String>>): Text? {
        val text = children[end - 1] as? Text
        if (text == null || end >= children.size) return null
        val literal = text.literal
        val space = literal.indexOfLast { it.isWhitespace() }
        // No space also covers an empty literal, so last() is only read on a non-empty one.
        if (space < 0 || literal.last().isWhitespace()) return null
        textCuts += text to literal.substring(0, space + 1)
        return Text(literal.substring(space + 1))
    }

    private fun Node.isLineBreak() = this is SoftLineBreak || this is HardLineBreak

    /** A comment, a processing instruction, a self-closing tag or a void element opens nothing. */
    private fun String.isNotHalfOfATagPair() =
        startsWith("<!") || startsWith("<?") || endsWith("/>") || tagName() in VOID_ELEMENTS

    /** The element name, with the `<` or `</` and anything after the name dropped. */
    private fun String.tagName() =
        removePrefix("</").removePrefix("<").takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()

    /** The HTML elements that never have a closing tag, so `<br>` is whole without a slash. */
    private val VOID_ELEMENTS =
        setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
}
