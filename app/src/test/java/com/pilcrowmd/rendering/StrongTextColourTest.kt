// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.AmoledColorScheme
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintCodeColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A48: bold takes the scheme's strong-text colour, white in Dark and AMOLED and the body colour in
 * Light and the PDF, while bold inside a link keeps the link colour whichever way the two nest.
 */
@RunWith(RobolectricTestRunner::class)
class StrongTextColourTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * The colour [word] is painted in: a paint in the TextView's body colour, then every character
     * span over the word applied in the order `getSpans` returns them, which is the order the
     * platform's `TextLine` applies them in, so a later span wins.
     */
    private fun paintedColour(scheme: PilcrowColorScheme, markdown: String, word: String, last: Boolean = false): Int =
        paintedColour(buildPilcrowMarkwon(context, scheme), scheme, markdown, word, last)

    private fun paintedColour(
        markwon: Markwon,
        scheme: PilcrowColorScheme,
        markdown: String,
        word: String,
        last: Boolean = false,
    ): Int {
        val text: Spanned = markwon.toMarkdown(markdown)
        val start = (if (last) text.lastIndexOf(word) else text.indexOf(word))
            .also { check(it >= 0) { "'$word' not in '$text'" } }
        val paint = TextPaint().apply { color = scheme.primaryText.toArgb() }
        text.getSpans(start, start + word.length, CharacterStyle::class.java).forEach { it.updateDrawState(paint) }
        return paint.color
    }

    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `bold is drawn in white in Dark and AMOLED`() {
        assertEquals(white, paintedColour(DarkColorScheme, "plain **strong** plain", "strong"))
        assertEquals(white, paintedColour(AmoledColorScheme, "plain **strong** plain", "strong"))
    }

    @Test
    fun `bold keeps the body colour in Light and the PDF`() {
        for (scheme in listOf(LightColorScheme, PrintColorScheme)) {
            assertEquals(scheme.primaryText.toArgb(), paintedColour(scheme, "plain **strong** plain", "strong"))
        }
        // The PDF's own instance, which renders with PrintCodeColorScheme.
        val pdf = paintedColour(buildPrintMarkwon(context), PrintCodeColorScheme, "plain **strong** plain", "strong")
        assertEquals("PDF bold", PrintColorScheme.primaryText.toArgb(), pdf)
        // No colour span at all, so bold inside text of another colour (a quote, a title) keeps that colour.
        val markwons = listOf(LightColorScheme, PrintColorScheme).map { buildPilcrowMarkwon(context, it) } +
            buildPrintMarkwon(context)
        for (markwon in markwons) {
            val text = markwon.toMarkdown("plain **strong** plain")
            assertEquals("colour spans", 0, text.getSpans(0, text.length, ForegroundColorSpan::class.java).size)
        }
    }

    @Test
    fun `normal text keeps the body colour in every scheme`() {
        for (scheme in listOf(DarkColorScheme, AmoledColorScheme, LightColorScheme, PrintColorScheme)) {
            val md = "plain **strong** plain"
            assertEquals(scheme.primaryText.toArgb(), paintedColour(scheme, md, "plain"))
            assertEquals("after the bold", scheme.primaryText.toArgb(), paintedColour(scheme, md, "plain", last = true))
        }
    }

    @Test
    fun `bold around a link keeps the link colour`() {
        for (scheme in listOf(DarkColorScheme, AmoledColorScheme)) {
            val md = "see **before [linked](https://example.org) after** end"
            assertEquals("link inside bold", scheme.link.toArgb(), paintedColour(scheme, md, "linked"))
            assertEquals("bold beside the link", white, paintedColour(scheme, md, "before"))
        }
    }

    @Test
    fun `bold inside a link keeps the link colour`() {
        for (scheme in listOf(DarkColorScheme, AmoledColorScheme)) {
            val md = "see [**linked**](https://example.org) end"
            assertEquals(scheme.link.toArgb(), paintedColour(scheme, md, "linked"))
        }
    }
}
