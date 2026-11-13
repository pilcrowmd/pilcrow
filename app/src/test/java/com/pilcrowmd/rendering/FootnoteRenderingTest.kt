// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.noties.markwon.Markwon
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The footnote MARKER on screen, against the real renderer config.
 *
 * The `⇌` lesson applies here more than anywhere else in this feature: a span stack that resolves to
 * nothing would pass any "the node is in the tree" assertion. So these tests assert on the painted
 * text and on the spans covering it — the same evidence the golden pins visually.
 */
@RunWith(RobolectricTestRunner::class)
class FootnoteRenderingTest {

    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        // Same plugin chain as production, without MarkwonRenderer's pre-warm thread, so nothing else
        // runs on this instance while the test parses.
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    private fun firstBlock(markdown: String): Node = Footnotes.transform(markwon.parse(markdown)).firstChild!!

    private fun renderFirstBlock(markdown: String): Spanned = markwon.render(firstBlock(markdown)) as Spanned

    @Test
    fun `a resolved reference paints its ordinal, not its label`() {
        val rendered = renderFirstBlock("See[^note] here.\n\n[^note]: body\n")
        assertEquals("See1 here.", rendered.toString().trim())
    }

    @Test
    fun `in the reader the marker draws as a box over the unchanged digits, not as a superscript`() {
        val rendered = renderFirstBlock("See[^1].\n\n[^1]: body\n")
        assertEquals(
            "the digits stay the text: search models the marker as exactly them",
            "See1.",
            rendered.toString().trim(),
        )
        val start = rendered.toString().indexOf('1')
        assertEquals(1, rendered.getSpans(start, start + 1, FootnoteMarkerSpan::class.java).size)
        assertEquals(0, rendered.getSpans(start, start + 1, SuperscriptSpan::class.java).size)
        assertEquals(0, rendered.getSpans(start, start + 1, RelativeSizeSpan::class.java).size)
    }

    @Test
    fun `the app-theme PDF keeps the superscript too`() {
        val print = buildPrintMarkwon(ApplicationProvider.getApplicationContext(), codeColors = DarkColorScheme)
        val block = Footnotes.transform(print.parse("See[^1].\n\n[^1]: body\n")).firstChild!!
        val rendered = print.render(block) as Spanned
        assertEquals(1, rendered.getSpans(0, rendered.length, SuperscriptSpan::class.java).size)
        assertEquals(0, rendered.getSpans(0, rendered.length, FootnoteMarkerSpan::class.java).size)
    }

    @Test
    fun `in print the marker stays small superscript digits, with no box`() {
        val print = buildPrintMarkwon(ApplicationProvider.getApplicationContext())
        val block = Footnotes.transform(print.parse("See[^1].\n\n[^1]: body\n")).firstChild!!
        val rendered = print.render(block) as Spanned
        val start = rendered.toString().indexOf('1')
        assertTrue("the ordinal must be painted", start >= 0)
        assertEquals(1, rendered.getSpans(start, start + 1, SuperscriptSpan::class.java).size)
        assertEquals(
            FOOTNOTE_MARKER_SCALE,
            rendered.getSpans(start, start + 1, RelativeSizeSpan::class.java).single().sizeChange,
            0f,
        )
        assertEquals(
            "the PDF must not change: no box in print",
            0,
            rendered.getSpans(0, rendered.length, FootnoteMarkerSpan::class.java).size,
        )
    }

    @Test
    fun `a marker tap goes to the reader's handler with the definition's block, and is inert without one`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val rendered = renderFirstBlock("See[^1].\n\n# Notes\n\n[^1]: body\n")
        val span = rendered.getSpans(0, rendered.length, FootnoteJumpSpan::class.java).single()
        // No handler above it (the PDF export, a bare view): the tap must be inert, not throw.
        // Safeguard 3 applies to the interaction as much as to the render.
        span.onClick(FrameLayout(context))

        val calls = mutableListOf<Int>()
        val reader = FrameLayout(context).apply { setFootnoteTapHandler { calls += it } }
        val textView = TextView(context)
        reader.addView(textView)
        span.onClick(textView)
        assertEquals("the definition is the third top-level block", listOf(2), calls)
    }

    @Test
    fun `a two-digit ordinal paints both digits under one marker`() {
        // Eleven labels, referenced in order: the eleventh marker is "11" and must be one span run,
        // matching the two characters SearchMarkdownUseCase appends for it.
        val refs = (1..11).joinToString(" ") { "r[^$it]" }
        val defs = (1..11).joinToString("\n") { "[^$it]: body $it" }
        val rendered = renderFirstBlock("$refs\n\n$defs\n")
        assertTrue(rendered.toString().contains("r11"))
        val start = rendered.toString().indexOf("r11") + 1
        val span = rendered.getSpans(start, start + 2, FootnoteJumpSpan::class.java).single()
        assertEquals(start, rendered.getSpanStart(span))
        assertEquals(start + 2, rendered.getSpanEnd(span))
    }

    @Test
    fun `an orphan reference paints its literal source`() {
        val rendered = renderFirstBlock("See[^missing].\n\n[^other]: body\n")
        assertEquals("See[^missing].", rendered.toString().trim())
        assertEquals(0, rendered.getSpans(0, rendered.length, FootnoteJumpSpan::class.java).size)
    }

    @Test
    fun `the marker takes the footnoteMarker colour of the active scheme, not a baked-in colour`() {
        val entry = ProseBlockEntry(
            ApplicationProvider.getApplicationContext(),
            ReaderWords.ENGLISH,
            colorScheme = DarkColorScheme,
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, firstBlock("See[^1].\n\n[^1]: body\n"))

        val painted = holder.textView.text as Spanned
        val markerStart = painted.toString().indexOf('1')
        assertEquals(
            DarkColorScheme.footnoteMarker.toArgb(),
            painted.getSpans(markerStart, markerStart + 1, ForegroundColorSpan::class.java).single().foregroundColor,
        )

        // The PDF export binds the same entry with PrintColorScheme; the marker must follow it.
        val printEntry = ProseBlockEntry(context, ReaderWords.ENGLISH, colorScheme = PrintColorScheme)
        val printHolder = printEntry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        printEntry.bindHolder(markwon, printHolder, firstBlock("See[^1].\n\n[^1]: body\n"))
        val printed = printHolder.textView.text as Spanned
        assertEquals(
            PrintColorScheme.footnoteMarker.toArgb(),
            printed.getSpans(markerStart, markerStart + 1, ForegroundColorSpan::class.java).single().foregroundColor,
        )
    }

    @Test
    fun `the plugin never rewrites the source string`() {
        // Safeguard 2, at the only place it could be broken: a Markwon plugin CAN rewrite markdown
        // in processMarkdown, and any rewrite would shift every editor and search offset off the
        // raw source. FootnotePlugin must hand the identical instance straight through.
        val source = "Newton[^1] wrote it.\n\n[^1]: gravity\n"
        assertSame(source, FootnotePlugin(forPrint = false, density = 1f).processMarkdown(source))
    }

    @Test
    fun `a definition falls back to prose when no entry claims it`() {
        // The graceful-ignore floor (Safeguard 3): if the adapter entry were ever missing or
        // unregistered, the block must still show its body as ordinary prose. Markwon's visitor
        // falls through to visitChildren for an unhandled node, which is exactly that.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prose = ProseBlockEntry(context, ReaderWords.ENGLISH)
        val holder = prose.createHolder(LayoutInflater.from(context), FrameLayout(context))
        val definition = Footnotes.transform(markwon.parse("Ref[^1].\n\n[^1]: the note body\n")).lastChild!!

        prose.bindHolder(markwon, holder, definition)

        assertEquals("the note body", holder.textView.text.toString().trim())
    }

    @Test
    fun `a document with no footnotes renders exactly as before`() {
        val rendered = renderFirstBlock("A plain paragraph with **bold** and a [link](https://example.com).\n")
        assertEquals("A plain paragraph with bold and a link.", rendered.toString().trim())
        assertEquals(0, rendered.getSpans(0, rendered.length, FootnoteJumpSpan::class.java).size)
    }
}
