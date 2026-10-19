// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-161 / M-227 / M-251: which blocks become callouts, and what is left of them. Plain commonmark,
 * as search parses, plus the admonition block parser every parse site registers.
 */
class CalloutsTest {

    private fun parse(markdown: String): Node {
        val fences = AdmonitionFences.scan(markdown)
        val parser = Parser.builder().customBlockParserFactory(AdmonitionBlockParserFactory { fences }).build()
        return Callouts.transform(parser.parse(markdown))
    }

    private fun first(markdown: String): Node = parse(markdown).firstChild!!

    /** Each top-level block of [root]: its type and its text. */
    private fun shape(root: Node): List<String> = buildList {
        var node = root.firstChild
        while (node != null) {
            add(node.javaClass.simpleName + ":" + text(node))
            node = node.next
        }
    }

    /** [markdown] as commonmark reads it with no admonition parser at all. */
    private fun withoutAdmonitions(markdown: String): List<String> =
        shape(Callouts.transform(Parser.builder().build().parse(markdown)))

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
    fun `an unknown type stays an ordinary quote, with or without a title`() {
        assertTrue(first("> [!FOO]\n> Body") is BlockQuote)
        assertTrue(first("> [!UNKNOWNTYPE] A title\n> Body") is BlockQuote)
        assertTrue(first("> Just a quote") is BlockQuote)
        assertTrue("a marker in a code span is not one", first("> `[!NOTE]`\n> Body") is BlockQuote)
    }

    @Test
    fun `text after the marker is the callout's title, and the body keeps its own lines`() {
        val node = first("> [!NOTE] Custom callout title\n> Body") as CalloutBlock
        assertEquals(CalloutKind.NOTE, node.kind)
        assertEquals("Custom callout title", node.title)
        assertEquals("Custom callout title", node.paintedTitle)
        assertEquals("[!NOTE]", node.marker)
        assertEquals("Body", text(node))
    }

    @Test
    fun `a title-only callout keeps its title and has no content`() {
        val node = first("> [!NOTE] Body on the same line") as CalloutBlock
        assertEquals("Body on the same line", node.paintedTitle)
        assertNull(node.firstChild)
    }

    @Test
    fun `a title is plain text, its inline formatting flattened`() {
        val node = first("> [!TIP] A **bold** `code` title\n> Body") as CalloutBlock
        assertEquals("A bold code title", node.paintedTitle)
        assertEquals("Body", text(node))
    }

    @Test
    fun `with no title the kind's own title is painted`() {
        val node = first("> [!WARNING]\n> Body") as CalloutBlock
        assertNull(node.title)
        assertEquals("Warning", node.paintedTitle)
    }

    @Test
    fun `a fold sign is accepted and never painted, with or without a title`() {
        val closed = first("> [!TIP]- Collapsible callout\n> Body") as CalloutBlock
        assertEquals(CalloutKind.TIP, closed.kind)
        assertEquals("Collapsible callout", closed.paintedTitle)
        assertEquals("Body", text(closed))
        val open = first("> [!WARNING]+ Starts open\n> Body") as CalloutBlock
        assertEquals("Starts open", open.paintedTitle)
        val bare = first("> [!TIP]-\n> Body") as CalloutBlock
        assertEquals("Tip", bare.paintedTitle)
        assertEquals("Body", text(bare))
        assertEquals("Note", (first("> [!NOTE]+") as CalloutBlock).paintedTitle)
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

    // M-251: Python-Markdown `!!!` and fenced `:::` admonitions.

    @Test
    fun `a Python-Markdown admonition is a callout with its quoted title and indented body`() {
        val node = first("!!! info \"Fieldwork note (Python-Markdown)\"\n    Rainfall was measured.") as CalloutBlock
        assertEquals(CalloutKind.NOTE, node.kind)
        assertEquals("Fieldwork note (Python-Markdown)", node.paintedTitle)
        assertEquals("!!!", node.marker)
        assertEquals("Rainfall was measured.", text(node))
    }

    @Test
    fun `an admonition with no title paints its kind's title`() {
        val node = first("!!! warning\n    Body") as CalloutBlock
        assertEquals(CalloutKind.WARNING, node.kind)
        assertNull(node.title)
        assertEquals("Warning", node.paintedTitle)
    }

    @Test
    fun `an admonition body is ordinary Markdown across blank lines, and ends at the first unindented line`() {
        val node = first("!!! tip\n    One.\n\n    - a\n    - b\n\n    ```\n    code\n    ```\n\nAfter")
        assertTrue(node is CalloutBlock)
        assertTrue(node.firstChild is Paragraph)
        assertTrue(node.firstChild!!.next is BulletList)
        assertEquals("code\n", (node.firstChild!!.next!!.next as FencedCodeBlock).literal)
        assertEquals("After", text(node.next!!))
    }

    @Test
    fun `every admonition type maps onto one of the five kinds, in any case`() {
        val expected = mapOf(
            CalloutKind.NOTE to listOf("note", "info", "abstract", "summary", "todo", "question", "help", "faq"),
            CalloutKind.TIP to listOf("tip", "hint", "success"),
            CalloutKind.IMPORTANT to listOf("important"),
            CalloutKind.WARNING to listOf("warning", "attention"),
            CalloutKind.CAUTION to listOf("danger", "error", "caution", "failure", "bug"),
        )
        for ((kind, types) in expected) {
            for (type in types + types.map { it.uppercase() }) {
                assertEquals("!!! $type", kind, (first("!!! $type\n    Body") as CalloutBlock).kind)
                assertEquals("::: $type", kind, (first("::: $type\nBody\n:::") as CalloutBlock).kind)
            }
        }
    }

    @Test
    fun `an unknown admonition type is left exactly as it parses today`() {
        assertTrue(first("!!! foo \"Title\"\n    Body") is Paragraph)
        assertTrue(first("::: foo\nBody\n:::") is Paragraph)
        assertTrue("Pandoc's attribute form is not a type", first("::: {.warning}\nBody\n:::") is Paragraph)
        assertTrue("prose that starts with !!! is prose", first("!!! Note this well") is Paragraph)
    }

    @Test
    fun `the collapsible form is shown open like the plain one`() {
        assertEquals("T", (first("??? note \"T\"\n    Body") as CalloutBlock).paintedTitle)
        assertEquals("Tip", (first("???+ tip\n    Body") as CalloutBlock).paintedTitle)
    }

    @Test
    fun `a fenced admonition is a callout up to its closing fence`() {
        val node = first("::: warning\nThis fenced admonition is supported.\n:::\nAfter") as CalloutBlock
        assertEquals(CalloutKind.WARNING, node.kind)
        assertNull(node.title)
        assertEquals(":::", node.marker)
        assertEquals("This fenced admonition is supported.", text(node))
        assertTrue("the line after the fence is not swallowed", node.next is Paragraph)
        assertEquals("After", text(node.next!!))
    }

    @Test
    fun `a fenced admonition takes the rest of its first line as the title`() {
        val node = first(":::tip Custom title\nBody\n:::") as CalloutBlock
        assertEquals("Custom title", node.paintedTitle)
        assertEquals("Body", text(node))
    }

    @Test
    fun `a shorter fence inside a longer one does not close it`() {
        val node = first(":::: note\nA\n:::\nB\n::::\nAfter") as CalloutBlock
        assertEquals("the inner ::: is body text", "A:::B", text(node))
        assertEquals("After", text(node.next!!))
    }

    @Test
    fun `an opening fence with no closing fence stays prose, exactly as without admonitions`() {
        for (markdown in listOf(
            "::: note\nBody\n\n# Heading\n\nLast",
            "Before\n::: tip Title\nBody",
            "::: note\nBody\n::\nLast",
            "::::: note\nBody\n:::\nLast",
        )) {
            assertEquals(markdown, withoutAdmonitions(markdown), shape(parse(markdown)))
        }
        val heading = first("::: note\nBody\n\n# Heading\n\nLast").next
        assertTrue("the heading is a top-level block again, so it is in the TOC", heading is Heading)
    }

    @Test
    fun `a stray opening fence does not stop a closed admonition after it`() {
        val stray = first(":::: note\nStray\n\n::: tip\nT\n:::\nAfter")
        assertTrue(stray is Paragraph)
        assertEquals(":::: noteStray", text(stray))
        val callout = stray.next as CalloutBlock
        assertEquals(CalloutKind.TIP, callout.kind)
        assertEquals("T", text(callout))
        assertEquals("After", text(callout.next!!))
    }

    @Test
    fun `a line that also ends in colons is never an opening fence`() {
        val markdown = "::: Summary :::\nBody\n:::"
        assertEquals(withoutAdmonitions(markdown), shape(parse(markdown)))
    }

    @Test
    fun `a fence inside a code block is code, not the closing fence`() {
        val unclosed = "::: note\n```\n:::\n```"
        assertEquals("only a fence outside the code closes", withoutAdmonitions(unclosed), shape(parse(unclosed)))
        val callout = first("::: note\n```\n:::\n```\n:::\nAfter") as CalloutBlock
        assertEquals(":::\n", (callout.firstChild as FencedCodeBlock).literal)
        assertEquals("After", text(callout.next!!))
    }

    @Test
    fun `admonitions are top-level only, like GitHub alerts`() {
        val inList = first("- !!! note\n      Body")
        assertTrue(inList is BulletList)
        assertTrue("not a callout inside the list item", inList.firstChild!!.firstChild is Paragraph)
        val inQuote = first("> !!! note\n>     Body")
        assertTrue(inQuote is BlockQuote)
        assertTrue("not a callout inside the quote", inQuote.firstChild is Paragraph)
        assertTrue(first("> ::: note\n> Body\n> :::") is BlockQuote)
        assertTrue("four spaces of indent is code", first("    !!! note\n        Body") is IndentedCodeBlock)
    }

    @Test
    fun `an admonition can interrupt a paragraph`() {
        val paragraph = first("Before\n!!! note\n    Body")
        assertEquals("Before", text(paragraph))
        assertTrue(paragraph.next is CalloutBlock)
        val fenced = first("Before\n::: tip\nBody\n:::")
        assertEquals("Before", text(fenced))
        assertTrue(fenced.next is CalloutBlock)
    }

    @Test
    fun `an admonition cannot interrupt a paragraph inside a quote or a list`() {
        val quote = first("> Before\n> !!! note\n>     Body")
        assertTrue(quote is BlockQuote)
        assertTrue(quote.firstChild is Paragraph)
        assertNull("the quote holds one paragraph", quote.firstChild!!.next)
        val list = first("- Before\n  !!! note\n      Body")
        assertTrue(list.firstChild!!.firstChild is Paragraph)
        assertNull("the list item holds one paragraph", list.firstChild!!.firstChild!!.next)
    }

    @Test(timeout = 10_000)
    fun `a long run of blanks inside a title does not stall the parse`() {
        // A lazy title group backtracks quadratically over the run; 200k blanks would take minutes.
        val gap = " ".repeat(200_000)
        assertEquals("a${gap}b", (first("> [!NOTE] a${gap}b\n> Body") as CalloutBlock).paintedTitle)
        assertEquals("a${gap}b", (first("::: note a${gap}b \t\nBody\n:::") as CalloutBlock).paintedTitle)
    }

    @Test
    fun `trailing blanks after a title are not part of it`() {
        assertEquals("Title", (first("> [!NOTE] Title \t\n> Body") as CalloutBlock).paintedTitle)
        assertEquals("Title", (first("::: note Title \t\nBody\n:::") as CalloutBlock).paintedTitle)
        assertNull((first("::: note \t\nBody\n:::") as CalloutBlock).title)
    }
}
