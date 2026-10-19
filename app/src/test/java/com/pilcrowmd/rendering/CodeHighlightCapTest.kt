// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.export.PdfContentLayoutBuilder
import com.pilcrowmd.ui.theme.PrintCodeColorScheme
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards issue #13: Prism4j colours a code block on the main thread, and a long json block froze the
 * reader (ANR). Past [MAX_HIGHLIGHT_CHARS] Markwon's own render leaves a block plain, which is what
 * still protects Main at bind. Issue #17 colours such a block some other way, so it is not left plain:
 * in the reader off Main, a piece at a time (LongCodeBlockColourTest), and in the PDF in full, inline.
 * The blocks below are cut from the same json text, so the length is the only thing that differs.
 */
@RunWith(RobolectricTestRunner::class)
class CodeHighlightCapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val json: String = buildString {
        append("[\n")
        var i = 0
        while (length <= MAX_HIGHLIGHT_CHARS) {
            append("  { \"id\": $i, \"name\": \"item-$i\", \"active\": true, \"note\": null },\n")
            i++
        }
    }

    private fun colourSpans(markwon: Markwon, code: String): Int {
        val node = FencedCodeBlock().apply {
            info = "json"
            literal = code
        }
        val text = markwon.render(node) as Spanned
        return text.getSpans(0, text.length, ForegroundColorSpan::class.java).size
    }

    @Test
    fun `a small json block is still coloured`() {
        val small = json.take(1_000)
        assertTrue(colourSpans(warmedMarkwonRenderer(context).markwon, small) > 0)
    }

    @Test
    fun `a json block exactly at the cap is still coloured`() {
        val atCap = json.take(MAX_HIGHLIGHT_CHARS)
        assertTrue(colourSpans(warmedMarkwonRenderer(context).markwon, atCap) > 0)
    }

    @Test
    fun `Markwon's own render of a json block over the cap is still plain`() {
        val over = json.take(MAX_HIGHLIGHT_CHARS + 1)
        assertEquals(0, colourSpans(warmedMarkwonRenderer(context).markwon, over))
        assertEquals(0, colourSpans(buildPrintMarkwon(context), over))
    }

    private fun overCap() = FencedCodeBlock().apply {
        info = "json"
        // Whole lines: the last one must be a complete json line to carry a string.
        literal = json
    }

    private fun assertLastLineIsColoured(text: CharSequence) {
        val spanned = text as Spanned
        val string = PrintCodeColorScheme.codeSyntax.string.toArgb()
        val at = spanned.lastIndexOf("\"item-")
        assertTrue("the last line is shown", at >= 0)
        assertTrue(
            "the last line's string has a role colour",
            spanned.getSpans(at + 1, at + 2, ForegroundColorSpan::class.java).any { it.foregroundColor == string },
        )
    }

    @Test
    fun `the PDF colours a json block over the cap in full, through the code entry`() {
        check(json.length > MAX_HIGHLIGHT_CHARS)
        val entry = FencedCodeBlockEntry(
            context,
            colorScheme = PrintCodeColorScheme,
            highlighting = CodeHighlighting(
                TextMateCodeHighlighter(context.assets::open),
                scope = null,
                prism4j = Prism4jCodeHighlighter(),
            ),
        )
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(buildPrintMarkwon(context), holder, overCap())
        assertLastLineIsColoured(holder.codeView.text)
    }

    @Test
    fun `the PDF builder colours a json block over the cap in full`() {
        val builder = PdfContentLayoutBuilder(
            context,
            TextMateCodeHighlighter(context.assets::open),
            prism4jHighlighter = Prism4jCodeHighlighter(),
        )
        val view = builder.inflateMeasuredBlock(
            buildPrintMarkwon(context),
            overCap(),
            LayoutInflater.from(context),
            PAGE_WIDTH_PX,
            1f,
        )
        assertLastLineIsColoured(view.findViewById<TextView>(R.id.code_text).text)
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
    }
}
