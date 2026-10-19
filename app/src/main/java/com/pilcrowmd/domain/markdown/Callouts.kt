// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
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
 * A callout: a top-level blockquote whose first line was `[!KIND]` (M-161), or a `!!!`/`:::`
 * admonition (M-251, [AdmonitionBlockParserFactory]). Its children are the body; the header line is
 * gone. [marker] is the header's opening token as written (`[!note]`, `!!!`, `:::`), so search can
 * anchor the painted title on it. [title] is the header's own title, as plain text, or null.
 */
class CalloutBlock(val kind: CalloutKind, val marker: String, val title: String? = null) : CustomBlock() {
    /** What the callout paints on its first line: its own [title], else the kind's. */
    val paintedTitle: String get() = title ?: kind.title
}

/**
 * Turns GitHub alerts (`> [!NOTE]` …) into [CalloutBlock]s. **M-161.**
 *
 * Top-level quotes only, as on GitHub, where an alert cannot nest inside a list or another quote. The
 * marker must open the first line; an unknown type (`[!FOO]`) leaves an ordinary blockquote. M-227: the
 * rest of that line is the title (Obsidian's form), painted as plain text, and an Obsidian fold sign
 * (`-`/`+`) straight after the marker is accepted and dropped: callouts are always shown open. The
 * source is never touched (Safeguard 2) — this rewrites the parsed tree, at the same sites and in the
 * same order as [Footnotes.transform] (see [ReaderDocument]).
 */
object Callouts {

    // Groups: 1 the marker as written, 2 the kind, 3 the title (after the optional fold sign) with its
    // trailing blanks, trimmed by the caller: a lazy group before `\s*$` is quadratic over a run of blanks.
    private val MARKER =
        Regex("""^(\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)])[+-]?(?:\s+(.*))?\s*$""", RegexOption.IGNORE_CASE)

    // What `\s` matches in a Java regex; [Char.isWhitespace] is wider.
    private const val REGEX_BLANKS = " \t\n\u000B\u000C\r"

    /**
     * [inlineSource] gives the source text of an inline node commonmark does not know, such as the
     * renderer's maths, so a title holding one keeps it as written; null for any other node. The
     * search parser has no such nodes, so it passes nothing.
     */
    fun transform(document: Node, inlineSource: (Node) -> String? = { null }): Node {
        var node = document.firstChild
        while (node != null) {
            val next = node.next
            if (node is BlockQuote) convert(node, inlineSource)
            node = next
        }
        return document
    }

    private fun convert(quote: BlockQuote, inlineSource: (Node) -> String?) {
        val paragraph = quote.firstChild as? Paragraph ?: return
        // The first line, as inline nodes. The marker can arrive as several Text nodes (`[`, `!NOTE`,
        // `]`) depending on the inline parser, and a title may hold emphasis or code, so the line is
        // read as plain text up to the first line break.
        val lineNodes = mutableListOf<Node>()
        var child = paragraph.firstChild
        while (child != null && child !is SoftLineBreak && child !is HardLineBreak) {
            lineNodes.add(child)
            child = child.next
        }
        val (marker, type, title) =
            MARKER.matchEntire(lineNodes.joinToString("") { plainText(it, inlineSource) })?.destructured ?: return
        // The marker itself must be literal text: `` `[!NOTE]` `` in a code span is not one.
        val leadingText = lineNodes.takeWhile { it is Text }.joinToString("") { (it as Text).literal }
        if (!leadingText.startsWith(marker)) return
        val kind = CalloutKind.valueOf(type.uppercase())

        lineNodes.forEach { it.unlink() }
        child?.unlink() // the line break that ended the marker line
        if (paragraph.firstChild == null) paragraph.unlink()

        val trimmed = title.trimEnd { it in REGEX_BLANKS }
        val callout = CalloutBlock(kind, marker, trimmed.takeIf { it.isNotEmpty() })
        var content = quote.firstChild
        while (content != null) {
            val next = content.next
            callout.appendChild(content)
            content = next
        }
        quote.insertBefore(callout)
        quote.unlink()
    }

    /**
     * [node]'s text with its formatting dropped; raw HTML tags paint nothing, so they add nothing, and a
     * node [inlineSource] knows (a formula) keeps its source.
     */
    private fun plainText(node: Node, inlineSource: (Node) -> String?): String = when (node) {
        is Text -> node.literal
        is Code -> node.literal
        is HtmlInline -> ""
        else -> inlineSource(node) ?: buildString {
            var child = node.firstChild
            while (child != null) {
                append(plainText(child, inlineSource))
                child = child.next
            }
        }
    }
}
