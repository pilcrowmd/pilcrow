// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightCodeSyntax
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-187 — a fence named by a common alias of a bundled grammar (`python3`, `kt`) takes that grammar's
 * colours, on screen and in the PDF, and so does a capitalised name (`Python`).
 *
 * The probe is the block's first keyword, read as the innermost [ForegroundColorSpan] over it (the
 * [CodeTokenColoursTest] pattern). With no grammar for the name, Prism4j leaves the block plain and the
 * keyword is drawn in the default code text colour, so the alias line is the only thing that can make
 * the probe pass.
 */
@RunWith(RobolectricTestRunner::class)
class FenceAliasColoursTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val python = "def greet():\n    return 1"
    private val kotlin = "fun greet() = 1"

    private fun render(markwon: Markwon, markdown: String): Spanned = markwon.render(markwon.parse(markdown)) as Spanned

    /** Colour of the innermost foreground span over the first character of [probe]. */
    private fun colourAt(text: Spanned, probe: String): Int? {
        val at = text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        return text.getSpans(at, at + 1, ForegroundColorSpan::class.java)
            .minByOrNull { text.getSpanEnd(it) - text.getSpanStart(it) }
            ?.foregroundColor
    }

    private fun keywordColour(markwon: Markwon, fence: String, code: String, keyword: String): Int? =
        colourAt(render(markwon, "```$fence\n$code\n```"), keyword)

    private val reader by lazy { buildPilcrowMarkwon(context, DarkColorScheme) }
    private val dark = DarkColorScheme.codeSyntax.keyword.toArgb()

    @Test
    fun aPython3FenceIsHighlightedAsPython() =
        assertEquals("python3", dark, keywordColour(reader, "python3", python, "def"))

    @Test
    fun aCapitalisedPythonFenceIsHighlightedAsPython() =
        assertEquals("Python", dark, keywordColour(reader, "Python", python, "def"))

    @Test
    fun aKtFenceIsHighlightedAsKotlin() = assertEquals("kt", dark, keywordColour(reader, "kt", kotlin, "fun"))

    @Test
    fun thePdfInstanceHighlightsAPython3FenceAsPython() = assertEquals(
        "python3 in the PDF",
        LightCodeSyntax.keyword.toArgb(),
        keywordColour(buildPrintMarkwon(context), "python3", python, "def"),
    )
}
