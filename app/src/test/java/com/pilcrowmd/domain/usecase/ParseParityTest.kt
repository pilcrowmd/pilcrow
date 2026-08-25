// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.buildPilcrowMarkwon
import com.pilcrowmd.screenshot.MarkdownSampleProvider
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Enforces the block-model parity that search rests on: the `MarkwonAdapter` renders one item per
 * TOP-LEVEL node of `markwon.parse(content)`, while the TOC and search walk
 * [ParseMarkdownHeadingsUseCase.parseDocument]. If those two ever disagree about how many top-level
 * blocks a document has, adapter positions shift and both the TOC and search-scroll target the wrong
 * block — silently.
 *
 * That parity has been maintained by convention (the frontmatter `BlockParser` lives in the domain
 * layer precisely so both sides construct from one definition). Convention is not a mechanism, so
 * this test makes it a checked invariant. It retro-covers frontmatter, and any future custom block
 * parser added to one parser and forgotten in the other fails here first.
 *
 * **The invariant is the COUNT, not the types.** One type divergence exists by design and is pinned
 * below: with the `$$` fences on their own lines, ext-latex's block parser emits a `JLatexMathBlock`
 * in the render parser, where the parity parser — which deliberately omits the math plugins — sees an
 * ordinary `Paragraph`. Both are exactly ONE top-level block, so indices still line up, and both
 * contribute no searchable text (the math source is excluded from search by `mathRanges`). Any
 * divergence beyond that substitution is a real defect and fails.
 */
@RunWith(RobolectricTestRunner::class)
class ParseParityTest {

    private val parity = ParseMarkdownHeadingsUseCase()
    private val markwon by lazy { buildPilcrowMarkwon(ApplicationProvider.getApplicationContext()) }

    private fun topLevelTypes(node: Node): List<String> = buildList {
        var child = node.firstChild
        while (child != null) {
            add(child.javaClass.simpleName)
            child = child.next
        }
    }

    /** The one accepted substitution: a display-math block is a Paragraph to the parity parser. */
    private fun normalize(types: List<String>): List<String> =
        types.map { if (it == "JLatexMathBlock") "Paragraph" else it }

    private fun assertParity(name: String, markdown: String) {
        val render = topLevelTypes(markwon.parse(markdown))
        val search = topLevelTypes(parity.parseDocument(markdown)!!)
        assertEquals(
            "top-level BLOCK COUNT must match for \"$name\" — adapter positions depend on it " +
                "(render=$render, parity=$search)",
            render.size,
            search.size,
        )
        assertEquals(
            "top-level block TYPES diverge for \"$name\" beyond the accepted display-math " +
                "substitution (render=$render, parity=$search)",
            normalize(render),
            normalize(search),
        )
    }

    @Test
    fun everyGoldenSampleParsesToTheSameBlockSequence() {
        // The samples that drive the 115 committed goldens — i.e. every block type the app renders.
        MarkdownSampleProvider().values.forEach { assertParity(it.name, it.markdown) }
    }

    @Test
    fun blockLevelConstructsWithCustomParsersStayInParity() {
        // Frontmatter is the existing custom BlockParser; display math is the known substitution.
        assertParity("frontmatter", "---\ntitle: t\nauthor: a\n---\n\nbody")
        assertParity("frontmatter unterminated", "---\ntitle: t\n\nbody")
        assertParity("display math on own lines", "before\n\n\$\$\n\\int x\n\$\$\n\nafter")
        assertParity("display math inline", "before\n\n\$\$x\$\$\n\nafter")
        assertParity("inline math", "a \$x^2\$ b")
        assertParity("table then para", "| a | b |\n|---|---|\n| 1 | 2 |\n\nafter")
    }
}
