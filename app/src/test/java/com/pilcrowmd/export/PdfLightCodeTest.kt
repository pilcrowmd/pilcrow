// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.rendering.buildPrintMarkwon
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.core.spans.CodeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.pow

/**
 * M-178 — code in every PDF takes the Light theme's code colours, whatever the app theme: the fenced
 * code panel and the inline code chip. Each block goes through the export's own path (the print
 * Markwon and [PdfContentLayoutBuilder]), so the colours read here are the ones the page is drawn with.
 *
 * The last two tests are controls: the yaml card and the table borders are NOT code and keep
 * [PrintColorScheme]. They pass before and after M-178; they exist so that turning either light is a
 * deliberate change, not a side effect.
 */
@RunWith(RobolectricTestRunner::class)
class PdfLightCodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The measured PDF view for the single top-level block in [markdown]. */
    private fun pdfBlock(markdown: String): View {
        val markwon = buildPrintMarkwon(context)
        val node = parseTopLevelBlocks(markwon, markdown).single()
        return PdfContentLayoutBuilder(context)
            .inflateMeasuredBlock(markwon, node, LayoutInflater.from(context), PAGE_WIDTH_PX, 1f)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun panel(view: View): GradientDrawable =
        view.findViewById<View>(R.id.code_scroll).background as GradientDrawable

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun contrast(a: Int, b: Int): Double {
        fun luminance(argb: Int): Double = Color(argb).let {
            0.2126 * channel(it.red) + 0.7152 * channel(it.green) + 0.0722 * channel(it.blue)
        }
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun inlineCodeIsDrawnOnTheLightChipInLightCodeText() {
        val text = descendants(pdfBlock("Para `code` here.")).filterIsInstance<TextView>().first()
        val spanned = text.text as Spanned
        val span = spanned.getSpans(0, spanned.length, CodeSpan::class.java).single()
        // What CodeSpan does to the paint the TextView draws the chip with.
        val paint = TextPaint().apply { color = text.currentTextColor }
        span.updateDrawState(paint)

        assertEquals("chip", LightColorScheme.inlineCodeBg.toArgb(), paint.bgColor)
        assertEquals("inline code text", LightColorScheme.editorText.toArgb(), paint.color)
        val ratio = contrast(paint.color, paint.bgColor)
        assertTrue("inline code is %.2f:1 on its chip, below 4.5:1".format(ratio), ratio >= 4.5)
    }

    @Test
    fun theFencedCodePanelIsTheLightCodePanel() {
        val panel = panel(pdfBlock("```kotlin\nval x = 1\n```"))
        assertEquals("panel", LightColorScheme.codeBlockBg.toArgb(), panel.color!!.defaultColor)
        assertEquals("panel border", LightColorScheme.codeBlockBorder.toArgb(), shadowOf(panel).strokeColor)
    }

    @Test
    fun control_theYamlCardKeepsThePrintScheme() {
        // Front matter, not a ```yaml fence: since M-198 only `---…---` is the card, and a ```yaml
        // fence is an ordinary code block that takes the light panel like any other.
        val card = panel(pdfBlock("---\ntitle: x\n---"))
        assertEquals("card", PrintColorScheme.secondarySurface.toArgb(), card.color!!.defaultColor)
        assertEquals("card border", PrintColorScheme.lightBorder.toArgb(), shadowOf(card).strokeColor)
    }

    @Test
    fun control_tableBordersKeepThePrintScheme() {
        val cell = descendants(pdfBlock("| a | b |\n|---|---|\n| 1 | 2 |"))
            .filterIsInstance<TextView>()
            .firstOrNull { it.background is GradientDrawable }
        assertNotNull("no table cell with a drawn background", cell)
        val stroke = shadowOf(cell!!.background as GradientDrawable).strokeColor
        assertEquals("cell border", PrintColorScheme.codeBlockBorder.toArgb(), stroke)
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
    }
}
