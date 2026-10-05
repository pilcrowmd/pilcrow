// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.export.PdfContentLayoutBuilder
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-243 — a fence with attributes (```` ```python title="x" {2} ````, ```` ```python{2} ````, a tab
 * before them) is read as its first word, before any `{`, in every place that reads a fence's language:
 * Prism4j on screen and in the PDF, the TextMate path, and the Mermaid route. Only `---` front matter
 * becomes the metadata card (NEW-32), so a ```` ```yaml ```` fence with attributes stays a code block.
 */
@RunWith(RobolectricTestRunner::class)
class FenceAttributesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val withAttributes = listOf("%s title=\"x\" {2}", "%s{2}", "%s\t{2}", "%s\ttitle=x", "  %s  ")

    @Test
    fun `the language is the first word before any brace, lower-cased`() {
        val expected = mapOf(
            "python title=\"x\" {2}" to "python",
            "python{2}" to "python",
            "\tPython\t{2}" to "python",
            "rust\ttitle=x" to "rust",
            "{.python}" to "",
            "" to "",
            "   " to "",
            null to "",
        )
        for ((info, language) in expected) assertEquals("'$info'", language, fenceLanguage(info))
    }

    private fun render(markwon: Markwon, markdown: String): Spanned = markwon.render(markwon.parse(markdown)) as Spanned

    private fun colourAt(text: Spanned, probe: String): Int? {
        val at = text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        return text.getSpans(at, at + 1, ForegroundColorSpan::class.java)
            .minByOrNull { text.getSpanEnd(it) - text.getSpanStart(it) }
            ?.foregroundColor
    }

    @Test
    fun `Prism4j colours a fence with attributes on screen and in the PDF`() {
        val cases = listOf(
            buildPilcrowMarkwon(context, DarkColorScheme) to DarkColorScheme.codeSyntax.keyword.toArgb(),
            buildPrintMarkwon(context) to PrintColorScheme.codeSyntax.keyword.toArgb(),
        )
        for ((markwon, keyword) in cases) {
            for (pattern in withAttributes) {
                val info = pattern.format("python")
                val text = render(markwon, "```$info\ndef f(): pass\n```")
                assertEquals("'$info'", keyword, colourAt(text, "def"))
            }
        }
    }

    @Test
    fun `the TextMate path colours a fence with attributes`() {
        val highlighter = TextMateCodeHighlighter(context.assets::open, nanoTime = { 0L })
        val entry = FencedCodeBlockEntry(
            context,
            colorScheme = DarkColorScheme,
            highlighting = CodeHighlighting(highlighter, scope = null),
        )
        val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
        for (pattern in withAttributes) {
            val info = pattern.format("bash")
            val node = FencedCodeBlock().apply {
                this.info = info
                literal = "if true; then echo; fi\n"
            }
            val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
            entry.bindHolder(markwon, holder, node)
            val keyword = DarkColorScheme.codeSyntax.keyword.toArgb()
            assertEquals("'$info'", keyword, colourAt(holder.codeView.text as Spanned, "if"))
        }
    }

    /** The holder the reader's adapter binds the first block of [markdown] into. */
    private fun readerHolder(markdown: String): FencedCodeBlockEntry.Holder {
        val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, mermaidCloudEnabled = false)
        adapter.setMarkdown(markwon, markdown)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        return holder as FencedCodeBlockEntry.Holder
    }

    @Test
    fun `a Mermaid fence with attributes takes the Mermaid route`() {
        for (pattern in withAttributes) {
            val info = pattern.format("mermaid")
            val holder = readerHolder("```$info\ngraph TD\n```")
            assertEquals("'$info' caption", View.VISIBLE, holder.mermaidCaption.visibility)
        }
    }

    @Test
    fun `a YAML fence with attributes stays a code block, on screen and in the PDF`() {
        val builder = PdfContentLayoutBuilder(context)
        for (pattern in withAttributes) {
            val info = pattern.format("yaml")
            // The front-matter card draws `key: value` as a `key<TAB>value` row; the code route keeps the text.
            val reader = readerHolder("```$info\nkey: value\n```").codeView.text.toString()
            assertEquals("'$info' reader", true, reader.contains(CODE_TEXT) && !reader.contains(YAML_ROW))
            val node = FencedCodeBlock().apply {
                this.info = info
                literal = "key: value\n"
            }
            val view = builder.inflateMeasuredBlock(
                buildPrintMarkwon(context),
                node,
                LayoutInflater.from(context),
                PAGE_WIDTH_PX,
                1f,
            )
            val pdf = view.findViewById<TextView>(R.id.code_text).text.toString()
            assertEquals("'$info' PDF", true, pdf.contains(CODE_TEXT) && !pdf.contains(YAML_ROW))
        }
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
        const val YAML_ROW = "key\tvalue"
        const val CODE_TEXT = "key: value"
    }
}
