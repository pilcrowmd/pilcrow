// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.markdown.LongLineCut.MAX_BLOCK_CHARS
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/**
 * S8-A61: cuts one inline node too long for a reader block into several of its own kind, for
 * [ParagraphSplitter], and measures the text inline nodes paint, for it and [TableSplitter].
 *
 * [inlineSource] gives the source of an inline node commonmark does not know (a `$…$` formula, a
 * `:name:` emoji; see [Callouts.transform]). Such a node is measured by that source, not as 0 (S8-A62):
 * search and the Contents walk parse with commonmark alone, where the same source is plain text, and
 * both trees must cut at the same places or every later block is numbered one off between them.
 */
internal class InlineCut(private val inlineSource: (Node) -> String? = { null }) {
    /**
     * [node] as several siblings of its own kind of at most [MAX_BLOCK_CHARS] characters each, or
     * [node] alone when it fits or is not a kind a cut may go inside. A copy takes [node]'s own
     * children, so [node] is only to be dropped from the tree once this has returned copies.
     */
    fun cutToSize(node: Node): List<Node> {
        if (chars(node) <= MAX_BLOCK_CHARS) return listOf(node)
        return when (node) {
            is Text -> pieces(node.literal).map { Text(it) }
            is Code -> pieces(node.literal).map { Code(it) }
            else -> {
                if (emptyCopyOf(node) == null) return listOf(node)
                val inner = generateSequence(node.firstChild) { it.next }.toList().flatMap { cutToSize(it) }
                runsOf(inner).mapNotNull { run -> emptyCopyOf(node)?.also { piece -> run.forEach(piece::appendChild) } }
            }
        }
    }

    /** [nodes] in runs of at most [MAX_BLOCK_CHARS] characters, each run at least one node. */
    private fun runsOf(nodes: List<Node>): List<List<Node>> {
        val runs = ArrayList<List<Node>>()
        var run = ArrayList<Node>()
        var chars = 0
        for (node in nodes) {
            val weight = chars(node)
            if (run.isNotEmpty() && chars + weight > MAX_BLOCK_CHARS) {
                runs += run
                run = ArrayList()
                chars = 0
            }
            run += node
            chars += weight
        }
        if (run.isNotEmpty()) runs += run
        return runs
    }

    /** An empty node of [node]'s kind and attributes, for the kinds a cut may go inside; else null. */
    private fun emptyCopyOf(node: Node): Node? = when (node) {
        is StrongEmphasis -> StrongEmphasis(node.openingDelimiter)
        is Emphasis -> Emphasis(node.openingDelimiter)
        is Link -> Link(node.destination, node.title)
        is Strikethrough -> Strikethrough()
        else -> null
    }

    /** [literal] cut where [LongLineCut.pieceEnd] says, every character in exactly one piece. */
    private fun pieces(literal: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (from < literal.length) {
            val end = LongLineCut.pieceEnd(literal, from)
            out += literal.substring(from, end)
            from = end
        }
        return out
    }

    /**
     * The characters [node] paints as text, what its TextView has to lay out; a node [inlineSource]
     * knows counts as its source, which is what the parity tree holds in its place.
     */
    fun chars(node: Node): Int = when (node) {
        is Text -> node.literal.length
        is Code -> node.literal.length
        is HtmlInline -> node.literal.length
        else -> sourceLength(node) ?: generateSequence(node.firstChild) { it.next }.sumOf { chars(it) }
    }

    /** The length of [node]'s source when [inlineSource] knows it (a formula, an emoji); else null. */
    fun sourceLength(node: Node): Int? = inlineSource(node)?.length
}
