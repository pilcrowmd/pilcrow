// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.domain.usecase.findSearchMatches
import com.pilcrowmd.ui.theme.AmoledColorScheme
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A88: `==marked==` paints on the scheme's highlight colour with the markers hidden, stays plain
 * where the spec says it must (`a == b`, code), and search, the contents list and the parity parser all
 * read it the way the reader paints it.
 */
@RunWith(RobolectricTestRunner::class)
class HighlightTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val schemes = listOf(DarkColorScheme, AmoledColorScheme, LightColorScheme, PrintColorScheme)

    private fun render(markdown: String, scheme: PilcrowColorScheme = DarkColorScheme): Spanned =
        buildPilcrowMarkwon(context, scheme).toMarkdown(markdown)

    /** The text ranges carrying the highlight colour, as strings. */
    private fun highlighted(text: Spanned, scheme: PilcrowColorScheme): List<String> =
        text.getSpans(0, text.length, BackgroundColorSpan::class.java)
            .filter { it.backgroundColor == scheme.highlight.toArgb() }
            .map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }

    @Test
    fun `marked text is painted on the highlight colour with the markers hidden`() {
        for (scheme in schemes) {
            val text = render("a ==marked== b", scheme)
            assertEquals("a marked b", text.toString())
            assertEquals(listOf("marked"), highlighted(text, scheme))
        }
        // The PDF's own instance, in both of its colour options.
        for (scheme in listOf(PrintColorScheme, DarkColorScheme)) {
            val pdf = buildPrintMarkwon(context, codeColors = scheme).toMarkdown("a ==marked== b")
            assertEquals("a marked b", pdf.toString())
            assertEquals(listOf("marked"), highlighted(pdf, scheme))
        }
    }

    @Test
    fun `the highlight is a soft colour per theme, not the body or search colour`() {
        for (scheme in schemes) {
            assertNotEquals(scheme.primaryText, scheme.highlight)
            assertNotEquals(scheme.searchHighlightFocused, scheme.highlight)
        }
    }

    @Test
    fun `a spaced double equals sign stays plain text`() {
        for (md in listOf("a == b", "a == b == c", "== x ==", "x ==y == z", "just == and ==")) {
            val text = render(md)
            assertEquals(md, text.toString())
            assertEquals(md, emptyList<String>(), highlighted(text, DarkColorScheme))
        }
    }

    @Test
    fun `code keeps its double equals signs`() {
        val inline = render("`==x==` and ==y==")
        assertEquals("==x== and y", inline.toString().replace('\u00a0', ' ').trim().replace(Regex(" +"), " "))
        assertEquals(listOf("y"), highlighted(inline, DarkColorScheme))
        val fenced = render("```\n==x==\n```")
        assertTrue(fenced.toString().contains("==x=="))
        assertEquals(emptyList<String>(), highlighted(fenced, DarkColorScheme))
    }

    @Test
    fun `bold and a link inside keep their own style`() {
        val text = render("==**bold** and [link](https://example.org)==")
        assertEquals("bold and link", text.toString())
        assertEquals(listOf("bold and link"), highlighted(text, DarkColorScheme))
        assertTrue(
            text.getSpans(0, 4, StyleSpan::class.java).isNotEmpty() || text.getSpans(0, 4, Any::class.java).size > 1,
        )
    }

    @Test
    fun `a longer run keeps the extra signs literal`() {
        val text = render("===x===")
        assertEquals("=x=", text.toString())
        assertEquals(listOf("x"), highlighted(text, DarkColorScheme))
    }

    @Test
    fun `the search colour is drawn over the highlight`() {
        val text = render("find ==me== now")
        val view = TextView(context).apply { this.text = text }
        val search = SearchHighlight(
            query = "me",
            focusedPosition = 0,
            focusedOccurrence = 0,
            otherColor = 0x11223344,
            focusedColor = 0x55667788,
        )
        assertEquals(1, SearchHighlighter.highlight(view, search, blockIsFocused = true, occurrenceBase = 0))
        val painted = view.text as Spanned
        val start = painted.toString().indexOf("me")
        val over = painted.getSpans(start, start + 2, BackgroundColorSpan::class.java).map { it.backgroundColor }
        assertEquals("the search span is applied last, so it paints over", 0x55667788, over.last())
        assertTrue(over.contains(DarkColorScheme.highlight.toArgb()))
    }

    @Test
    fun `search counts and places matches in marked text as the reader paints them`() {
        val markdown = "me ==me and me== me **==me==**"
        val markwon: Markwon = buildPilcrowMarkwon(context)
        val block = ReaderDocument.transform(markwon.parse(markdown)).firstChild!!
        val painted = searchableMatchOffsets(markwon.render(block), "me")
        val model = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(markdown, "me")
        assertEquals(painted.size, model.size)
        assertEquals(painted.indices.toList(), model.map { it.occurrenceInBlock })
        // Each raw start sits on the letters, not on a marker.
        for (m in model) assertEquals("me", markdown.substring(m.startIndex, m.startIndex + 2))
        // The marker itself is never a match.
        assertEquals(0, SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(markdown, "==").size)
    }

    @Test
    fun `the contents list shows marked heading text without markers`() {
        val headings = ParseMarkdownHeadingsUseCase().extractHeadings("# Plan ==now== ok\n\ntext")
        assertEquals("Plan now ok", headings.single().text)
    }
}
