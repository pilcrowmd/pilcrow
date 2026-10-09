// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.BlockQuote
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/**
 * A table cell must never SILENTLY DROP content (Safeguard 1).
 *
 * `TableBlockEntry` renders each cell through Markwon and falls back to a plain-text walk of the
 * cell's nodes when that returns nothing. The fallback used to collect `Text` literals only, so any
 * node carrying its text as a PROPERTY rather than as a `Text` child vanished from the page — the
 * author's content deleted on screen with no error.
 *
 * The fallback's contract is therefore the same one `SearchMarkdownUseCase.appendVisible` implements:
 * it must reproduce exactly what the cell paints, because search offsets depend on that
 * agreement. These tests pin the node types whose literal lives off the `Text` path.
 *
 * M-17, M-32, M-250: a cell is rendered through Markwon like a paragraph, so its formatting, links
 * and maths survive. Those tests assert the span CLASSES the cell carries, not pixels.
 */
@RunWith(RobolectricTestRunner::class)
class TableCellFidelityTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric never runs jlatexmath's init provider (see MarkdownScreenshotTest); a cell's
        // maths is resolved during the bind, so the library must be ready before any test binds.
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        markwon = buildPilcrowMarkwon(context)
    }

    /** The painted text of every cell, row-major — the exact order `TableBlockEntry` lays them out. */
    private fun cellTexts(markdown: String): List<String> = cellViews(markdown).map { it.text.toString() }

    /** Every cell's TextView, row-major, after a real bind through [TableBlockEntry]. */
    private fun cellViews(
        markdown: String,
        instance: Markwon = markwon,
        entry: TableBlockEntry = TableBlockEntry(context),
    ): List<TextView> {
        val document: Node = Footnotes.transform(instance.parse(markdown))
        val table = generateSequence(document.firstChild) { it.next }
            .filterIsInstance<TableBlock>()
            .first()
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(instance, holder, table)
        val views = mutableListOf<TextView>()
        fun collect(view: View) {
            if (view is TextView) views.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(holder.table)
        return views
    }

    private inline fun <reified T> TextView.spans(): List<T> {
        val spanned = text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, spanned.length, T::class.java).toList()
    }

    @Test
    fun `a footnote reference in a table cell paints its ordinal, not nothing`() {
        val texts = cellTexts(
            "| H |\n|---|\n| planet[^t] then Venus |\n\n[^t]: table note.\n",
        )
        assertEquals(listOf("H", "planet1 then Venus"), texts)
    }

    /** Markwon pads inline code with a no-break space each side, as it does in a paragraph (M-17). */
    @Test
    fun `inline code in a table cell is not dropped`() {
        val texts = cellTexts("| H |\n|---|\n| a `code` b |\n")
        assertEquals(listOf("H", "a \u00A0code\u00A0 b"), texts)
    }

    @Test
    fun `bold in a table cell is bold`() {
        val cell = cellViews("| H |\n|---|\n| a **b** c |\n").last()
        assertEquals("a b c", cell.text.toString())
        assertTrue("the cell must carry a StrongEmphasisSpan", cell.spans<StrongEmphasisSpan>().isNotEmpty())
    }

    @Test
    fun `a link in a table cell is a link that can be tapped`() {
        val cell = cellViews("| H |\n|---|\n| [NASA](https://nasa.gov) |\n").last()
        assertEquals("NASA", cell.text.toString())
        assertTrue("the cell must carry a LinkSpan", cell.spans<LinkSpan>().isNotEmpty())
        assertTrue("a tap must reach the link", cell.movementMethod is LinkMovementMethod)
    }

    @Test
    fun `inline code in a table cell has its code background`() {
        val cell = cellViews("| H |\n|---|\n| a `code` b |\n").last()
        assertTrue("the cell must carry a CodeSpan", cell.spans<CodeSpan>().isNotEmpty())
    }

    /**
     * M-32. The formula is resolved during the bind, before the columns are measured, so its column
     * is as wide as the formula and not as wide as its source text. No looper is idled here: the
     * result must already be there when the bind returns.
     */
    @Test
    fun `maths in a table cell renders, resolved before the columns are measured`() {
        val cell = cellViews("| H |\n|---|\n| \$E=mc^2\$ |\n").last()
        assertTrue("the cell must not be empty", cell.text.isNotEmpty())
        val span = cell.spans<JLatexAsyncDrawableSpan>().single()
        assertTrue("the formula must be resolved by the time the bind returns", span.drawable.hasResult())
    }

    /** C: a formula in a cell is drawn at the size the same formula has in a paragraph on screen. */
    @Test
    fun `maths in a table cell is the size of the same maths in a paragraph`() {
        val scale = 1.5f
        val renderer = MarkwonRenderer(context).also { it.awaitFontPreWarm() }
        val instance = renderer.markwonFor(DarkColorScheme, scale)
        val cell = cellViews(
            "| H |\n|---|\n| \$\\frac{a}{b}\$ |\n",
            instance,
            TableBlockEntry(context, fontScale = scale),
        ).last()
        val inCell = cell.spans<JLatexAsyncDrawableSpan>().single().drawable.result.bounds.height()
        assertEquals(proseMathHeight(instance, "Area \$\\frac{a}{b}\$ here."), inCell)
    }

    /** The height of the one formula in [markdown], rendered as a paragraph the way the screen does. */
    private fun proseMathHeight(instance: Markwon, markdown: String): Int {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = TextView(activity)
        activity.setContentView(view)
        instance.setMarkdown(view, markdown)
        val span = view.spans<JLatexAsyncDrawableSpan>().single()
        repeat(MAX_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            if (span.drawable.hasResult()) return span.drawable.result.bounds.height()
            ForkJoinPool.commonPool().awaitQuiescence(STEP_MS, TimeUnit.MILLISECONDS)
        }
        error("the paragraph's formula never rendered")
    }

    /** The screen's instance, with its image loader: the one that draws pictures in a paragraph. */
    private fun screenMarkwon(): Markwon = buildPilcrowMarkwon(
        context,
        images = ReaderImages(MarkdownImageLoader.createImageLoader(context), ImageBase()),
    )

    /**
     * M-250. A picture's size is known only once it has loaded, after the columns are fixed, so a cell
     * shows the image's alt text in its place instead of dropping it.
     */
    @Test
    fun `an image in a table cell shows its alt text`() {
        val cell = cellViews("| H |\n|---|\n| ![Mars](missing.png) |\n", screenMarkwon()).last()
        assertEquals("Mars", cell.text.toString())
        assertTrue("no picture is loaded into a cell", cell.spans<AsyncDrawableSpan>().isEmpty())
    }

    @Test
    fun `an image with no alt text in a table cell shows the placeholder's word for it`() {
        val cell = cellViews("| H |\n|---|\n| ![](missing.png) |\n", screenMarkwon()).last()
        assertEquals(ImagePlaceholderDrawable.LABEL_NO_ALT, cell.text.toString())
    }

    /**
     * Search does not count an image's alt text, so the cell's painted alt text must not count
     * as a match either, or every later match in the table is highlighted one ordinal off.
     */
    @Test
    fun `an image's alt text in a table cell is not a search match, as search models it`() {
        val markdown = "| H |\n|---|\n| ![Mars](missing.png) then Mars |\n"
        val cell = cellViews(markdown, screenMarkwon()).last()
        val searched = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(markdown, "Mars")
        assertEquals("search sees only the text after the image", 1, searched.size)
        assertEquals(searched.size, searchableMatchOffsets(cell.text, "Mars").size)
    }

    /**
     * A table nested in a quote is still drawn by Markwon's own table span: only a render whose root
     * is the cell itself is handled differently.
     */
    @Test
    fun `a table inside a quote is still drawn by the table plugin`() {
        val quote = markwon.parse("> | H |\n> |---|\n> | inside |\n").firstChild as BlockQuote
        val rendered = markwon.render(quote)
        val rows = rendered.getSpans(0, rendered.length, TableRowSpan::class.java)
        assertTrue("the quote's table must be a TableRowSpan", rows.isNotEmpty())
        assertFalse("the cell's text lives in the row span, not in the text", rendered.toString().contains("inside"))
    }

    /**
     * The offset trap, pinned. `SearchMarkdownUseCase` models a resolved marker as its ORDINAL, so a
     * cell that paints anything else hands search an offset into text the user cannot see: the match
     * counts, but the highlight lands on nothing. Before the non-lossy fallback this failed with the
     * cell painting "planet then Venus" while search insisted the block contained a "1".
     */
    @Test
    fun `search models a footnote marker in a table cell exactly as the cell paints it`() {
        val markdown = "| H |\n|---|\n| planet[^t] then Venus |\n\n[^t]: table note.\n"
        val painted = cellTexts(markdown).last()
        val matches = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())
            .findSearchMatches(markdown, "1")
        assertEquals("search must see the ordinal in the table block", 1, matches.count { it.adapterPosition == 0 })
        assertTrue("cell must paint the ordinal search modelled, but painted <$painted>", painted.contains("1"))
    }

    /**
     * H1. The movement method a link needs also consumes a drag, so a cell keeps it only when
     * it has something to tap; every other cell is left as plain `setText` left it.
     */
    @Test
    fun `a cell with nothing to tap has no movement method and is not clickable`() {
        val plain = TextView(context).apply { text = "x" }
        val cells = cellViews("| H |\n|---|\n| a **b** `c` |\n")
        for (cell in cells) {
            assertNull("<${cell.text}> must not keep the link movement method", cell.movementMethod)
            assertEquals(plain.isClickable, cell.isClickable)
            assertEquals(plain.isLongClickable, cell.isLongClickable)
            assertEquals(plain.isFocusable, cell.isFocusable)
        }
    }

    @Test
    fun `a footnote marker in a table cell keeps the movement method, so it can be tapped`() {
        val cell = cellViews("| H |\n|---|\n| planet[^t] |\n\n[^t]: table note.\n").last()
        assertTrue("the marker must be a ClickableSpan", cell.spans<ClickableSpan>().isNotEmpty())
        assertTrue(cell.movementMethod is LinkMovementMethod)
    }

    /** M4. The plain-text fallback, used when a cell's render throws, keeps every visible literal. */
    @Test
    fun `the plain-text fallback keeps inline code, inline HTML and a footnote marker`() {
        val document = Footnotes.transform(
            markwon.parse("| H |\n|---|\n| a `code` <b>x</b> planet[^t] |\n\n[^t]: table note.\n"),
        )
        val cells = mutableListOf<TableCell>()
        fun find(first: Node?) {
            var node = first
            while (node != null) {
                if (node is TableCell) cells.add(node)
                find(node.firstChild)
                node = node.next
            }
        }
        find(document.firstChild)
        assertEquals("a code <b>x</b> planet1", TableBlockEntry(context).plainText(cells.last()))
    }

    /** L2. An image's "Tap to show" target is removed in a cell: alt text is all a cell shows of it. */
    @Test
    fun `an image in a table cell leaves nothing to tap`() {
        val cell = cellViews("| H |\n|---|\n| ![Mars](missing.png) |\n", screenMarkwon()).last()
        assertTrue("no RelativeImageTapSpan in a cell", cell.spans<RelativeImageTapSpan>().isEmpty())
        assertTrue("no ClickableSpan in a cell", cell.spans<ClickableSpan>().isEmpty())
        assertNull(cell.movementMethod)
    }

    /**
     * M1. A bind builds at most 24 formulas on the main thread; the rest stay with the plugin's loader,
     * whose result is posted to the main looper, which this test never idles.
     */
    @Test
    fun `a table builds at most 24 formulas during the bind`() {
        val markdown = "| H |\n|---|\n" + (1..SYNC_FORMULA_CAP + 1).joinToString("") { "| \$x_{$it}\$ |\n" }
        val spans = cellViews(markdown).drop(1).map { it.spans<JLatexAsyncDrawableSpan>().single() }
        assertEquals(List(SYNC_FORMULA_CAP) { true } + false, spans.map { it.drawable.hasResult() })
    }

    /**
     * M1, off the main thread. The PDF export binds tables on Dispatchers.IO, where the cap would only
     * clip formulas in print, so there every formula is built during the bind.
     */
    @Test
    fun `a table bound off the main thread builds every formula during the bind`() {
        val markdown = "| H |\n|---|\n" + (1..SYNC_FORMULA_CAP + 1).joinToString("") { "| \$x_{$it}\$ |\n" }
        var resolved: List<Boolean>? = null
        var failure: Throwable? = null
        val worker = Thread {
            try {
                resolved = cellViews(markdown).drop(1).map { cell ->
                    cell.spans<JLatexAsyncDrawableSpan>().single().drawable.hasResult()
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        worker.start()
        worker.join()
        failure?.let { throw it }
        assertEquals(List(SYNC_FORMULA_CAP + 1) { true }, resolved)
    }

    private companion object {
        const val MAX_PASSES = 50
        const val STEP_MS = 200L
        const val SYNC_FORMULA_CAP = 24
    }
}
