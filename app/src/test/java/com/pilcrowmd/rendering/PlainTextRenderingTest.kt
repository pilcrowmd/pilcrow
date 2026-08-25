// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.text.style.MetricAffectingSpan
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Plain-mode rendering through the REAL adapter machinery: a [PlainTextChunk] document
 * set via setParsedMarkdown dispatches to [PlainTextBlockEntry], which renders the literal
 * verbatim — no Markdown spans, zero vertical padding (chunk seams live in blank-line gaps).
 * The `.md` path is untouched: the parser never emits PlainTextChunk.
 */
@RunWith(RobolectricTestRunner::class)
class PlainTextRenderingTest {

    private lateinit var context: android.content.Context
    private lateinit var markwon: io.noties.markwon.Markwon

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    private fun boundTextViews(content: String): List<android.widget.TextView> {
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon)
        adapter.setParsedMarkdown(markwon, PlainTextBlocks.build(content))
        val parent = FrameLayout(context)
        return (0 until adapter.itemCount).map { i ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(i))
            adapter.onBindViewHolder(holder, i)
            (holder as PlainTextBlockEntry.Holder).textView
        }
    }

    @Test
    fun markdownSyntaxRendersLiterally() {
        val src = "# not a heading\n**not bold**\n\n- not a list\n| not | table |"
        val views = boundTextViews(src)
        assertEquals(1, views.size)
        assertEquals(src, views[0].text.toString())
    }

    @Test
    fun noStylingSpansArePresent() {
        val views = boundTextViews("# h\n**b**\n*i*\n`c`")
        val text = views[0].text
        val spanCount = (text as? Spanned)?.getSpans(0, text.length, MetricAffectingSpan::class.java)?.size ?: 0
        assertEquals("no metric-affecting (heading/bold/code) spans in plain mode", 0, spanCount)
    }

    @Test
    fun blankLinesArePreservedInText() {
        val src = "a\n\n\n\nb"
        assertEquals(src, boundTextViews(src)[0].text.toString())
    }

    @Test
    fun chunksBindIndependentlyWithZeroVerticalPadding() {
        val head = (1..220).map { "line $it" }
        val src = (head + "" + listOf("tail")).joinToString("\n")
        val views = boundTextViews(src)
        assertEquals(2, views.size)
        views.forEach { tv ->
            assertEquals("zero top padding", 0, tv.paddingTop)
            assertEquals("zero bottom padding", 0, tv.paddingBottom)
            assertTrue("horizontal prose padding kept", tv.paddingLeft > 0)
        }
        assertEquals(src, views.joinToString("\n") { it.text.toString() })
    }

    @Test
    fun markdownParsePathNeverEmitsPlainChunks() {
        // The parser cannot produce PlainTextChunk — .md rendering is structurally unaffected.
        val node = markwon.parse("# real heading\n\nreal paragraph")
        var found = 0
        var child = node.firstChild
        while (child != null) {
            if (child is PlainTextChunk) found++
            child = child.next
        }
        assertEquals(0, found)
    }
}
