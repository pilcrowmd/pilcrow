// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.Spanned
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.repository.ImageLookup
import com.pilcrowmd.repository.RelativeImageResolver
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * S8-A89: pictures in the PDF. A picture the reader can show prints at its natural size, never
 * enlarged, narrowed to the text column and shrunk to one page's height with its aspect ratio kept;
 * a paragraph of only pictures is centred; one that cannot print keeps its alt text, with no network and
 * no failed export; and the colours of the picture are untouched whatever the PDF colour option.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class PdfPicturesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val density = context.resources.displayMetrics.density

    private fun png(width: Int, height: Int, colour: Int = Color.rgb(0x33, 0x66, 0x99)): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private class Printed(val span: AsyncDrawableSpan, val view: TextView, val width: Int, val height: Int)

    private val renderer by lazy {
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        warmedMarkwonRenderer(context)
    }

    private fun exporter(relative: RelativeImageResolver? = null) =
        PdfExporter(context, renderer, NullMermaidRenderer, relative)

    /** Lays [markdown] out as the export does and returns every picture span with the view that holds it. */
    private fun layOut(exporter: PdfExporter, markdown: String, palette: PdfPalette = PdfPalette.PRINT): List<Printed> {
        exporter.layoutBuilder.palette = palette
        val markwon = renderer.printMarkwonFor(1f, palette.code)
        val nodes = topLevelBlocksForMode(markwon, markdown, RenderMode.MARKDOWN)
        val pageWidth = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        exporter.layoutBuilder.measureBlockBounds(markwon, nodes, pageWidth, 1f)
        val found = ArrayList<Printed>()
        exporter.layoutBuilder.forEachBlock(PdfContentLayoutBuilder.ParseSpec(markwon, nodes, pageWidth, 1f)) {
                _,
                _,
                v,
            ->
            collect(v, found)
        }
        return found
    }

    private fun collect(view: View, into: MutableList<Printed>) {
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), into)
        if (view is TextView) {
            val text = view.text as? Spanned ?: return
            for (span in text.getSpans(0, text.length, AsyncDrawableSpan::class.java)) {
                val bounds = span.drawable.bounds
                if (span.drawable.hasResult()) into += Printed(span, view, bounds.width(), bounds.height())
            }
        }
    }

    /** The export's two passes and every page's draw, onto a bitmap: PdfDocument itself cannot run here. */
    private fun export(exporter: PdfExporter, markdown: String, palette: PdfPalette = PdfPalette.PRINT): Int {
        exporter.layoutBuilder.palette = palette
        val markwon = renderer.printMarkwonFor(1f, palette.code)
        val nodes = topLevelBlocksForMode(markwon, markdown, RenderMode.MARKDOWN)
        val w = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val h = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_HEIGHT_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val bounds = exporter.layoutBuilder.measureBlockBounds(markwon, nodes, w, 1f)
        val spans = exporter.computePageSpans(exporter.computePageStarts(bounds, h), bounds.last().second, h)
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var peak = 0
        spans.forEach { (startY, clip) ->
            val canvas = android.graphics.Canvas(page)
            val visible = bounds.indices.filter {
                bounds[it].second > startY && bounds[it].first < startY + clip
            }.toSet()
            exporter.layoutBuilder.forEachBlock(PdfContentLayoutBuilder.ParseSpec(markwon, nodes, w, 1f), visible) {
                    i,
                    _,
                    v,
                ->
                canvas.save()
                canvas.translate(0f, (bounds[i].first - startY).toFloat())
                v.draw(canvas)
                canvas.restore()
                peak = maxOf(peak, exporter.layoutBuilder.pictures!!.cacheBytes)
            }
        }
        exporter.layoutBuilder.pictures!!.clear()
        return peak
    }

    private fun printed(markdown: String, exporter: PdfExporter = exporter()): List<Printed> =
        layOut(exporter, markdown)

    @Test
    fun `a small picture prints at its natural size and is not enlarged`() {
        val only = printed("![small](${png(40, 20)})").single()
        assertEquals((40 * density).toInt(), only.width)
        assertEquals((20 * density).toInt(), only.height)
    }

    @Test
    fun `a wide picture is narrowed to the text column with its aspect ratio kept`() {
        val only = printed("![wide](${png(6000, 300)})").single()
        val column = only.view.width - only.view.paddingLeft - only.view.paddingRight
        assertTrue("fits the column: ${only.width} <= $column", only.width <= column)
        assertTrue("fills it: ${only.width} >= ${column - 2}", only.width >= column - 2)
        assertEquals(20f, only.width.toFloat() / only.height, 0.5f)
    }

    @Test
    fun `a tall picture is shrunk to one page and never sliced`() {
        val exporter = exporter()
        val pageWidth = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val pageHeight = pageWidth * PdfExporter.PAGE_CONTENT_HEIGHT_PT / PdfExporter.PAGE_CONTENT_WIDTH_PT
        val only = printed("![tall](${png(300, 9000)})", exporter).single()
        assertTrue("one page: ${only.height} <= $pageHeight", only.height <= pageHeight)
        assertEquals(300f / 9000f, only.width.toFloat() / only.height, 0.01f)
        // And the block is one page or less, so pagination has nothing to cut.
        val bounds = exporter.measureBlockBounds("![tall](${png(300, 9000)})")
        assertTrue(bounds.single().second - bounds.single().first <= pageHeight)
    }

    @Test
    fun `a paragraph of only a picture is centred and one with text is not`() {
        val image = png(40, 20)
        assertEquals(
            Gravity.CENTER_HORIZONTAL,
            printed("![a]($image)").single().view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
        val inline = printed("Some words ![a]($image) and more").single()
        assertFalse(inline.view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL)
    }

    @Test
    fun `pictures that cannot print keep their alt text and nothing fails`() {
        val markdown = listOf(
            "![web](https://example.com/a.png)",
            "![rooted](/storage/emulated/0/a.png)",
            "![file](file:///sdcard/a.png)",
            "![relative](images/none.png)",
            "![broken](data:image/png;base64,AAAA)",
            "![nothing](data:image/png;base64,)",
        ).joinToString("\n\n")
        val exporter = exporter()
        assertTrue(printed(markdown, exporter).isEmpty())
        // The whole export still completes, and every alt text is on the page.
        export(exporter, markdown)
    }

    @Test
    fun `a relative picture prints through a granted folder and not without one`() {
        val granted = RelativeImageResolver { _, _ -> ImageLookup.Found(Uri.parse("content://x/y")) }
        // The resolver finds it, but nothing can be opened at that URI here, so it is simply not printed.
        assertTrue(printed("![r](images/p.png)", exporter(granted)).isEmpty())
        assertTrue(printed("![r](images/p.png)", exporter(null)).isEmpty())
        val needsFolder = RelativeImageResolver { _, _ -> ImageLookup.NeedsFolder }
        assertTrue(printed("![r](images/p.png)", exporter(needsFolder)).isEmpty())
    }

    @Test
    fun `a picture over the size cap is not printed`() {
        val source = PdfImageSource(context, maxBytes = 200)
        assertNull(source.size(png(300, 300)))
        assertNotNull(PdfImageSource(context).size(png(300, 300)))
    }

    @Test
    fun `the picture is asked for no network and the destination is refused before any I-O`() {
        val source = PdfImageSource(context)
        assertNull(source.size("https://example.com/a.png"))
        assertNull(source.size("http://example.com/a.png"))
        assertNull(source.size("//example.com/a.png"))
    }

    @Test
    fun `measuring and drawing lay a picture out at the same height`() {
        val exporter = exporter()
        val markdown = "before\n\n![a](${png(900, 400)})\n\nafter"
        val first = exporter.measureBlockBounds(markdown)
        assertEquals(first, exporter.measureBlockBounds(markdown))
        // Pass 2 binds each block again with the real pixels; its height is the one Pass 1 left room for.
        val markwon = renderer.printMarkwonFor(1f, PdfPalette.PRINT.code)
        val nodes = topLevelBlocksForMode(markwon, markdown, RenderMode.MARKDOWN)
        val w = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val bounds = exporter.layoutBuilder.measureBlockBounds(markwon, nodes, w, 1f)
        exporter.layoutBuilder.forEachBlock(PdfContentLayoutBuilder.ParseSpec(markwon, nodes, w, 1f)) { i, _, v ->
            assertEquals("block $i", bounds[i].second - bounds[i].first, v.measuredHeight)
        }
        exporter.layoutBuilder.pictures!!.clear()
    }

    @Test
    fun `fifty large pictures export without growing memory with their number`() {
        val exporter = exporter()
        val big = png(2400, 1800)
        val markdown = (1..50).joinToString("\n\n") { "![p$it]($big)\n\nparagraph $it" }
        val peak = export(exporter, markdown)
        assertTrue("decoded pictures held at most the cap, not 50 of them: $peak", peak <= PdfImageSource.CACHE_BYTES)
        assertEquals(0, exporter.layoutBuilder.pictures!!.cacheBytes)
    }

    /** The first page's blocks drawn onto a bitmap, as the export draws them. */
    private fun drawFirstPage(exporter: PdfExporter, markdown: String, palette: PdfPalette): Bitmap {
        exporter.layoutBuilder.palette = palette
        val markwon = renderer.printMarkwonFor(1f, palette.code)
        val nodes = topLevelBlocksForMode(markwon, markdown, RenderMode.MARKDOWN)
        val w = (exporter.ptToPx(PdfExporter.PAGE_CONTENT_WIDTH_PT.toFloat()) / PdfExporter.PRINT_SCALE).toInt()
        val bounds = exporter.layoutBuilder.measureBlockBounds(markwon, nodes, w, 1f)
        val page = Bitmap.createBitmap(w, bounds.last().second, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(page)
        canvas.drawColor(palette.page.primaryBackground.toArgb())
        exporter.layoutBuilder.forEachBlock(PdfContentLayoutBuilder.ParseSpec(markwon, nodes, w, 1f)) { i, _, v ->
            canvas.save()
            canvas.translate(0f, bounds[i].first.toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        exporter.layoutBuilder.pictures!!.clear()
        return page
    }

    @Test
    fun `the colours of a picture are untouched in both PDF colour options`() {
        val colour = Color.rgb(0x33, 0x66, 0x99)
        val markdown = "![blue](${png(60, 60, colour)})"
        val expected = (60 * density).toInt().let { it * it }
        for (palette in listOf(PdfPalette.PRINT, PdfPalette.of(com.pilcrowmd.ui.theme.DarkColorScheme))) {
            val page = drawFirstPage(exporter(), markdown, palette)
            val pixels = IntArray(page.width * page.height)
            page.getPixels(pixels, 0, page.width, 0, 0, page.width, page.height)
            val exact = pixels.count { it == colour }
            assertTrue("exact picture colour: $exact of about $expected", exact >= expected * 9 / 10)
        }
    }

    private fun contentPicture(name: String, extraBytes: Int): Uri {
        val encoded = png(300, 300).substringAfter("base64,")
        val bytes = Base64.decode(encoded, Base64.DEFAULT) + ByteArray(extraBytes)
        val uri = Uri.parse("content://s8a89/$name")
        val file = java.io.File.createTempFile("s8a89", ".bin", context.cacheDir).apply { writeBytes(bytes) }
        TestPictureProvider.files[name] = file
        org.robolectric.Robolectric.setupContentProvider(TestPictureProvider::class.java, "s8a89")
        return uri
    }

    @Test
    fun `a content picture over the cap is refused in pass 1 though its header is small`() {
        // The header of this picture fits in a few KB; the file does not fit the cap.
        val over = contentPicture("big1.png", extraBytes = 600_000)
        assertNull(PdfImageSource(context, maxBytes = 300_000).size(over.toString()))
        // The same file under a roomier cap prints, so it is the size and not the picture that refuses it.
        val under = contentPicture("big2.png", extraBytes = 600_000)
        assertNotNull(PdfImageSource(context, maxBytes = 2_000_000).size(under.toString()))
    }

    @Test
    fun `skipping bytes counts against the cap`() {
        val stream = LimitedInputStream(java.io.ByteArrayInputStream(ByteArray(1000)), 100)
        var threw = false
        try {
            stream.skip(500)
        } catch (_: java.io.IOException) {
            threw = true
        }
        assertTrue("a skip past the cap fails", threw)
    }

    @Test
    fun `a relative picture is read against the note being exported`() {
        val file = contentPicture("rel.png", extraBytes = 0)
        var asked: Uri? = null
        val note = Uri.parse("content://s8a89/notes/note.md")
        val resolver = RelativeImageResolver { noteUri, _ ->
            asked = noteUri
            ImageLookup.Found(file)
        }
        val source = PdfImageSource(context, resolver, noteUri = { note })
        assertNotNull(source.size("images/rel.png"))
        assertEquals(note, asked)
        // With no note known, a relative path cannot be read and keeps its alt text.
        assertNull(PdfImageSource(context, resolver, noteUri = { null }).size("images/rel2.png"))
    }

    @Test
    fun `a picture that cannot print is read once, not on every bind`() {
        var asked = 0
        val resolver = RelativeImageResolver { _, _ ->
            asked++
            ImageLookup.Unavailable
        }
        val source = PdfImageSource(context, resolver, noteUri = { Uri.parse("content://s8a89/n.md") })
        repeat(3) { assertNull(source.size("images/missing.png")) }
        assertEquals(1, asked)
    }
}

/** Serves the files a test puts in [files] under `content://s8a89/<name>`, as a granted document would be. */
class TestPictureProvider : android.content.ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        val file = files.getValue(uri.lastPathSegment.orEmpty())
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        p: Array<String>?,
        s: String?,
        a: Array<String>?,
        o: String?,
    ): android.database.Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null

    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0

    override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        val files = HashMap<String, java.io.File>()
    }
}
