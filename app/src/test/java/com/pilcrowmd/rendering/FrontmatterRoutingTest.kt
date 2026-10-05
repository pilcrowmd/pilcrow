// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.ReaderDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-198: only front matter (`---…---` at the very start of the file) becomes the metadata card. A
 * ```` ```yaml ```` fence carries the same `"yaml"` info string, and must stay a code block wherever
 * it sits — mid-document, or as the file's first block. Bound through the reader's REAL adapter.
 *
 * The card and the code block share one holder, so the lane is told apart by what it wrote: the card
 * turns `key: value` into a `key<TAB>value` row and hides Copy; the code block keeps the literal
 * colon and shows Copy.
 */
@RunWith(RobolectricTestRunner::class)
class FrontmatterRoutingTest {

    private lateinit var context: android.content.Context
    private lateinit var markwon: io.noties.markwon.Markwon

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    /** Every fenced block's holder, in document order, parsed and bound the way Preview does it. */
    private fun fencedHolders(content: String): List<FencedCodeBlockEntry.Holder> {
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon)
        adapter.setParsedMarkdown(markwon, ReaderDocument.transform(markwon.parse(content)))
        val parent = FrameLayout(context)
        return (0 until adapter.itemCount).mapNotNull { i ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(i))
            adapter.onBindViewHolder(holder, i)
            holder as? FencedCodeBlockEntry.Holder
        }
    }

    private fun assertCard(holder: FencedCodeBlockEntry.Holder, expected: String) {
        assertEquals("front matter must render as the metadata card", expected, holder.codeView.text.toString())
        assertEquals("the card has no Copy button", View.GONE, holder.copyButton.visibility)
    }

    private fun assertCodeBlock(holder: FencedCodeBlockEntry.Holder, line: String) {
        val text = holder.codeView.text.toString()
        assertTrue("a ```yaml fence must render as code, was <$text>", text.contains(line))
        assertEquals("a code block shows Copy", View.VISIBLE, holder.copyButton.visibility)
    }

    @Test
    fun yamlFenceMidDocumentRendersAsCodeBlock() {
        val holders = fencedHolders(
            "---\ntitle: Real\n---\n\n# Notes\n\nConfig:\n\n```yaml\ntitle: Mid\n```\n",
        )
        assertEquals(2, holders.size)
        assertCard(holders[0], "title\tReal")
        assertCodeBlock(holders[1], "title: Mid")
    }

    @Test
    fun realFrontMatterStillRendersAsCard() {
        val holders = fencedHolders("---\ntitle: Real\nauthor: Tester\n---\n\nBody.\n")
        assertCard(holders.single(), "title\tReal\nauthor\tTester")
    }

    @Test
    fun fileStartingWithYamlFenceRendersAsCodeBlock() {
        val holders = fencedHolders("```yaml\ntitle: Top\n```\n\nBody.\n")
        assertCodeBlock(holders.single(), "title: Top")
    }
}
