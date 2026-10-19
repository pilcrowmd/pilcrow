// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Typeface
import android.text.Spanned
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.core.spans.BulletListItemSpan
import org.commonmark.node.HtmlBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-161 through the real render chain. The painted text is asserted character for character,
 * because search models exactly that text (SearchCalloutsAndDetailsTest) and the two must agree.
 */
@RunWith(RobolectricTestRunner::class)
class CalloutAndDetailsRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val markwon = buildPilcrowMarkwon(context)

    private fun firstBlock(markdown: String) = ReaderTree.build(markwon, markdown, plain = false).firstChild!!

    @Test
    fun `the render parser's split marker still makes a callout`() {
        // Markwon's inline parser may hand the marker over as several Text nodes; the transform
        // reads the whole first line, so this is the case that proves it.
        val node = firstBlock("> [!WARNING]\n> Mind the gap.")
        assertTrue(node is CalloutBlock)
        assertEquals(CalloutKind.WARNING, (node as CalloutBlock).kind)
    }

    @Test
    fun `a callout paints its title then its body, with no quote bar, and the title takes its colour`() {
        val rendered = markwon.render(firstBlock("> [!TIP]\n> Mind the gap.")) as Spanned
        assertEquals("Tip\nMind the gap.", rendered.toString().trim())
        assertEquals(0, rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java).size)
        CalloutStyle.decorateTitles(context, rendered, LightColorScheme, 1f)
        val title = rendered.getSpans(0, rendered.length, CalloutTitleSpan::class.java).single()
        assertEquals(LightColorScheme.callouts.tip.toArgb(), title.color)
        assertEquals(
            "the icon sits in the title's margin",
            1,
            rendered.getSpans(0, 3, LeadingIconSpan::class.java).size,
        )
    }

    @Test
    fun `a details header paints its summary, and its inline body only while open`() {
        val header = Details.parseHeader(
            (firstBlock("<details><summary>Tight</summary>Its body.</details>") as HtmlBlock).literal,
        )!!
        val style = DetailsHeaderStyle(Typeface.DEFAULT_BOLD, DarkColorScheme.accent.toArgb(), 1f)
        val closed = detailsHeaderText(context, header, expanded = false, style)
        val open = detailsHeaderText(context, header, expanded = true, style)
        assertEquals("Tight", closed.toString())
        assertEquals("Tight\nIts body.", open.toString())
    }

    @Test
    fun `an unknown callout type still renders as a quote`() {
        val rendered = markwon.render(firstBlock("> [!FOO]\n> Body")) as Spanned
        assertEquals(1, rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java).size)
        assertTrue(rendered.toString().contains("[!FOO]"))
    }

    /** The painted text, and the title span's exact range in it. */
    private fun paint(markdown: String): Pair<String, String> {
        val rendered = markwon.render(firstBlock(markdown)) as Spanned
        val title = rendered.getSpans(0, rendered.length, CalloutTitleSpan::class.java).single()
        return rendered.toString().trim() to
            rendered.subSequence(rendered.getSpanStart(title), rendered.getSpanEnd(title)).toString()
    }

    @Test
    fun `a custom title is painted in place of the kind's title`() {
        val (text, title) = paint("> [!NOTE] Custom callout title\n> Mind the gap.")
        assertEquals("Custom callout title\nMind the gap.", text)
        assertEquals("Custom callout title", title)
    }

    @Test
    fun `a fold sign is not painted, and the callout is shown open`() {
        assertEquals("Collapsible\nMind the gap." to "Collapsible", paint("> [!TIP]- Collapsible\n> Mind the gap."))
        assertEquals("Starts open\nMind the gap." to "Starts open", paint("> [!WARNING]+ Starts open\n> Mind the gap."))
        assertEquals("Tip\nMind the gap." to "Tip", paint("> [!TIP]-\n> Mind the gap."))
    }

    @Test
    fun `a Python-Markdown admonition paints its title then its body, with no quote bar`() {
        val markdown = "!!! info \"Fieldwork note\"\n    Rainfall was measured at 07:00."
        assertEquals("Fieldwork note\nRainfall was measured at 07:00." to "Fieldwork note", paint(markdown))
        val rendered = markwon.render(firstBlock(markdown)) as Spanned
        assertEquals(0, rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java).size)
    }

    @Test
    fun `a fenced admonition paints its kind's title then its body`() {
        assertEquals(
            "Warning\nNot supported by CommonMark." to "Warning",
            paint("::: warning\nNot supported by CommonMark.\n:::"),
        )
    }

    @Test
    fun `an admonition body renders exactly as a GitHub alert body does`() {
        val alert = markwon.render(firstBlock("> [!NOTE]\n> One **two**.\n>\n> - a\n> - b")) as Spanned
        for (markdown in listOf(
            "!!! note\n    One **two**.\n\n    - a\n    - b",
            "::: note\nOne **two**.\n\n- a\n- b\n:::",
        )) {
            val admonition = markwon.render(firstBlock(markdown)) as Spanned
            assertEquals(markdown, alert.toString(), admonition.toString())
            assertEquals(
                markdown,
                alert.getSpans(0, alert.length, Any::class.java).map { it.javaClass.name to alert.getSpanStart(it) },
                admonition.getSpans(0, admonition.length, Any::class.java)
                    .map { it.javaClass.name to admonition.getSpanStart(it) },
            )
            assertEquals(2, admonition.getSpans(0, admonition.length, BulletListItemSpan::class.java).size)
        }
    }

    @Test
    fun `a formula in a title is painted as its source, which is the title search models`() {
        val markdown = "> [!NOTE] Energy \$E=mc^2\$\n> mc here"
        val (text, title) = paint(markdown)
        assertEquals("Energy \$E=mc^2\$", title)
        val parse = ParseMarkdownHeadingsUseCase()
        assertEquals(title, (parse.parseDocument(markdown)!!.firstChild as CalloutBlock).paintedTitle)
        val painted = Regex("mc").findAll(text).count()
        assertEquals(2, painted)
        assertEquals(painted, SearchMarkdownUseCase(parse).findSearchMatches(markdown, "mc").size)
        assertEquals("\$x\$", paint("> [!NOTE] \$x\$\n> Body").second)
    }
}
