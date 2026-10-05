// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.text.Spanned
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import coil.EventListener
import coil.decode.Decoder
import coil.request.ImageRequest
import coil.request.Options
import com.pilcrowmd.repository.ImageLookup
import com.pilcrowmd.repository.RelativeImageResolver
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * M-93: markdown images render from what the app can read, and everything else becomes the
 * placeholder with its alt text — never a crash, never a network call.
 *
 * Loads are asynchronous (Coil decodes off the main thread and posts the result back), so every
 * assertion first waits for the drawable to HAVE a result ([awaitResult]); the initial state, no
 * result, matches none of the outcomes asserted here. Off-main decoding is checked on the thread
 * the decode reports, not by looking for "no result yet": in a shared test JVM the time a result
 * lands proves nothing about the thread it was decoded on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownImagesTest {

    private lateinit var context: Context
    private lateinit var networkCalls: AtomicInteger
    private lateinit var markwon: Markwon
    private lateinit var decodeThreadHadLooper: AtomicReference<Boolean>
    private lateinit var lookups: ConcurrentHashMap<String, ImageLookup>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        networkCalls = AtomicInteger()
        // Counts every call Coil asks for, then refuses it like production's RefuseNetwork.
        val countingFactory = Call.Factory { _: Request ->
            networkCalls.incrementAndGet()
            throw IOException("test: network refused")
        }
        decodeThreadHadLooper = AtomicReference()
        // Records whether the decode ran on a looper thread (main) or a worker thread (Coil's pool).
        val decodeWatcher = object : EventListener {
            override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
                decodeThreadHadLooper.set(Looper.myLooper() != null)
            }
        }
        lookups = ConcurrentHashMap()
        // What the folder walk answers for each relative path; anything else is unavailable.
        val resolver = RelativeImageResolver { _, path -> lookups[path] ?: ImageLookup.Unavailable }
        markwon = buildPilcrowMarkwon(
            context,
            images = ReaderImages(
                MarkdownImageLoader.createImageLoader(context, countingFactory, decodeWatcher, resolver),
                ImageBase().apply { noteUri = Uri.parse("content://com.pilcrowmd.test/document/trip.md") },
            ),
        )
    }

    @Test
    fun `an embedded image renders as a bitmap, loaded off the main thread`() {
        val bitmap = (awaitResult(render("![Hut](${dataUri(png(40, 30))})")) as BitmapDrawable).bitmap

        assertEquals(40, bitmap.width)
        assertEquals(30, bitmap.height)
        // Decoded through Coil (an event was seen), on a worker thread: no looper, so not the main one.
        assertEquals("decode ran on a looper thread", false, decodeThreadHadLooper.get())
    }

    @Test
    fun `a readable content URI renders as a bitmap`() {
        val uri = Uri.parse("content://com.pilcrowmd.test/images/photo.png")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(png(24, 12)))

        val result = awaitResult(render("![Photo]($uri)"))

        val bitmap = (result as BitmapDrawable).bitmap
        assertEquals(24, bitmap.width)
        assertEquals(12, bitmap.height)
    }

    @Test
    fun `a missing file becomes the placeholder with its alt text`() {
        val result = awaitResult(render("![Hut at dusk](content://com.pilcrowmd.test/missing.png)"))

        assertEquals("Hut at dusk", (result as ImagePlaceholderDrawable).label)
    }

    @Test
    fun `a corrupt image becomes the placeholder`() {
        val garbage = Base64.getEncoder().encodeToString("not a picture at all".toByteArray())

        val result = awaitResult(render("![Broken](data:image/png;base64,$garbage)"))

        assertEquals("Broken", (result as ImagePlaceholderDrawable).label)
    }

    @Test
    fun `a remote image becomes the placeholder and makes no network call`() {
        val result = awaitResult(render("![Trail badge](https://example.com/badge.png)"))

        assertEquals("Trail badge", (result as ImagePlaceholderDrawable).label)
        // Let anything still queued on Coil's dispatchers run before counting.
        Thread.sleep(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("network calls", 0, networkCalls.get())
    }

    @Test
    fun `a relative path no folder grant can bring in is the plain placeholder`() {
        val result = awaitResult(render("![Route map](images/route.png)"))

        assertEquals("Route map", (result as ImagePlaceholderDrawable).label)
    }

    @Test
    fun `a relative path in a granted folder renders as a bitmap, with no network call`() {
        val inFolder = Uri.parse("content://com.pilcrowmd.test/tree/notes/document/hut.png")
        shadowOf(context.contentResolver).registerInputStream(inFolder, ByteArrayInputStream(png(32, 16)))
        lookups["images/hut.png"] = ImageLookup.Found(inFolder)

        val bitmap = (awaitResult(render("![Hut](images/hut.png)")) as BitmapDrawable).bitmap

        assertEquals(32, bitmap.width)
        assertEquals(16, bitmap.height)
        assertEquals("network calls", 0, networkCalls.get())
    }

    @Test
    fun `a relative path a folder grant would bring in says Tap to show`() {
        lookups["images/hut.png"] = ImageLookup.NeedsFolder

        val placeholder = awaitResult(render("![Hut at dusk](images/hut.png)")) as ImagePlaceholderDrawable

        assertEquals("Hut at dusk", placeholder.label)
        assertEquals(ImagePlaceholderDrawable.TAP_TO_SHOW, placeholder.actionLabel)
        assertEquals("network calls", 0, networkCalls.get())
    }

    @Test
    fun `tapping a relative image reaches the reader handler, and a remote one has no tap`() {
        val taps = AtomicInteger()
        val reader = FrameLayout(context).apply { setImageTapHandler { taps.incrementAndGet() } }
        val textView = TextView(context).also { reader.addView(it) }
        markwon.setMarkdown(textView, "![Hut](images/hut.png) and ![Badge](https://example.com/b.png)")
        val text = textView.text as Spanned

        val tapSpans = text.getSpans(0, text.length, RelativeImageTapSpan::class.java)
        assertEquals("tap spans", 1, tapSpans.size)
        assertEquals(
            "on the relative image",
            "Hut",
            text.subSequence(text.getSpanStart(tapSpans[0]), text.getSpanEnd(tapSpans[0])).toString(),
        )
        tapSpans[0].onClick(textView)
        assertEquals(1, taps.get())
    }

    @Test
    fun `an image with no alt text is labelled Image`() {
        val result = awaitResult(render("![](images/route.png)"))

        assertEquals(ImagePlaceholderDrawable.LABEL_NO_ALT, (result as ImagePlaceholderDrawable).label)
    }

    @Test
    fun `an oversized image is downsampled to the screen width`() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val wide = 8 * screenWidth

        val bitmap = (awaitResult(render("![Wide](${dataUri(png(wide, 300))})")) as BitmapDrawable).bitmap

        assertTrue("decoded ${bitmap.width} px wide for a $screenWidth px screen", bitmap.width <= screenWidth)
    }

    @Test
    fun `a very tall image is capped at the decode height`() {
        val tall = MarkdownImageLoader.MAX_DECODE_HEIGHT_PX * 3

        val bitmap = (awaitResult(render("![Tall](${dataUri(png(20, tall))})")) as BitmapDrawable).bitmap

        assertTrue("decoded ${bitmap.height} px tall", bitmap.height <= MarkdownImageLoader.MAX_DECODE_HEIGHT_PX)
    }

    /** Renders [markdown] into a TextView, which schedules its images, and returns the one image. */
    private fun render(markdown: String): AsyncDrawable {
        val textView = TextView(context)
        markwon.setMarkdown(textView, markdown)
        val spans = (textView.text as Spanned).getSpans(0, textView.text.length, AsyncDrawableSpan::class.java)
        assertEquals("image spans", 1, spans.size)
        return spans.single().drawable
    }

    /** Waits for the load to finish, running the main looper so Coil can post its result. */
    private fun awaitResult(drawable: AsyncDrawable): Any {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!drawable.hasResult()) {
            check(System.currentTimeMillis() < deadline) { "image did not load within $TIMEOUT_MS ms" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        return drawable.result
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun dataUri(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 200L
    }
}
