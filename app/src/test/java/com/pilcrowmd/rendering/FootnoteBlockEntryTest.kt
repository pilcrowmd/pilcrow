// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.FootnoteDefinitionBlock
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The footnote DEFINITION as it is painted.
 *
 * The load-bearing assertion here is the quiet one: the body TextView must contain the note's text
 * and nothing else. The marker and the back-link are sibling views on purpose, because
 * `SearchMarkdownUseCase` models this block as its body alone — put either of them inside the
 * TextView and the block's painted text stops matching what search counts.
 */
@RunWith(RobolectricTestRunner::class)
class FootnoteBlockEntryTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    private fun definition(markdown: String, index: Int = 0): FootnoteDefinitionBlock {
        val document = Footnotes.transform(markwon.parse(markdown))
        val blocks = generateSequence(document.firstChild) { it.next }
        return blocks.filterIsInstance<FootnoteDefinitionBlock>().toList()[index]
    }

    private fun bind(
        node: FootnoteDefinitionBlock,
        entry: FootnoteBlockEntry = FootnoteBlockEntry(context),
        renderer: Markwon = markwon,
    ): FootnoteBlockEntry.Holder {
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(renderer, holder, node)
        return holder
    }

    @Test
    fun `the body view holds the note text and nothing else`() {
        val holder = bind(definition("Ref[^1].\n\n[^1]: the note body\n"))
        assertEquals("the note body", holder.body.text.toString().trim())
        assertEquals("1", holder.marker.text.toString())
    }

    @Test
    fun `a referenced note shows its ordinal and offers a back-link`() {
        val holder = bind(definition("A[^b] and B[^a].\n\n[^a]: alpha\n[^b]: bravo\n", index = 1))
        assertEquals("bravo is referenced first, so it is 1", "1", holder.marker.text.toString())
        assertEquals(View.VISIBLE, holder.backLink.visibility)
        // No RecyclerView ancestor here: the tap must be inert rather than throw.
        holder.backLink.performClick()
    }

    @Test
    fun `an unreferenced note shows its literal label and hides the back-link`() {
        val holder = bind(definition("Nothing points here.\n\n[^sources]: see the appendix\n"))
        assertEquals("no fabricated number", "sources", holder.marker.text.toString())
        assertEquals("see the appendix", holder.body.text.toString().trim())
        assertEquals(View.GONE, holder.backLink.visibility)
    }

    @Test
    fun `a recycled holder does not keep the previous note's back-link`() {
        val entry = FootnoteBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))

        entry.bindHolder(markwon, holder, definition("Ref[^1].\n\n[^1]: referenced\n"))
        assertEquals(View.VISIBLE, holder.backLink.visibility)

        entry.bindHolder(markwon, holder, definition("No refs.\n\n[^loose]: orphan\n"))
        assertEquals(View.GONE, holder.backLink.visibility)
        assertTrue("the stale listener must be cleared", !holder.backLink.hasOnClickListeners())
    }

    @Test
    fun `inline formatting inside a note survives`() {
        val holder = bind(definition("Ref[^1].\n\n[^1]: body with **bold** inside\n"))
        assertEquals("body with bold inside", holder.body.text.toString().trim())
        val spans = (holder.body.text as android.text.Spanned)
            .getSpans(0, holder.body.text.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java)
        assertEquals("the note body goes through the normal Markwon path", 1, spans.size)
    }

    @Test
    fun `a multi-paragraph note renders both paragraphs`() {
        val holder = bind(definition("Ref[^1].\n\n[^1]: first para\n\n    second para\n"))
        val painted = holder.body.text.toString()
        assertTrue(painted.contains("first para"))
        assertTrue(painted.contains("second para"))
    }

    @Test
    fun `the marker is tagged as chrome so search ordinals ignore it`() {
        // The intra-block scroll walks every TextView in a holder and threads the block's match
        // ordinal across them. The marker paints an ordinal (or a label) that the search use case
        // never modelled for this block, so counting matches inside it would desync that thread —
        // searching "1" against a note numbered 1 is the obvious way to hit it.
        val holder = bind(definition("Ref[^1].\n\n[^1]: body\n"))
        assertEquals(SEARCH_EXCLUDED_TAG, holder.marker.tag)
        assertEquals("the body IS content and must stay countable", null, holder.body.tag)
    }

    @Test
    fun `a failing render degrades instead of crashing the adapter`() {
        val throwing = mockk<Markwon>()
        every { throwing.render(any()) } throws RuntimeException("boom")

        val holder = bind(definition("Ref[^1].\n\n[^1]: body\n"), renderer = throwing)

        assertEquals(ProseBlockEntry.RENDER_FALLBACK_TEXT, holder.body.text.toString())
    }

    @Test
    fun `colours come from the active scheme, never a baked-in value`() {
        val note = definition("Ref[^1].\n\n[^1]: body\n")
        val dark = bind(note, FootnoteBlockEntry(context, colorScheme = DarkColorScheme))
        assertEquals(DarkColorScheme.accent.toArgb(), dark.marker.currentTextColor)
        assertEquals(DarkColorScheme.secondaryText.toArgb(), dark.body.currentTextColor)

        // The PDF export builds the same entry with PrintColorScheme.
        val print = bind(note, FootnoteBlockEntry(context, colorScheme = PrintColorScheme))
        assertEquals(PrintColorScheme.accent.toArgb(), print.marker.currentTextColor)
        assertEquals(PrintColorScheme.secondaryText.toArgb(), print.body.currentTextColor)
    }
}
