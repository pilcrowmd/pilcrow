// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.export.PdfContentLayoutBuilder
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-136 — bash, TypeScript, Rust and Ruby blocks take their token colours through the real entry,
 * the real Markwon instance and the real grammars, in Dark, Light and the PDF.
 *
 * Each probe names a character only one token covers and reads the innermost [ForegroundColorSpan]
 * over it. Prism4j has no grammar for these fences, so without the TextMate path nothing colours any
 * probe: every assertion fails.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderCodeGrammarsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A clock that never moves: the 1 s block budget can never decide these tests, however slow the
    // machine, so the 2,050-line test is decided by the line limit alone.
    private val highlighter = TextMateCodeHighlighter(context.assets::open, nanoTime = { 0L })

    /** keyword, string and comment probes per fence. */
    private class Probe(
        val fence: String,
        val code: String,
        val keyword: String,
        val string: String,
        val comment: String,
    )

    private val probes = listOf(
        Probe("bash", "if [ -n \"\$X\" ]; then\n  echo \"hello\" # say it\nfi", "if", "\"hello\"", "# say"),
        Probe("ts", "const s: string = \"hello\"; // say it\nexport {}", "const", "\"hello\"", "// say"),
        Probe("rust", "fn main() {\n    let s = \"hello\"; // say it\n}", "fn", "\"hello\"", "// say"),
        Probe("ruby", "def hi\n  puts \"hello\" # say it\nend", "def", "\"hello\"", "# say"),
    )

    private fun node(fence: String, code: String) = FencedCodeBlock().apply {
        info = fence
        literal = "$code\n"
    }

    private fun bindInline(
        scheme: PilcrowColorScheme,
        node: FencedCodeBlock,
        search: SearchHighlight = SearchHighlight(),
    ): TextView {
        val markwon = buildPilcrowMarkwon(context, scheme)
        val entry = FencedCodeBlockEntry(
            context,
            colorScheme = scheme,
            searchHighlight = search,
            highlighting = CodeHighlighting(highlighter, scope = null),
        )
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, node)
        return holder.codeView
    }

    private fun colourAt(text: CharSequence, probe: String): Int? {
        val spanned = text as Spanned
        val at = spanned.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        return spanned.getSpans(at, at + 1, ForegroundColorSpan::class.java)
            .minByOrNull { spanned.getSpanEnd(it) - spanned.getSpanStart(it) }
            ?.foregroundColor
    }

    private fun assertProbes(scheme: PilcrowColorScheme, render: (FencedCodeBlock) -> CharSequence) {
        val syntax = scheme.codeSyntax
        for (p in probes) {
            val text = render(node(p.fence, p.code))
            assertEquals("${p.fence} keyword", syntax.keyword.toArgb(), colourAt(text, p.keyword))
            assertEquals("${p.fence} string", syntax.string.toArgb(), colourAt(text, p.string))
            assertEquals("${p.fence} comment", syntax.comment.toArgb(), colourAt(text, p.comment))
        }
    }

    @Test
    fun `the four languages are coloured in Dark`() =
        assertProbes(DarkColorScheme) { bindInline(DarkColorScheme, it).text }

    @Test
    fun `the four languages are coloured in Light`() =
        assertProbes(LightColorScheme) { bindInline(LightColorScheme, it).text }

    @Test
    fun `the four languages are coloured in the PDF`() {
        val print = buildPrintMarkwon(context)
        val builder = PdfContentLayoutBuilder(context, highlighter)
        assertProbes(PrintColorScheme) { node ->
            val view = builder.inflateMeasuredBlock(print, node, LayoutInflater.from(context), PAGE_WIDTH_PX, 1f)
            view.findViewById<TextView>(R.id.code_text).text
        }
    }

    @Test
    fun `console and tsx stay plain`() {
        for (fence in listOf("console", "tsx")) {
            val text = bindInline(DarkColorScheme, node(fence, "if true; then echo \"hello\"; fi")).text as Spanned
            val colours = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                .map { it.foregroundColor }.toSet()
            assertEquals("$fence colours", emptySet<Int>(), colours - DarkColorScheme.editorText.toArgb())
        }
    }

    @Test
    fun `a block past the line limit renders whole, head coloured and tail plain`() {
        val code = (1..TextMateCodeHighlighter.MAX_LINES + 50).joinToString("\n") { "echo \"line $it\"" }
        val text = bindInline(DarkColorScheme, node("bash", code)).text as Spanned
        val lastLine = "echo \"line ${TextMateCodeHighlighter.MAX_LINES + 50}\""
        assertTrue("the whole block is shown", text.toString().contains(lastLine))
        assertEquals(DarkColorScheme.codeSyntax.function.toArgb(), colourAt(text, "echo \"line 1\""))
        val tail = text.indexOf("echo \"line ${TextMateCodeHighlighter.MAX_LINES + 1}\"")
        assertTrue(
            "the tail has no token colour",
            text.getSpans(tail, text.length, ForegroundColorSpan::class.java).none { text.getSpanEnd(it) > tail },
        )
    }

    @Test
    fun `a search highlight still applies over coloured code`() {
        val search = SearchHighlight(
            query = "hello",
            focusedPosition = -1,
            otherColor = SEARCH_COLOUR,
            focusedColor = SEARCH_COLOUR,
        )
        val text = bindInline(DarkColorScheme, node("bash", "echo \"hello\""), search).text as Spanned
        val at = text.indexOf("hello")
        assertEquals("token colour", DarkColorScheme.codeSyntax.string.toArgb(), colourAt(text, "hello"))
        assertTrue(
            "search highlight",
            text.getSpans(at, at + 1, BackgroundColorSpan::class.java).any { it.backgroundColor == SEARCH_COLOUR },
        )
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
        const val SEARCH_COLOUR = 0xFF123456.toInt()
    }
}
