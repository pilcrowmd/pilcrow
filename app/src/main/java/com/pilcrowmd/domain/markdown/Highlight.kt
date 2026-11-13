// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

/**
 * S8-A88: `==text==`, marked text. A pair of double equals signs around inline content, parsed like
 * `~~strikethrough~~`: the opening `==` must touch a non-space on its right and the closing one a
 * non-space on its left (commonmark's flanking rule), so `a == b` stays plain text. Inside code
 * spans, maths and link destinations the inline parser never reaches it. Bold, links and the rest
 * nest inside as children.
 *
 * Parse-time only: the node holds the content between the markers and the source is never touched
 * (Safeguard 2). The reader and the search parser both register [HighlightDelimiterProcessor].
 * `HighlightTest` fails if the search parser lacks it (search counts, contents text); the block-parity
 * test cannot, as `==` never changes the block sequence.
 */
class Highlight :
    CustomNode(),
    Delimited {
    override fun getOpeningDelimiter(): String = MARKER

    override fun getClosingDelimiter(): String = MARKER

    companion object {
        const val MARKER = "=="
    }
}

/** The `=` delimiter run handler: two on each side make one [Highlight]; a longer run keeps the rest literal. */
class HighlightDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter(): Char = '='

    override fun getClosingCharacter(): Char = '='

    override fun getMinLength(): Int = 2

    override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun): Int =
        if (opener.length() >= 2 && closer.length() >= 2) 2 else 0

    override fun process(opener: Text, closer: Text, delimiterUse: Int) {
        val highlight = Highlight()
        var node: Node? = opener.next
        while (node != null && node !== closer) {
            val next = node.next
            highlight.appendChild(node)
            node = next
        }
        opener.insertAfter(highlight)
    }
}
