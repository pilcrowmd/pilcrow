// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.html.span.SubScriptSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-246 / M-245 — HTML `<code>` and `<kbd>` are drawn exactly as Markdown inline code is.
 *
 * All three runs sit in ONE paragraph, so the only thing that can put a span over `y` or `Ctrl` is
 * the tag's own handler: the paragraph adds none, and Markdown code covers `x` alone. Without the
 * handlers HtmlPlugin strips both tags and leaves the text plain.
 */
@RunWith(RobolectricTestRunner::class)
class HtmlCodeTagTest {

    private lateinit var markwon: Markwon
    private lateinit var spanned: Spanned

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
        spanned = markwon.render(markwon.parse("a `x` b <code>y</code> c <kbd>Ctrl</kbd> d")) as Spanned
    }

    /** The spans that cover the whole of [word], by class name, sorted. */
    private fun spanClassesOver(word: String): List<String> {
        val start = spanned.toString().indexOf(word)
        assertTrue("'$word' is in the rendered text '$spanned'", start >= 0)
        val end = start + word.length
        return spanned.getSpans(start, end, Any::class.java)
            .filter { spanned.getSpanStart(it) <= start && spanned.getSpanEnd(it) >= end }
            .map { it.javaClass.name }
            .sorted()
    }

    /** The background the spans over [word] paint, as Markwon applies them when drawing. */
    private fun backgroundOver(word: String): Int {
        val start = spanned.toString().indexOf(word)
        val paint = TextPaint()
        spanned.getSpans(start, start + word.length, CharacterStyle::class.java).forEach { it.updateDrawState(paint) }
        return paint.bgColor
    }

    @Test
    fun `markdown inline code is the reference`() {
        assertTrue("inline code has a span", spanClassesOver("x").isNotEmpty())
        assertEquals(DarkColorScheme.inlineCodeBg.toArgb(), backgroundOver("x"))
    }

    @Test
    fun `html code tag gets the inline code span`() {
        assertEquals(spanClassesOver("x"), spanClassesOver("y"))
        assertEquals(DarkColorScheme.inlineCodeBg.toArgb(), backgroundOver("y"))
    }

    @Test
    fun `html kbd tag gets the inline code span`() {
        assertEquals(spanClassesOver("x"), spanClassesOver("Ctrl"))
        assertEquals(DarkColorScheme.inlineCodeBg.toArgb(), backgroundOver("Ctrl"))
    }

    @Test
    fun `the default html handlers are kept`() {
        val sub = markwon.render(markwon.parse("H<sub>2</sub>O")) as Spanned
        assertEquals(1, sub.getSpans(0, sub.length, SubScriptSpan::class.java).size)
    }

    /** An HTML `<pre><code>` block still renders, both lines kept, as one inline-code run (no crash). */
    @Test
    fun `an html pre code block renders its text as inline code`() {
        val block = markwon.render(markwon.parse("<pre><code>val a = 1\nval b = 2</code></pre>")) as Spanned
        assertEquals("val a = 1\nval b = 2", block.toString())
        val spans = block.getSpans(0, block.length, Any::class.java)
        assertEquals(listOf(CodeSpan::class.java), spans.map { it.javaClass })
        assertEquals(0 to block.length, block.getSpanStart(spans[0]) to block.getSpanEnd(spans[0]))
    }
}
