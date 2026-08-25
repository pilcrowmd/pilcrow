// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `\ce{}` shim wired into the REAL MarkwonRenderer plugin chain: `beforeRender` (triggered by
 * [Markwon.render]) must rewrite the latex payload of math nodes from all three producers — the
 * library's `$$…$$` inline processor, our single-`$` processor, and the block parser — and must be
 * structurally unable to touch anything else.
 *
 * The code-block/prose fixture makes "structurally unreachable" a tested claim (added test
 * plan item): a literal `\ce{…}` in a fenced code block or in plain prose survives a
 * render byte-identically.
 */
@RunWith(RobolectricTestRunner::class)
class CeShimRenderingTest {

    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    /** Parses AND renders (render fires beforeRender), returning the visited node tree. */
    private fun parseAndRender(md: String): Node {
        val node = markwon.parse(md)
        markwon.render(node)
        return node
    }

    private fun inlineMathLatex(root: Node): List<String> {
        val found = mutableListOf<String>()
        root.accept(object : AbstractVisitor() {
            override fun visit(customNode: CustomNode) {
                if (customNode is JLatexMathNode) found += customNode.latex()
                visitChildren(customNode)
            }
        })
        return found
    }

    private fun blockMathLatex(root: Node): List<String> {
        val found = mutableListOf<String>()
        root.accept(object : AbstractVisitor() {
            override fun visit(customBlock: CustomBlock) {
                if (customBlock is JLatexMathBlock) found += customBlock.latex()
                visitChildren(customBlock)
            }
        })
        return found
    }

    // --- the three producers all get the shimmed payload ---

    @Test
    fun singleDollarInlineCeIsRewritten() {
        val latex = inlineMathLatex(parseAndRender("rate \$k[\\ce{A}]\$ here"))
        assertEquals(listOf("k[{\\mathrm{A}}]"), latex)
    }

    @Test
    fun doubleDollarInlineCeIsRewritten() {
        val latex = inlineMathLatex(parseAndRender("water \$\$\\ce{H2O}\$\$ inline"))
        assertEquals(listOf("{\\mathrm{H_{2}O}}"), latex)
    }

    @Test
    fun blockMathCeIsRewritten() {
        val latex = blockMathLatex(parseAndRender("\$\$\n\\ce{2H2 + O2 -> 2H2O}\n\$\$"))
        assertEquals(1, latex.size)
        assertFalse("block latex must not contain \\ce{", latex[0].contains("\\ce{"))
        assertTrue("block latex must contain the arrow", latex[0].contains("\\longrightarrow"))
    }

    // --- untouched path: math without \ce keeps its payload byte-identical ---

    @Test
    fun mathWithoutCeIsUntouched() {
        val latex = inlineMathLatex(parseAndRender("energy \$E=mc^2\$ here"))
        assertEquals(listOf("E=mc^2"), latex)
    }

    // --- added fixture: \ce{} in code blocks and prose is structurally unreachable ---

    @Test
    fun ceInFencedCodeBlockIsNeverTouched() {
        val root = parseAndRender("```\n\\ce{H2O} raw in code\n```")
        var literal: String? = null
        root.accept(object : AbstractVisitor() {
            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                literal = fencedCodeBlock.literal
            }
        })
        assertEquals("\\ce{H2O} raw in code\n", literal)
    }

    @Test
    fun ceInPlainProseIsNeverTouched() {
        val root = parseAndRender("the literal \\ce{H2O} stays prose")
        val textContent = StringBuilder()
        root.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                textContent.append(text.literal)
            }
        })
        assertTrue(
            "prose must keep the literal \\ce{H2O}, got: \"$textContent\"",
            textContent.contains("\\ce{H2O}"),
        )
        assertEquals("prose must produce no math nodes", 0, inlineMathLatex(root).size)
    }
}
