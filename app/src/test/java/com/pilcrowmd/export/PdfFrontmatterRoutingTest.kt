// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.rendering.MarkwonRenderer
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-198, PDF side: the export has its own create/bind dispatch, so the reader's routing proves nothing
 * about the printed page. Only front matter may print as the metadata card; a ```` ```yaml ```` fence
 * prints as code wherever it sits. Told apart by text, since print hides Copy on both lanes: the card
 * writes `key<TAB>value`, the code block keeps the literal `key: value`.
 */
@RunWith(RobolectricTestRunner::class)
class PdfFrontmatterRoutingTest {

    private lateinit var context: Context
    private lateinit var exporter: PdfExporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val renderer = MarkwonRenderer(context)
        // Same reason as PdfExporterTest: a parse racing the pre-warm on the shared Markwon can throw.
        renderer.awaitFontPreWarm()
        exporter = PdfExporter(context, renderer)
    }

    /** The printed text of every fenced block, in document order, through the export's own dispatch. */
    private fun printedFencedTexts(content: String): List<String> {
        val markwon = exporter.getMarkwon()
        val builder = PdfContentLayoutBuilder(context)
        val inflater = LayoutInflater.from(context)
        val widthPx = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        return topLevelBlocksForMode(markwon, content, RenderMode.MARKDOWN)
            .filterIsInstance<FencedCodeBlock>()
            .map { node ->
                // Read at once: the builder pools one holder per node type and rebinds it.
                val view = builder.inflateMeasuredBlock(markwon, node, inflater, widthPx, 1.0f)
                view.findViewById<TextView>(R.id.code_text).text.toString()
            }
    }

    private fun assertCode(text: String, line: String) =
        assertTrue("a ```yaml fence must print as code, was <$text>", text.contains(line))

    @Test
    fun yamlFenceMidDocumentPrintsAsCodeBlock() {
        val texts = printedFencedTexts(
            "---\ntitle: Real\n---\n\n# Notes\n\nConfig:\n\n```yaml\ntitle: Mid\n```\n",
        )
        assertEquals(2, texts.size)
        assertEquals("title\tReal", texts[0])
        assertCode(texts[1], "title: Mid")
    }

    @Test
    fun realFrontMatterStillPrintsAsCard() {
        val texts = printedFencedTexts("---\ntitle: Real\nauthor: Tester\n---\n\nBody.\n")
        assertEquals(listOf("title\tReal\nauthor\tTester"), texts)
    }

    @Test
    fun fileStartingWithYamlFencePrintsAsCodeBlock() {
        val texts = printedFencedTexts("```yaml\ntitle: Top\n```\n\nBody.\n")
        assertCode(texts.single(), "title: Top")
    }
}
