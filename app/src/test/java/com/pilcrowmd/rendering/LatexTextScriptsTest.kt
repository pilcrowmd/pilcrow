// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.scilab.forge.jlatexmath.DefaultTeXFont
import org.scilab.forge.jlatexmath.TeXParser
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * M-197 (issue #9): Greek and Cyrillic letters inside `\text{}` render, in any order.
 *
 * JLaTeXMath loads each of those alphabets from a language package on first use. The core library
 * registers both but ships neither, so the first one used threw, and left `TeXParser.isLoading` stuck
 * `true` for the rest of the process. The app now ships both packages as assets
 * (`org/scilab/forge/jlatexmath/fonts/language_{greek,cyrillic}.xml` and the files they include).
 *
 * JLaTeXMath keeps its alphabet state in statics, so the whole sequence is one test: Cyrillic first
 * (the order that broke both before), then Greek, then the issue's formula, then the same formula
 * through the reader's own Markwon, where a parse failure would show the source instead (M-260).
 * Moving the package assets aside fails the first step.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatexTextScriptsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun greekAndCyrillicInsideTextRenderInEitherOrder() {
        // Robolectric does not run the library's self-init ContentProvider (see PdfExporterTest).
        JLatexMathAndroid.init(context)

        assertBuilds("\\text{привет}")
        assertBuilds("\\text{αλλιώς}")
        assertBuilds(ISSUE_FORMULA)

        assertFalse("the alphabet loader is not left stuck", isLoading())
        // Drawn from the shipped packages, not from the system font the stuck loader falls back to.
        assertTrue(
            "both packages loaded: ${DefaultTeXFont.loadedAlphabets}",
            DefaultTeXFont.loadedAlphabets.containsAll(
                listOf(Character.UnicodeBlock.CYRILLIC, Character.UnicodeBlock.GREEK),
            ),
        )

        val text: Spanned = buildPilcrowMarkwon(context).toMarkdown("\$\$\n$ISSUE_FORMULA\n\$\$")
        assertEquals(
            "the reader keeps the formula, not its source",
            0,
            text.getSpans(0, text.length, MathSourceSpan::class.java).size,
        )
        assertEquals(1, text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).size)
    }

    private fun assertBuilds(latex: String) {
        val drawable = runCatching { JLatexMathDrawable.builder(latex).textSize(TEXT_SIZE_PX).build() }
            .getOrElse { throw AssertionError("'$latex' does not build: $it", it) }
        assertTrue("'$latex' has a width", drawable.intrinsicWidth > 0)
    }

    private fun isLoading(): Boolean =
        TeXParser::class.java.getDeclaredField("isLoading").apply { isAccessible = true }.getBoolean(null)

    private companion object {
        const val TEXT_SIZE_PX = 60f

        /** The formula from issue #9, as reported. */
        const val ISSUE_FORMULA = "f(x)=\\begin{cases}1, & \\text{αν } x>0\\\\0, & \\text{αλλιώς}\\end{cases}"
    }
}
