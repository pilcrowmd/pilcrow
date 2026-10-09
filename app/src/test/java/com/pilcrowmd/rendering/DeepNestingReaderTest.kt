// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.export.topLevelBlocksForMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * NEW-11, end to end: a document nested deeper than the stack can walk is shown as plain text — no
 * throw anywhere a user can reach it, and every character of it on screen — while ordinary nesting
 * still renders as Markdown.
 *
 * Drives the reader the way the renderer fuzz harness does: the production Markwon chain and adapter,
 * the list populated exactly as `Preview.kt` populates it ([ReaderTree] → [Details.sections] →
 * `setParsedMarkdown`), every item bound, measured, laid out and drawn; then the TOC parse and search,
 * and the PDF export. The test thread's stack is ~1 MB, far below the app's main thread, so an overflow
 * shows up here at a smaller depth than on a device — 400 nested `>` was the fuzz suite's first one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeepNestingReaderTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    /** One reader item as painted: its holder, its text, and how many search highlights it carries. */
    private class PaintedItem(val holder: Any, val text: String, val highlights: Int)

    /** Every item, bound and drawn with [query] highlighted, in adapter order. */
    private fun renderInReader(markdown: String, query: String = ""): List<PaintedItem> {
        val markwon = buildPilcrowMarkwon(activity)
        val details = DetailsState()
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            activity,
            markwon,
            searchHighlight = SearchHighlight(query = query),
            details = details,
        )
        val document = ReaderTree.build(markwon, markdown, plain = false)
        details.load(markdown, Details.sections(document))
        adapter.setParsedMarkdown(markwon, document)

        val parent = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        val canvas = Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        val items = (0 until adapter.itemCount).map { position ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            val view = holder.itemView
            view.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            view.draw(canvas)
            PaintedItem(holder, StringBuilder().also { collect(view, it) }.toString(), highlights(view))
        }
        shadowOf(Looper.getMainLooper()).idle()

        val headings = ParseMarkdownHeadingsUseCase()
        headings.extractHeadings(markdown)
        SearchMarkdownUseCase(headings).findSearchMatches(markdown, "deep")
        return items
    }

    private fun collect(view: View, out: StringBuilder) {
        when (view) {
            is TextView -> out.append(view.text)
            is ViewGroup -> for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    private fun highlights(view: View): Int = when (view) {
        is TextView -> (view.text as? Spanned)?.getSpans(0, view.text.length, BackgroundColorSpan::class.java)
            ?.size ?: 0
        is ViewGroup -> (0 until view.childCount).sumOf { highlights(view.getChildAt(it)) }
        else -> 0
    }

    private fun assertShownAsPlainText(markdown: String) {
        val items = renderInReader(markdown)
        assertTrue("every item is a plain-text chunk", items.all { it.holder is PlainTextBlockEntry.Holder })
        assertEquals("the source, verbatim", markdown, items.joinToString("\n") { it.text })
    }

    @Test
    fun `400 nested quotes - the fuzz suite's first overflow - are shown as plain text`() {
        assertShownAsPlainText(">".repeat(400) + " deep")
    }

    @Test
    fun `100,000 nested quotes are shown as plain text`() {
        assertShownAsPlainText(">".repeat(100_000) + " deep")
    }

    @Test
    fun `deep lists and mixed containers are shown as plain text`() {
        assertShownAsPlainText("- ".repeat(50_000) + "deep")
        assertShownAsPlainText("> - ".repeat(25_000) + "deep")
        assertShownAsPlainText("Intro paragraph.\n\n" + "> ".repeat(20_000) + "<details>\ndeep\n\nOutro.")
    }

    @Test
    fun `ordinary nesting still renders as Markdown`() {
        val items = renderInReader(">".repeat(10) + " formatted\n\n- a\n  - b\n    - **c**")
        assertFalse("rendered, not plain", items.any { it.holder is PlainTextBlockEntry.Holder })
        val painted = items.joinToString("\n") { it.text }
        assertEquals("the quote's markers are formatting, not text", "formatted", items.first().text.trim())
        assertFalse("no markdown syntax painted", painted.contains('>') || painted.contains("**"))
    }

    @Test
    fun `the TOC of a too-deep document is empty and its search finds the words on screen`() {
        // A footnote definition makes the TOC parse's footnote pass walk every block, the deep one too.
        val markdown = "# Title\n\n" + ">".repeat(100_000) + " deep[^1]\n\n[^1]: note\n"
        val headings = ParseMarkdownHeadingsUseCase()
        assertEquals(emptyList<Any>(), headings.extractHeadings(markdown))
        // Shown as one plain-text chunk, so its one "deep" is item 0 at its exact source offset.
        assertEquals(
            listOf(SearchMatch("deep", startIndex = markdown.indexOf("deep"), adapterPosition = 0)),
            SearchMarkdownUseCase(headings).findSearchMatches(markdown, "deep"),
        )
    }

    @Test
    fun `a too-deep document paints exactly the search matches, item by item`() {
        // Two plain-text chunks: the deep line and 100 "x" paragraphs, then a line with "deep" twice.
        val markdown = ">".repeat(400) + " deep\n" + "x\n\n".repeat(100) + "deep deep"
        val matches = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(markdown, "deep")

        val painted = renderInReader(markdown, query = "deep").map { it.highlights }
        assertEquals("highlights painted per item", listOf(1, 2), painted)
        assertEquals(
            "the model counts what each item paints",
            painted,
            painted.indices.map { position -> matches.count { it.adapterPosition == position } },
        )
        assertEquals(listOf(0, 0, 1), matches.map { it.occurrenceInBlock })
    }

    @Test
    fun `the PDF export prints a too-deep document as plain text`() {
        val renderer = warmedMarkwonRenderer(activity)
        val exporter = PdfExporter(activity, renderer)
        val markdown = ">".repeat(100_000) + " deep"

        val blocks = topLevelBlocksForMode(renderer.printMarkwonFor(1f), markdown, RenderMode.MARKDOWN)
        assertTrue(blocks.all { it is PlainTextChunk })
        assertEquals(markdown, blocks.joinToString("\n") { (it as PlainTextChunk).literal })

        // Pass 1 of the export binds, measures and lays out every block through the print path. (Pass 2
        // needs a real PdfDocument, which Robolectric's shadow refuses — see PdfExporterTest.)
        assertEquals(blocks.size, exporter.measureBlockBounds(markdown).size)

        val ordinary = topLevelBlocksForMode(renderer.printMarkwonFor(1f), "> > quoted", RenderMode.MARKDOWN)
        assertFalse("ordinary nesting still prints as Markdown", ordinary.any { it is PlainTextChunk })
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
    }
}
