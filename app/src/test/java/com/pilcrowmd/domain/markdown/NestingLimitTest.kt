// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** NEW-11: the depth measurement, and the post-processor that swaps out a tree too deep to walk. */
class NestingLimitTest {

    private val bare = Parser.builder().build()
    private val guarded = Parser.builder().postProcessor(NestingLimit.postProcessor).build()

    @Test
    fun `depth counts from the root's children`() {
        assertEquals(0, NestingLimit.depth(Document()))
        // Document > Paragraph > Text
        assertEquals(2, NestingLimit.depth(bare.parse("plain")))
        // Document > BlockQuote > BlockQuote > Paragraph > Text
        assertEquals(4, NestingLimit.depth(bare.parse("> > a")))
    }

    @Test
    fun `depth is the deepest branch, not the last or the first one visited`() {
        // A deep branch between two shallow siblings: a walk that lost count on the way back up, or
        // stopped at the first leaf, would answer 2.
        assertEquals(6, NestingLimit.depth(bare.parse("a\n\n> > > > b\n\nc")))
        // The deepest branch is last, after a return to a shallower level.
        assertEquals(5, NestingLimit.depth(bare.parse("> a\n>\n> > > b")))
    }

    @Test
    fun `depth of a hand-built chain ignores siblings above the root`() {
        val outer = Document()
        val root = BlockQuote()
        outer.appendChild(root)
        outer.appendChild(Paragraph()) // a sibling of root: must not be walked into
        val paragraph = Paragraph()
        root.appendChild(paragraph)
        paragraph.appendChild(Text("x"))
        assertEquals(2, NestingLimit.depth(root))
    }

    @Test
    fun `a depth far beyond any thread stack is measured without overflowing`() {
        val n = 100_000
        val tree = bare.parse(">".repeat(n) + " deep")
        assertEquals(n + 2, NestingLimit.depth(tree)) // n quotes, then the paragraph and its text
        assertEquals(2 * n + 2, NestingLimit.depth(bare.parse("- ".repeat(n) + "deep"))) // list + item per level
    }

    @Test
    fun `the post-processor keeps a tree at the limit and replaces one just past it`() {
        // `>` × k puts the text at depth k + 2.
        val atLimit = ">".repeat(NestingLimit.MAX_DEPTH - 2) + " ok"
        val pastLimit = ">".repeat(NestingLimit.MAX_DEPTH - 1) + " too deep"
        val kept = guarded.parse(atLimit)
        assertFalse(kept is TooDeepDocument)
        assertEquals(NestingLimit.MAX_DEPTH, NestingLimit.depth(kept))

        val replaced = guarded.parse(pastLimit)
        assertTrue(replaced is TooDeepDocument)
        assertEquals("the replacement has nothing to walk", null, replaced.firstChild)
    }

    @Test
    fun `the post-processor returns an ordinary document itself, untouched`() {
        val document = bare.parse("# Title\n\n> quote\n\n- a\n  - b\n")
        assertSame(document, NestingLimit.postProcessor.process(document))
    }

    @Test
    fun `every deep container shape is caught`() {
        val shapes = mapOf(
            "quotes" to ">".repeat(5_000) + " x",
            "lists" to "- ".repeat(5_000) + "x",
            "ordered lists" to "1. ".repeat(5_000) + "x",
            "mixed" to "> - ".repeat(2_500) + "x",
            "details in quotes" to "> ".repeat(5_000) + "<details>\nx",
        )
        shapes.forEach { (shape, markdown) ->
            assertTrue(shape, guarded.parse(markdown) is TooDeepDocument)
        }
    }

    private fun Node.childCount(): Int = generateSequence(firstChild) { it.next }.count()

    @Test
    fun `nested details blocks are siblings, not a deep tree`() {
        // commonmark keeps HTML blocks flat, so `<details>` nesting is not the recursion NEW-11 is
        // about; the reader's section logic over them is iterative (Details.sections).
        val tree = bare.parse("<details>\n\n".repeat(10_000) + "x")
        assertTrue(NestingLimit.depth(tree) <= 2)
        assertEquals(10_001, tree.childCount())
    }
}
