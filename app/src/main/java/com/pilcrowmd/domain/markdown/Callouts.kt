// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.CustomBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/** The five GitHub alert types (M-161), with the title each one paints. */
enum class CalloutKind(val title: String) {
    NOTE("Note"),
    TIP("Tip"),
    IMPORTANT("Important"),
    WARNING("Warning"),
    CAUTION("Caution"),
}

/**
 * A top-level blockquote whose first line was `[!KIND]`: the quote's content with that marker line
 * removed. [marker] is the marker as written, so search can anchor the painted title on it.
 */
class CalloutBlock(val kind: CalloutKind, val marker: String) : CustomBlock()

/**
 * Turns GitHub alerts (`> [!NOTE]` …) into [CalloutBlock]s. **M-161.**
 *
 * Top-level quotes only, as on GitHub, where an alert cannot nest inside a list or another quote. The
 * marker must be the whole first line; an unknown type (`[!FOO]`) or text after the marker leaves an
 * ordinary blockquote. The source is never touched (Safeguard 2) — this rewrites the parsed tree, at
 * the same sites and in the same order as [Footnotes.transform] (see [ReaderDocument]).
 */
object Callouts {

    private val MARKER = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*$""", RegexOption.IGNORE_CASE)

    fun transform(document: Node): Node {
        var node = document.firstChild
        while (node != null) {
            val next = node.next
            if (node is BlockQuote) convert(node)
            node = next
        }
        return document
    }

    private fun convert(quote: BlockQuote) {
        val paragraph = quote.firstChild as? Paragraph ?: return
        // The marker can arrive as several Text nodes (`[`, `!NOTE`, `]`) depending on the inline
        // parser, so the first line is read as the run of Text before the first line break.
        val markerNodes = mutableListOf<Node>()
        var child = paragraph.firstChild
        while (child is Text) {
            markerNodes.add(child)
            child = child.next
        }
        if (child != null && child !is SoftLineBreak && child !is HardLineBreak) return
        val marker = markerNodes.joinToString("") { (it as Text).literal }
        val match = MARKER.matchEntire(marker) ?: return
        val kind = CalloutKind.valueOf(match.groupValues[1].uppercase())

        markerNodes.forEach { it.unlink() }
        child?.unlink() // the line break that ended the marker line
        if (paragraph.firstChild == null) paragraph.unlink()

        val callout = CalloutBlock(kind, marker.trimEnd())
        var content = quote.firstChild
        while (content != null) {
            val next = content.next
            callout.appendChild(content)
            content = next
        }
        quote.insertBefore(callout)
        quote.unlink()
    }
}
