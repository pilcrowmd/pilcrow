// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.buildPrintMarkwon
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-70: the PDF prints a ```mermaid block as its diagram. The builder is tested directly with a fake
 * `diagram` function, because the exporter's own refuses to wait on the main looper these tests run on
 * (the last two tests pin that refusal, and that it does call the renderer off the main thread).
 *
 * Each block is measured exactly as the export measures it: detached, at an exact page width, with an
 * unbounded height, so the image's height is whatever the ImageView itself works out from the picture.
 */
@RunWith(RobolectricTestRunner::class)
class PdfMermaidDiagramTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon = buildPrintMarkwon(context)
    private val source = "flowchart TD\n  A --> B\n"
    private val calls = mutableListOf<Pair<String, Int>>()

    private fun builder(picture: Bitmap?) = builder { picture }

    /** [answer] is given the number of the call, from 1. */
    private fun builder(answer: (call: Int) -> Bitmap?) = PdfContentLayoutBuilder(
        context,
        diagram = { diagramSource, widthPx ->
            calls += diagramSource to widthPx
            answer(calls.size)
        },
    )

    private fun block(builder: PdfContentLayoutBuilder, markdown: String): View {
        val node = parseTopLevelBlocks(markwon, markdown).single()
        return builder.inflateMeasuredBlock(markwon, node, LayoutInflater.from(context), PAGE_WIDTH_PX, 1f)
    }

    private fun mermaid() = "```mermaid\n$source```"

    private fun View.image() = findViewById<ImageView>(R.id.mermaid_image)
    private fun View.code() = findViewById<View>(R.id.code_scroll)
    private fun View.codeText() = findViewById<TextView>(R.id.code_text).text.toString()
    private fun View.caption() = findViewById<TextView>(R.id.mermaid_caption)

    /** A picture exactly as wide as the request, [aspect] wide per high, as the real renderer draws it. */
    private fun builderDrawing(aspect: Float) = PdfContentLayoutBuilder(
        context,
        diagram = { diagramSource, widthPx ->
            calls += diagramSource to widthPx
            Bitmap.createBitmap(widthPx, (widthPx / aspect).toInt(), Bitmap.Config.ARGB_8888)
        },
    )

    private fun View.verticalMargins() =
        (layoutParams as ViewGroup.MarginLayoutParams).let { it.topMargin + it.bottomMargin }

    private fun assertCentred(image: ImageView) {
        val panel = image.parent as View
        val expectedLeft = (panel.measuredWidth - image.measuredWidth) / 2f
        assertEquals("the panel is centred", expectedLeft, image.left.toFloat(), 1f)
    }

    @Test
    fun `a wide diagram prints centred, at most 60 percent of the text width, at the picture's aspect ratio`() {
        val view = block(builderDrawing(aspect = 2f), mermaid())
        val image = view.image()

        assertEquals("the image shows", View.VISIBLE, image.visibility)
        assertEquals("no code", View.GONE, view.code().visibility)
        assertEquals("no caption", View.GONE, view.caption().visibility)

        val panelMax = PAGE_WIDTH_PX * 6 / 10
        assertTrue("panel ${image.measuredWidth} within the cap $panelMax", image.measuredWidth <= panelMax)
        assertEquals("a wide picture fills the cap", panelMax.toFloat(), image.measuredWidth.toFloat(), 1f)
        val padding = image.paddingLeft + image.paddingRight
        assertEquals("the diagram is drawn for the panel's inner width", listOf(source to panelMax - padding), calls)
        val innerWidth = image.measuredWidth - padding
        val expectedHeight = innerWidth / 2 + image.paddingTop + image.paddingBottom
        assertEquals("the height follows the 2:1 picture", expectedHeight.toFloat(), image.measuredHeight.toFloat(), 1f)
        assertCentred(image)
    }

    @Test
    fun `a narrow diagram is not stretched to the cap and prints centred at its own width`() {
        val picture = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val view = block(builder(picture), mermaid())
        val image = view.image()

        assertSame(picture, (image.drawable as BitmapDrawable).bitmap)
        val padding = image.paddingLeft + image.paddingRight
        assertEquals("picture width plus padding, not stretched", 200 + padding, image.measuredWidth)
        val expectedHeight = 100 + image.paddingTop + image.paddingBottom
        assertEquals("the height follows the 2:1 picture", expectedHeight, image.measuredHeight)
        assertCentred(image)
    }

    @Test
    fun `a very tall diagram is capped to one page, keeps its aspect ratio and prints centred`() {
        val aspect = 0.1f
        val view = block(builderDrawing(aspect), mermaid())
        val image = view.image()

        val pageTextHeight = PAGE_WIDTH_PX * PdfExporter.PAGE_CONTENT_HEIGHT_PT / PdfExporter.PAGE_CONTENT_WIDTH_PT
        assertTrue(
            "block ${view.measuredHeight} + margins ${view.verticalMargins()} fits the page height $pageTextHeight",
            view.measuredHeight + view.verticalMargins() <= pageTextHeight,
        )
        val drawnWidth = image.measuredWidth - image.paddingLeft - image.paddingRight
        val drawnHeight = image.measuredHeight - image.paddingTop - image.paddingBottom
        assertEquals("the aspect ratio is kept", aspect, drawnWidth.toFloat() / drawnHeight, aspect * 0.02f)
        val widthCap = PAGE_WIDTH_PX * 6 / 10 - image.paddingLeft - image.paddingRight
        assertTrue("it shrank to fit the page, not just the width", drawnWidth < widthCap)
        assertCentred(image)
    }

    @Test
    fun `a very wide short diagram prints in a panel only as tall as its picture`() {
        // The layout's 120 dp minHeight (the reader's pending panel) must not apply on paper: 20 px plus padding is
        // far below it. ImageView ignores minHeight while adjustViewBounds resizes it, which the PDF fit relies on.
        val pictureHeight = 20
        val builder = PdfContentLayoutBuilder(
            context,
            diagram = { _, widthPx -> Bitmap.createBitmap(widthPx, pictureHeight, Bitmap.Config.ARGB_8888) },
        )
        val image = block(builder, mermaid()).image()

        val drawnWidth = image.measuredWidth - image.paddingLeft - image.paddingRight
        val drawable = image.drawable
        val shownHeight = drawnWidth.toFloat() * drawable.intrinsicHeight / drawable.intrinsicWidth
        assertEquals(
            "the panel is the picture plus padding",
            shownHeight + image.paddingTop + image.paddingBottom,
            image.measuredHeight.toFloat(),
            1f,
        )
    }

    @Test
    fun `an undrawn diagram prints its source with the caption`() {
        val view = block(builder(null), mermaid())

        assertEquals(View.GONE, view.image().visibility)
        assertEquals(View.VISIBLE, view.code().visibility)
        assertTrue("the source prints", view.codeText().contains("A --> B"))
        assertEquals(View.VISIBLE, view.caption().visibility)
        assertEquals("Diagram could not be drawn. Showing its source.", view.caption().text.toString())
    }

    @Test
    fun `an ordinary code block is unaffected`() {
        val view = block(builder(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)), "```kotlin\nval x = 1\n```")

        assertEquals("no diagram is drawn for it", emptyList<Pair<String, Int>>(), calls)
        assertEquals(View.GONE, view.image().visibility)
        assertEquals(View.VISIBLE, view.code().visibility)
        assertEquals(View.GONE, view.caption().visibility)
        assertTrue(view.codeText().contains("val x = 1"))
    }

    @Test
    fun `front matter printed after an undrawn diagram on the pooled holder has no caption`() {
        val builder = builder(null)
        val fallback = block(builder, mermaid())
        assertEquals("precondition: the caption shows", View.VISIBLE, fallback.caption().visibility)

        val card = block(builder, "---\ntitle: Stress test\n---")

        assertSame("precondition: the pool reused the holder", fallback, card)
        assertEquals("the card must not keep the caption", View.GONE, card.caption().visibility)
    }

    @Test
    fun `the draw pass keeps the measured height when the picture cannot be had a second time`() {
        val picture = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val builder = builder { call -> if (call == 1) picture else null } // drawn to measure, then lost
        val nodes = parseTopLevelBlocks(markwon, mermaid())
        val spec = PdfContentLayoutBuilder.ParseSpec(markwon, nodes, PAGE_WIDTH_PX, 1f)

        val (top, bottom) = builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f).single()
        var drawn: View? = null
        builder.forEachBlock(spec) { _, _, view -> drawn = view }
        val view = checkNotNull(drawn)

        assertEquals("the draw pass asked again", 2, calls.size)
        assertEquals("the block draws at the height the page left for it", bottom - top, view.measuredHeight)
        assertEquals("the panel stays, empty", View.VISIBLE, view.image().visibility)
        assertEquals("not the source", View.GONE, view.code().visibility)
    }

    @Test
    fun `the draw pass prints the source without asking again when the measure pass had no picture`() {
        val picture = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val builder = builder { call -> if (call == 1) null else picture } // none to measure, one later
        val nodes = parseTopLevelBlocks(markwon, mermaid())
        val spec = PdfContentLayoutBuilder.ParseSpec(markwon, nodes, PAGE_WIDTH_PX, 1f)

        builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f)
        var drawn: View? = null
        builder.forEachBlock(spec) { _, _, view -> drawn = view }

        assertEquals("one export, one question", 1, calls.size)
        assertEquals(View.VISIBLE, checkNotNull(drawn).code().visibility)

        builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f)
        assertEquals("control: the next export asks again", 2, calls.size)
    }

    @Test
    fun `the memo keeps the measured size, never the picture`() {
        val builder = builder(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888))
        val nodes = parseTopLevelBlocks(markwon, "${mermaid()}\n\n```mermaid\npie\n```")

        builder.measureBlockBounds(markwon, nodes, PAGE_WIDTH_PX, 1f)

        assertEquals("precondition: both diagrams are in the memo", 2, builder.diagrams.size)
        assertTrue("only sizes: ${builder.diagrams.values}", builder.diagrams.values.all { it is Size })
        assertEquals(Size(400, 200), builder.diagrams.values.first())
    }

    @Test
    fun `the exporter keeps no diagrams once an export has ended`() {
        val exporter = PdfExporter(context, MarkwonRenderer(context), CountingRenderer())
        // Control: the measure pass alone does fill the memo, so an empty memo below is the clear's doing.
        val printMarkwon = MarkwonRenderer(context).printMarkwonFor(1f)
        exporter.layoutBuilder.measureBlockBounds(
            printMarkwon,
            parseTopLevelBlocks(printMarkwon, mermaid()),
            PAGE_WIDTH_PX,
            1f,
        )
        assertEquals("the measure pass fills the memo", 1, exporter.layoutBuilder.diagrams.size)

        // Robolectric has no native PdfDocument, so the export throws at its first page, after the measure
        // pass has filled the memo again. That is the failure path; the clear must run on it too.
        runCatching { exporter.paginateAndRenderStreaming(mermaid()).close() }

        assertEquals("the memo is emptied", emptyMap<Pair<String, Int>, Size?>(), exporter.layoutBuilder.diagrams)
    }

    /** Counts calls; answers a picture at once, so it needs no main thread. */
    private class CountingRenderer : MermaidDiagramRenderer {
        var calls = 0
        val picture: Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? {
            calls++
            return picture
        }
    }

    @Test
    fun `the exporter never waits for a diagram on the main thread`() {
        val renderer = CountingRenderer()
        val exporter = PdfExporter(context, MarkwonRenderer(context), renderer)

        assertNull(exporter.diagram(source, 100))
        assertEquals("the renderer is not called", 0, renderer.calls)
    }

    @Test
    fun `control - off the main thread the exporter asks the renderer`() {
        val renderer = CountingRenderer()
        val exporter = PdfExporter(context, MarkwonRenderer(context), renderer)
        var answer: Bitmap? = null

        Thread { answer = exporter.diagram(source, 100) }.apply { start() }.join()

        assertEquals(1, renderer.calls)
        assertSame(renderer.picture, answer)
    }

    private companion object {
        const val PAGE_WIDTH_PX = 800
    }
}
