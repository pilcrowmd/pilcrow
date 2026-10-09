// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * M-225: display maths with an equation number (`\tag{1}`) rendered as its source, because
 * JLaTeXMath has no `\tag`. The reader rewrites `\tag`, `\tag*`, `\notag` and `\nonumber` before
 * the formula is parsed ([EquationTagShim]), so the formula renders; the number sits a `\qquad`
 * after it rather than right-aligned.
 *
 * Built on the real chain ([buildPilcrowMarkwon]) and asserted on spans: a formula that renders
 * carries the plugin's [JLatexAsyncDrawableSpan]; one that fails carries [MathSourceSpan].
 */
@RunWith(RobolectricTestRunner::class)
class MathTagRenderingTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    private val body = "P(A\\mid B)=\\frac{P(B\\mid A)P(A)}{P(B)},\\qquad\n" +
        "\\hat\\beta=(X^{\\mathsf T}X)^{-1}X^{\\mathsf T}y"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric does not run the library's self-init ContentProvider (see PdfExporterTest).
        JLatexMathAndroid.init(context)
        markwon = buildPilcrowMarkwon(context)
    }

    /** Settles the cause: the library rejects `\tag` and accepts everything else in the fixture. */
    @Test
    fun theLibraryRejectsTagAndAcceptsTheRestOfTheFixture() {
        TeXFormula(body)
        try {
            TeXFormula("$body\n\\tag{1}")
            fail("JLaTeXMath parsed \\tag; the rewrite may no longer be needed")
        } catch (expected: Exception) {
            // the cause of M-225
        }
    }

    @Test
    fun displayFormulaWithTagRenders() = assertRenders("\$\$\n$body\n\\tag{1}\n\$\$")

    @Test
    fun starredTagRenders() = assertRenders("\$\$\nx = y \\tag*{a}\n\$\$")

    @Test
    fun notagAndNonumberRender() = assertRenders("\$\$\nx = y \\notag + z \\nonumber\n\$\$")

    @Test
    fun inlineFormulaWithTagRenders() = assertRenders("Before \$x = y \\tag{2}\$ after.")

    /** A `\tag{` that never closes is not guessed at: it still fails, and shows its source. */
    @Test
    fun unclosedTagFallsBackToSourceWithoutThrowing() {
        val text = rendered("\$\$\nx = y \\tag{1\n\$\$")
        assertTrue("no image span", latexSpans(text).isEmpty())
        assertEquals(1, sourceSpans(text).size)
        assertTrue("the source is shown unchanged", text.toString().contains("x = y \\tag{1"))
    }

    private fun assertRenders(markdown: String) {
        val text = rendered(markdown)
        assertTrue("the formula falls back to its source: $text", sourceSpans(text).isEmpty())
        assertEquals(1, latexSpans(text).size)
    }

    private fun rendered(markdown: String): Spanned = markwon.render(markwon.parse(markdown))

    private fun latexSpans(text: Spanned) = text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).toList()

    private fun sourceSpans(text: Spanned) = text.getSpans(0, text.length, MathSourceSpan::class.java).toList()
}
