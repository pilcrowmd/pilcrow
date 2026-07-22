// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Single-`$` inline math tested against the REAL MarkwonRenderer parser config. A
 * `$...$` becomes math iff our processor emits a [JLatexMathNode] in the parse tree — the same node
 * the library emits for `$$...$$`, which JLatexMathPlugin's visitor renders into the
 * `JLatexAsyncDrawableSpan` the preview paints and the PDF sync-resolver walks.
 *
 * We assert on the parse TREE (not the rendered Spanned) deliberately: it pins our processor's
 * decision precisely and is free of JLatexMath's process-global render state (which makes
 * span-level assertions flaky across the shared-JVM suite). Actual on-screen/PDF rendering of the
 * node is covered by PdfExporterTest + on-device UAT.
 *
 * Committed rules: opener `$` not followed by space/digit · closer `$` not preceded by space and not
 * followed by a digit · closer not part of `$$` · non-empty · single-line (abort on `\n`) · `\$`
 * escapes a literal `$`.
 */
@RunWith(RobolectricTestRunner::class)
class InlineMathRenderingTest {

    // Built via buildPilcrowMarkwon (identical plugin chain to production) WITHOUT MarkwonRenderer's
    // init{} pre-warm thread — whose concurrent background parse would race this one on Markwon's
    // shared, stateful InlineProcessors and make the parse non-deterministic across the suite.
    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    /** Number of LaTeX math nodes (`$...$` ours + inline `$$...$$` the library's) in the parse tree. */
    private fun mathNodeCount(md: String): Int {
        var count = 0
        markwon.parse(md).accept(object : AbstractVisitor() {
            override fun visit(customNode: CustomNode) {
                if (customNode is JLatexMathNode) count++
                visitChildren(customNode)
            }
        })
        return count
    }

    private fun assertMath(md: String) = assertEquals("expected math in: \"$md\"", 1, mathNodeCount(md))

    private fun assertNotMath(md: String) = assertEquals("expected NO math in: \"$md\"", 0, mathNodeCount(md))

    // --- single-$ math renders ---

    @Test fun simpleVariable() = assertMath("a \$x\$ b")

    @Test fun equation() = assertMath("energy \$E=mc^2\$ here")

    @Test fun greekAndOps() = assertMath("the \$\\alpha + \\beta\$ sum")

    @Test fun inequalityWithSubscript() = assertMath("payoff when \$S_T > K\$ holds")

    @Test fun spacesInsideContentAreFine() = assertMath("value \$x = 5\$ today")

    @Test fun inlineNextToDisplayBothRender() {
        // one display $$…$$ (library) + one inline $…$ (ours) → two math nodes
        assertEquals(2, mathNodeCount("\$\$D\$\$ and \$i\$ together"))
    }

    // --- currency / prose must NOT render as math ---

    @Test fun bareDollarAmount() = assertNotMath("it costs \$5 today")

    @Test fun twoAmounts() = assertNotMath("\$100 to \$200 range")

    @Test fun amountPerUnit() = assertNotMath("\$5/mo plan")

    @Test fun decimalAmount() = assertNotMath("price \$1,250.50 net")

    @Test fun currencyAdjacency() = assertNotMath("\$US\$5 fee") // closer followed by digit

    @Test fun openerFollowedBySpace() = assertNotMath("a \$ 5 b")

    @Test fun twoAmountsInProse() = assertNotMath("I have \$20 and you have \$30")

    @Test fun shellStyleVars() = assertNotMath("set \$HOME and \$PATH now") // closer preceded by space

    // --- \$ escaping (user override) ---

    @Test fun escapedAmount() = assertNotMath("pay \\\$100 now")

    @Test fun escapedPair() = assertNotMath("\\\$x\\\$ literal")

    @Test fun mixedEscapeAndMath() {
        // "\$5" stays literal; "$x$" is math → exactly one math node
        assertEquals(1, mathNodeCount("cost is \\\$5 but \$x\$ is math"))
    }

    // --- single-line guard: an opener whose only partner is on another line stays literal ---

    @Test fun openerWithCloserOnNextLineIsLiteral() = assertNotMath("\$a\nb\$ end")

    // --- $$ display path unaffected (coexistence regression) ---

    @Test fun doubleDollarStillRenders() = assertMath("a \$\$y\$\$ b")

    @Test fun emptyOrSpacedDollarsAreLiteral() = assertNotMath("a \$ \$ b")
}
