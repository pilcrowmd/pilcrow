// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-161: which quotes become callouts, and what is left of them. Plain commonmark, as search parses. */
class CalloutsTest {

    private fun first(markdown: String): Node =
        Callouts.transform(Parser.builder().build().parse(markdown)).firstChild!!

    private fun text(node: Node): String = buildString {
        fun walk(n: Node) {
            if (n is Text) append(n.literal)
            var c = n.firstChild
            while (c != null) {
                walk(c)
                c = c.next
            }
        }
        walk(node)
    }

    @Test
    fun `each of the five markers makes its callout, and only the marker line is removed`() {
        for (kind in CalloutKind.values()) {
            val node = first("> [!${kind.name}]\n> Body **text**.")
            assertTrue("$kind became a callout", node is CalloutBlock)
            assertEquals(kind, (node as CalloutBlock).kind)
            assertEquals("Body text.", text(node))
        }
    }

    @Test
    fun `the marker is case-insensitive and remembers how it was written`() {
        val node = first("> [!note]\n> Body") as CalloutBlock
        assertEquals(CalloutKind.NOTE, node.kind)
        assertEquals("[!note]", node.marker)
    }

    @Test
    fun `an unknown type, or text after the marker, stays an ordinary quote`() {
        assertTrue(first("> [!FOO]\n> Body") is BlockQuote)
        assertTrue(first("> [!NOTE] Body on the same line") is BlockQuote)
        assertTrue(first("> Just a quote") is BlockQuote)
    }

    @Test
    fun `a marker-only callout has no content left`() {
        val node = first("> [!TIP]") as CalloutBlock
        assertNull(node.firstChild)
    }

    @Test
    fun `later paragraphs of the callout are kept`() {
        val node = first("> [!WARNING]\n> One.\n>\n> Two.") as CalloutBlock
        assertEquals("One.Two.", text(node))
    }

    @Test
    fun `a quote inside a list is not a callout, as on GitHub`() {
        val list = first("- > [!NOTE]\n  > Body")
        assertTrue(list.firstChild!!.firstChild is BlockQuote)
    }
}
