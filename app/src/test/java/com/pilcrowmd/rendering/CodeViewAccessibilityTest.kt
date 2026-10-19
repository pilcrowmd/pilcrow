// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.mockk.mockk
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-404: on the S24+ TalkBack read out every colour of a coloured json block ("start color red", ...),
 * because the accessibility node carried the SPANNED text, and a plain grey block read only its text.
 * Code views give accessibility the same characters with no spans, so the colours stay on screen and
 * are not spoken.
 *
 * Each check first asserts the shown text DOES carry colour spans (or the test could not fail), then
 * that the node and the event text are plain, then that the view still shows its colours.
 */
@RunWith(RobolectricTestRunner::class)
class CodeViewAccessibilityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon: Markwon = buildPilcrowMarkwon(context, DarkColorScheme)

    private fun json(lines: Int) = buildString {
        for (i in 0 until lines) append("  { \"id\": $i, \"name\": \"item-$i\", \"active\": true, \"note\": null },\n")
    }

    /** The holders of every code item of ```json [code], bound through the real adapter, colours inline. */
    private fun bindJson(code: String): List<FencedCodeBlockEntry.Holder> {
        val document = ReaderDocument.transform(markwon.parse("```json\n$code```\n"))
        val blocks = AdapterBlocks.of(document)
        val adapter: MarkwonAdapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            codeHighlighting = CodeHighlighting(
                TextMateCodeHighlighter(context.assets::open),
                scope = null,
                Prism4jCodeHighlighter(),
            ),
        )
        adapter.setParsedMarkdown(markwon, document)
        return blocks.indices.filter { blocks[it] is FencedCodeBlock }.map { position ->
            val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            holder as FencedCodeBlockEntry.Holder
        }
    }

    private fun colourSpans(text: CharSequence) =
        (text as? Spanned)?.getSpans(0, text.length, ForegroundColorSpan::class.java).orEmpty()

    private fun assertSpokenPlain(view: TextView, what: String) {
        val shown = view.text
        assertTrue("$what: precondition, the shown text carries colour spans", colourSpans(shown).isNotEmpty())

        val spoken = view.createAccessibilityNodeInfo().text
        assertFalse("$what: the node text must not be Spanned (TalkBack reads each span out)", spoken is Spanned)
        assertEquals("$what: the node text is the shown characters", shown.toString(), spoken.toString())

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        view.onPopulateAccessibilityEvent(event)
        assertTrue("$what: the event carries the text", event.text.isNotEmpty())
        event.text.forEach { assertFalse("$what: event text must not be Spanned", it is Spanned) }

        assertTrue("$what: the view itself still shows its colours", colourSpans(view.text).isNotEmpty())
        assertEquals("$what: and the same text", shown.toString(), view.text.toString())
    }

    @Test
    fun `a coloured short json block is spoken as plain text`() {
        val holder = bindJson(json(3)).single()
        assertSpokenPlain(holder.codeView, "short json")
    }

    @Test
    fun `a piece of a long coloured json block is spoken as plain text`() {
        val holders = bindJson(json(LONG_LINES))
        assertTrue("precondition: the block is cut into pieces", holders.size > 1)
        assertSpokenPlain(holders.last().codeView, "last piece")
    }

    @Test
    fun `the frontmatter card is spoken as plain text`() {
        val entry = FrontmatterBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(
            mockk<Markwon>(relaxed = true),
            holder,
            FencedCodeBlock().apply { literal = "title: A Note\nauthor: someone\n" },
        )
        assertSpokenPlain(holder.codeView, "frontmatter")
    }

    private companion object {
        const val LONG_LINES = 400
    }
}
