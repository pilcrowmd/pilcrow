// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards issue #13: Prism4j colours a code block on the main thread, and a long json block froze the
 * reader (ANR). Past [MAX_HIGHLIGHT_CHARS] a block is left plain. Both blocks below are cut from the
 * same json text, so the length is the only thing that differs between them.
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
    fun `a json block over the cap is left plain, on screen and in the PDF`() {
        val over = json.take(MAX_HIGHLIGHT_CHARS + 1)
        assertEquals(0, colourSpans(warmedMarkwonRenderer(context).markwon, over))
        assertEquals(0, colourSpans(buildPrintMarkwon(context), over))
    }
}
