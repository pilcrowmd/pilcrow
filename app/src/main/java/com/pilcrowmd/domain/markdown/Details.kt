// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.HtmlBlock
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node

/**
 * An opening `<details>` block, read from its HTML literal. **M-161.**
 *
 * CommonMark ends an HTML block at a blank line, so `<details>` + `<summary>` is usually one block
 * and the section's Markdown body follows as sibling blocks up to a `</details>` block. Text after
 * `</summary>` in the same block is [inlineBody]; when the block also closes the section
 * ([closesItself]), that text is the whole body.
 *
 * `</details>` written straight before the next `<details>`, with no blank line, is in the same
 * block: [closesBefore] counts those leading close tags, each ending a section still open before it.
 * `</details>` with a text line straight under it is one block too, and it still ends the section
 * (see [Details.sections]).
 */
data class DetailsHeader(
    val summary: String,
    val inlineBody: String,
    val openByDefault: Boolean,
    val closesItself: Boolean,
    val closesBefore: Int = 0,
)

/**
 * One collapsible section over the top-level block list. [header] is its `<details>` block's index;
 * [last] is its `</details>` block's index, or the document's last block when it is never closed
 * (a browser runs an unclosed `<details>` to the end too). The body is `header + 1 until last`, plus
 * [last] itself when [closed] is false. A section closed by `</details>` tags that start a block
 * which also paints (the next header, or a line of text) has no `</details>` block of its own: it
 * ends at the block before that one, not [closed].
 */
data class DetailsSection(val header: Int, val last: Int, val closed: Boolean, val openByDefault: Boolean) {
    /** The separate `</details>` block, or null when there is none (unclosed, or a one-block section). */
    val closeBlock: Int? get() = if (closed && last != header) last else null
}

object Details {

    private val OPEN_TAG = Regex("""^((?:\s*</details\s*>)*)\s*<details(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val OPEN_ATTRIBUTE = Regex("""\sopen(\s|=|$)""", RegexOption.IGNORE_CASE)
    private val SUMMARY =
        Regex("""<summary(\s[^>]*)?>(.*?)</summary\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CLOSE_TAG = Regex("""</details\s*>""", RegexOption.IGNORE_CASE)
    private val CLOSE_BLOCK = Regex("""^(?:\s*</details\s*>)+\s*$""", RegexOption.IGNORE_CASE)
    private val LEADING_CLOSES = Regex("""^(?:\s*</details\s*>)+""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]*>""")
    private val WHITESPACE = Regex("""\s+""")

    /** Browsers label a `<details>` without a `<summary>` this way. */
    const val DEFAULT_SUMMARY = "Details"

    /** The header this HTML literal opens, or null when it does not start with `<details>` (after any `</details>`). */
    fun parseHeader(literal: String): DetailsHeader? {
        val open = OPEN_TAG.find(literal) ?: return null
        val attributes = open.groupValues[2]
        val afterOpen = literal.substring(open.range.last + 1)
        val summaryMatch = SUMMARY.find(afterOpen)
        val summary = summaryMatch?.groupValues?.get(2)?.let(::plainText).orEmpty().ifEmpty { DEFAULT_SUMMARY }
        val rest = if (summaryMatch != null) afterOpen.substring(summaryMatch.range.last + 1) else afterOpen
        val close = CLOSE_TAG.find(rest)
        return DetailsHeader(
            summary = summary,
            inlineBody = plainText(if (close != null) rest.substring(0, close.range.first) else rest),
            openByDefault = OPEN_ATTRIBUTE.containsMatchIn(attributes),
            closesItself = close != null,
            closesBefore = CLOSE_TAG.findAll(open.groupValues[1]).count(),
        )
    }

    /** True for a block that is only `</details>` tags: it paints nothing. */
    fun isCloseBlock(literal: String): Boolean = CLOSE_BLOCK.matches(literal)

    /**
     * The sections in [document]'s top-level blocks, nested ones included, in header order. Indices
     * are the reader adapter's positions, and Markwon's reducer drops a link reference definition
     * before numbering them, so a definition takes no index here either.
     */
    fun sections(document: Node): List<DetailsSection> {
        val blocks = generateSequence(document.firstChild) { it.next }
            .filterNot { it is LinkReferenceDefinition }
            .toList()
        val sections = mutableListOf<DetailsSection>()
        val openHeaders = ArrayDeque<Pair<Int, DetailsHeader>>()
        blocks.forEachIndexed { index, block ->
            if (block !is HtmlBlock) return@forEachIndexed
            val header = parseHeader(block.literal)
            val closeOnly = header == null && isCloseBlock(block.literal)
            repeat(header?.closesBefore ?: leadingCloses(block.literal)) {
                val (start, opened) = openHeaders.removeLastOrNull() ?: return@repeat
                // A block of only close tags ends the section as its own hidden close block; one that
                // also paints (a header, a line of text) stays visible after the section it ends.
                sections.add(
                    if (closeOnly) {
                        DetailsSection(start, index, closed = true, openByDefault = opened.openByDefault)
                    } else {
                        DetailsSection(start, index - 1, closed = false, openByDefault = opened.openByDefault)
                    },
                )
            }
            if (header == null) return@forEachIndexed
            if (header.closesItself) {
                sections.add(DetailsSection(index, index, closed = true, openByDefault = header.openByDefault))
            } else {
                openHeaders.addLast(index to header)
            }
        }
        while (openHeaders.isNotEmpty()) {
            val (start, opened) = openHeaders.removeLast()
            sections.add(DetailsSection(start, blocks.lastIndex, closed = false, openByDefault = opened.openByDefault))
        }
        return sections.sortedBy { it.header }
    }

    private fun leadingCloses(literal: String): Int =
        LEADING_CLOSES.find(literal)?.let { CLOSE_TAG.findAll(it.value).count() } ?: 0

    /** Tags removed, entities for `<`, `>` and `&` decoded, whitespace collapsed — what HTML paints. */
    private fun plainText(html: String): String = html.replace(TAG, " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(WHITESPACE, " ").trim()
}
