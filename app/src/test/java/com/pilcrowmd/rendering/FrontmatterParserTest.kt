// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.FrontmatterBlockParserFactory
import com.pilcrowmd.domain.markdown.FrontmatterDetector
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the frontmatter fix (no Robolectric/Android needed — the
 * parser layer is plain commonmark-java 0.13.0).
 *
 * Verifies two things:
 *  1. [FrontmatterDetector] — the full-string lookahead that decides whether *well-formed*
 *     frontmatter (open `---` at line 1 AND a matching close `---`) is present. This is the
 *     rollback gate: no close ⇒ not eligible ⇒ core renders a thematic break.
 *  2. [FrontmatterBlockParserFactory] integration — eligible docs emit a `FencedCodeBlock`
 *     with info `"yaml"`; ineligible docs leave the leading `---` as a `ThematicBreak`.
 */
class FrontmatterParserTest {

    /** Mirror of the real plugin wiring: detect once, feed the result to the factory gate. */
    private fun parse(markdown: String): Node {
        val enabled = FrontmatterDetector.hasWellFormedFrontmatter(markdown)
        return Parser.builder()
            .customBlockParserFactory(FrontmatterBlockParserFactory { enabled })
            .build()
            .parse(markdown)
    }

    private fun firstYamlBlock(doc: Node): FencedCodeBlock? {
        var child = doc.firstChild
        while (child != null) {
            if (child is FencedCodeBlock && child.info == "yaml") return child
            child = child.next
        }
        return null
    }

    // ---- detector ----

    @Test
    fun detectsWellFormedFrontmatter() {
        assertTrue(
            FrontmatterDetector.hasWellFormedFrontmatter(
                "---\ntitle: Sample Doc\nauthor: Tester\n---\n\nBody after frontmatter.",
            ),
        )
    }

    @Test
    fun detectsSingleLineFrontmatter() {
        assertTrue(FrontmatterDetector.hasWellFormedFrontmatter("---\nkey: val\n---\n"))
    }

    @Test
    fun rejectsMissingClose() {
        assertFalse(
            FrontmatterDetector.hasWellFormedFrontmatter("---\ntitle: A\n\nBody with no close."),
        )
    }

    @Test
    fun rejectsNoFrontmatter() {
        assertFalse(FrontmatterDetector.hasWellFormedFrontmatter("# Heading\n\nJust a doc."))
    }

    @Test
    fun rejectsMidDocThematicBreak() {
        assertFalse(FrontmatterDetector.hasWellFormedFrontmatter("Above.\n\n---\n\nBelow."))
    }

    // ---- parser integration ----

    @Test
    fun wellFormedFrontmatterBecomesYamlBlock() {
        val doc = parse("---\ntitle: Sample Doc\nauthor: Tester\n---\n\nBody after frontmatter.")
        val yaml = firstYamlBlock(doc)
        assertNotNull("well-formed frontmatter must emit a yaml FencedCodeBlock", yaml)
        assertEquals("title: Sample Doc\nauthor: Tester\n", yaml!!.literal)
    }

    @Test
    fun missingCloseFallsBackToThematicBreak() {
        // Rollback: an unterminated frontmatter block must NOT swallow the
        // document — the leading `---` renders as a normal thematic break.
        val doc = parse("---\ntitle: A\n\nBody with no close.")
        assertNull(
            "unterminated frontmatter must not produce a yaml block",
            firstYamlBlock(doc),
        )
        assertTrue(
            "leading --- must remain a thematic break",
            doc.firstChild is ThematicBreak,
        )
    }

    @Test
    fun midDocDashesRemainThematicBreak() {
        val doc = parse("Above.\n\n---\n\nBelow.")
        assertNull(firstYamlBlock(doc))
    }

    @Test
    fun singleLineFrontmatterBody() {
        val doc = parse("---\nkey: val\n---\n\nx")
        assertEquals("key: val\n", firstYamlBlock(doc)!!.literal)
    }
}
