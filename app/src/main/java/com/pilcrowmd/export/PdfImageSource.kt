// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spanned
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.pilcrowmd.domain.markdown.ImageDestination
import com.pilcrowmd.repository.ImageLookup
import com.pilcrowmd.repository.RelativeImageResolver
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * S8-A89: the pictures of one PDF export. Synchronous, like the diagram callback: the export runs on
 * IO and blocks here.
 *
 * Prints what the reader can show and nothing else: an embedded `data:` image, an absolute
 * `content://` URI and a relative path inside a folder grant the app already holds ([ImageDestination]).
 * A web picture, a missing grant, a broken or unsupported file, or one over [MAX_BYTES] answers null,
 * and the PDF keeps the alt text it prints today. It never asks for a permission and never reaches the
 * network: a remote destination is refused before any I/O.
 *
 * Pass 1 of the export only needs sizes, so [size] reads the header and nothing else; [bitmap] decodes
 * when a block is drawn, bounded to what the page can show, and keeps a small cache for blocks that
 * span pages. Memory therefore never grows with the number of pictures. [clear] ends an export.
 */
internal class PdfImageSource(
    private val context: Context,
    private val relativeImages: RelativeImageResolver? = null,
    private val noteUri: () -> Uri? = { null },
    // Only a test lowers this, to prove the cap without a 50 MB picture.
    private val maxBytes: Long = MAX_BYTES,
) {
    /** One picture's natural size in pixels, or null when it cannot be printed. Kept per export. */
    private val sizes = HashMap<String, Size?>()

    private class Cached(val bitmap: Bitmap, val bytes: Int)

    private val cache = LinkedHashMap<Pair<String, Int>, Cached>(CACHE_SLOTS, LOAD_FACTOR, true)
    internal var cacheBytes = 0
        private set

    /** The picture's natural size in pixels, or null: print the alt text. Header only, no pixels decoded. */
    fun size(destination: String): Size? {
        // containsKey, not getOrPut: a picture that cannot print is remembered as null and not read again.
        if (destination !in sizes) sizes[destination] = guarded { readSize(destination) }
        return sizes[destination]
    }

    /**
     * The picture decoded for the page, no larger than needed to fill [fitWidthPx] by [fitHeightPx]
     * (a power-of-two sample, so colours are untouched), or null when it cannot be had this time.
     */
    fun bitmap(destination: String, fitWidthPx: Int, fitHeightPx: Int): Bitmap? {
        val key = destination to fitWidthPx
        cache[key]?.let { return it.bitmap }
        val decoded = guarded { decode(destination, fitWidthPx, fitHeightPx) } ?: return null
        val bytes = decoded.allocationByteCount
        if (bytes <= CACHE_BYTES) {
            cache[key] = Cached(decoded, bytes)
            cacheBytes += bytes
            trimCache()
        }
        return decoded
    }

    /** An export ends (or fails): keep no picture. */
    fun clear() {
        sizes.clear()
        cache.clear()
        cacheBytes = 0
    }

    private fun trimCache() {
        val eldest = cache.entries.iterator()
        while (cacheBytes > CACHE_BYTES && eldest.hasNext()) {
            cacheBytes -= eldest.next().value.bytes
            eldest.remove()
        }
    }

    private fun readSize(destination: String): Size? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(destination)?.use {
            BitmapFactory.decodeStream(it, null, options)
            // The header says nothing about the file's size, so read the rest through the cap: one over
            // it fails here, in Pass 1, and keeps its alt text instead of printing as an empty box.
            if (options.outWidth > 0 && options.outHeight > 0) it.drain()
        }
        return if (options.outWidth > 0 && options.outHeight > 0) Size(options.outWidth, options.outHeight) else null
    }

    private fun decode(destination: String, fitWidthPx: Int, fitHeightPx: Int): Bitmap? {
        val natural = sizes[destination] ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(natural, fitWidthPx, fitHeightPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(destination)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** A fresh stream over the picture, or null when it is not one the export may read. */
    private fun open(destination: String): InputStream? {
        val stream = when (val kind = ImageDestination.classify(destination)) {
            is ImageDestination.Embedded -> embedded(kind)
            is ImageDestination.Content -> context.contentResolver.openInputStream(Uri.parse(kind.uri))
            is ImageDestination.Relative -> relative(kind)
            else -> null // web pictures, `file:` and the rest are never read
        }
        return stream?.let { LimitedInputStream(it, maxBytes) }
    }

    private fun embedded(kind: ImageDestination.Embedded): InputStream? {
        // Three bytes for every four characters: refuse before decoding what is over the cap anyway.
        if (kind.base64.length / BASE64_RATIO * BASE64_BYTES > maxBytes) return null
        return ByteArrayInputStream(Base64.getMimeDecoder().decode(kind.base64))
    }

    private fun relative(kind: ImageDestination.Relative): InputStream? {
        val resolver = relativeImages ?: return null
        val note = noteUri() ?: return null
        val lookup = runBlocking { resolver.resolve(note, kind.path) }
        return (lookup as? ImageLookup.Found)?.let { context.contentResolver.openInputStream(it.uri) }
    }

    /** Anything that goes wrong, an out-of-memory included, is "cannot print": never a failed export. */
    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        android.util.Log.w("PdfExporter", "Picture skipped: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        android.util.Log.w("PdfExporter", "Picture skipped, out of memory: ${e.message}")
        null
    }

    internal companion object {
        /** Over this a picture is not printed, as on the Mac app. */
        const val MAX_BYTES = 50L * 1024 * 1024

        /** Decoded pictures kept for blocks that span pages: small and per export, not per picture. */
        const val CACHE_BYTES = 24 * 1024 * 1024
        private const val CACHE_SLOTS = 8
        private const val LOAD_FACTOR = 0.75f
        private const val BASE64_RATIO = 4
        private const val BASE64_BYTES = 3

        /** The largest power-of-two sample that still leaves the picture at least [fitW] by [fitH]. */
        fun sampleSize(natural: Size, fitW: Int, fitH: Int): Int {
            var sample = 1
            while (natural.width / (sample * 2) >= fitW.coerceAtLeast(1) &&
                natural.height / (sample * 2) >= fitH.coerceAtLeast(1)
            ) {
                sample *= 2
            }
            return sample
        }
    }
}

/** Reads at most [limit] bytes, then fails: a file over the cap is never pulled into memory. */
internal class LimitedInputStream(stream: InputStream, private var remaining: Long) : FilterInputStream(stream) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) spend(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) spend(count.toLong())
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) spend(skipped)
        return skipped
    }

    private fun spend(bytes: Long) {
        remaining -= bytes
        if (remaining < 0) throw IOException("Picture is over the size cap")
    }
}

/**
 * S8-A89, PDF only: gives every picture span in [root]'s text its picture, the way the maths is resolved
 * before measuring. A picture prints at its natural size (its pixels read as dp, as the reader reads them),
 * never enlarged, narrowed to the text column and shrunk to fit one page's text height with the aspect
 * ratio kept, so it is never sliced. A span whose picture cannot be had keeps its alt text.
 *
 * [measuring] is Pass 1, which only needs sizes: a blank drawable of the final size stands in, so no pixels
 * are decoded until a block is drawn, and both passes lay the block out at the same height.
 *
 * A paragraph that holds nothing but pictures is centred, as the Mac app does; any other paragraph keeps its
 * alignment. Pooled views are reset on every bind.
 */
internal fun PdfImageSource.printPictures(
    root: View,
    pageContentWidthPx: Int,
    pageTextHeightPx: Int,
    measuring: Boolean,
) {
    when (root) {
        is ViewGroup -> for (i in 0 until root.childCount) {
            printPictures(root.getChildAt(i), pageContentWidthPx, pageTextHeightPx, measuring)
        }
        is TextView -> printPicturesIn(root, pageContentWidthPx, pageTextHeightPx, measuring)
    }
}

/** The room a picture in [view] has: the block's width and one page's text height, less what surrounds it. */
private fun roomAround(view: View, pageContentWidthPx: Int, pageTextHeightPx: Int): Size {
    var horizontal = 0
    var vertical = 0
    var node: View? = view
    while (node != null) {
        horizontal += node.paddingLeft + node.paddingRight
        vertical += node.paddingTop + node.paddingBottom
        val margins = node.layoutParams as? ViewGroup.MarginLayoutParams
        if (margins != null) {
            vertical += margins.topMargin + margins.bottomMargin
            // The block itself is measured at the full width, so only a child's side margins narrow it.
            if (node.parent is View) horizontal += margins.leftMargin + margins.rightMargin
        }
        node = node.parent as? View
    }
    return Size((pageContentWidthPx - horizontal).coerceAtLeast(1), (pageTextHeightPx - vertical).coerceAtLeast(1))
}

private fun PdfImageSource.printPicturesIn(
    view: TextView,
    pageContentWidthPx: Int,
    pageTextHeightPx: Int,
    measuring: Boolean,
) {
    val text = view.text as? Spanned
    val spans = text?.getSpans(0, text.length, AsyncDrawableSpan::class.java).orEmpty()
        .filter { it !is JLatexAsyncDrawableSpan }
    var printed = false
    if (spans.isNotEmpty()) {
        val room = roomAround(view, pageContentWidthPx, pageTextHeightPx)
        for (span in spans) {
            if (fillPicture(view, span, room, measuring)) printed = true
        }
    }
    alignPictureParagraph(view, text, spans, printed)
    // The text was laid out with the placeholders' size; laying it out again takes the pictures' (M-181).
    if (printed) view.text = view.text
}

/** Gives one picture span its picture, sized to [room]; false when it has none to give (alt text stays). */
private fun PdfImageSource.fillPicture(
    view: TextView,
    span: AsyncDrawableSpan,
    room: Size,
    measuring: Boolean,
): Boolean {
    val picture = span.drawable
    if (picture.hasResult()) return false
    val natural = size(picture.destination) ?: return false
    val density = view.resources.displayMetrics.density
    val scale = minOf(1f, room.width / (natural.width * density), room.height / (natural.height * density))
    val width = (natural.width * density * scale).toInt().coerceAtLeast(1)
    val height = (natural.height * density * scale).toInt().coerceAtLeast(1)
    val bitmap = if (measuring) null else bitmap(picture.destination, width, height)
    val result = bitmap?.let { BitmapDrawable(view.resources, it) } ?: blank(width, height)
    result.setBounds(0, 0, width, height)
    picture.initWithKnownDimensions(room.width, view.textSize)
    picture.setResult(result)
    return true
}

/**
 * A paragraph of only pictures is centred; anything else keeps its alignment. A view is touched only when it
 * printed a picture or was centred before (it is pooled), so other text is left exactly as it was.
 */
private fun alignPictureParagraph(view: TextView, text: Spanned?, spans: List<AsyncDrawableSpan>, printed: Boolean) {
    val before = view.gravity
    if (!printed && (before and Gravity.HORIZONTAL_GRAVITY_MASK) != Gravity.CENTER_HORIZONTAL) return
    val centre = printed && text != null && holdsOnlyPictures(text, spans)
    view.gravity =
        (before and Gravity.VERTICAL_GRAVITY_MASK) or (if (centre) Gravity.CENTER_HORIZONTAL else Gravity.START)
}

private fun blank(width: Int, height: Int): Drawable = GradientDrawable().apply {
    setColor(Color.TRANSPARENT)
    setSize(width, height)
}

/** Whether everything the paragraph prints is a picture, so it can be centred. */
private fun holdsOnlyPictures(text: Spanned, spans: List<AsyncDrawableSpan>): Boolean {
    val covered = BooleanArray(text.length)
    for (span in spans) {
        for (i in text.getSpanStart(span) until text.getSpanEnd(span)) covered[i] = true
    }
    return text.indices.all { covered[it] || text[it].isWhitespace() }
}

/** One page's text height in layout px, from the exporter's own page constants, as the diagram panel uses. */
internal fun pageTextHeightFor(pageContentWidthPx: Int): Int =
    pageContentWidthPx * PdfExporter.PAGE_CONTENT_HEIGHT_PT / PdfExporter.PAGE_CONTENT_WIDTH_PT

/** Reads to the end without keeping anything, so a [LimitedInputStream] can count the whole file. */
private fun InputStream.drain() {
    val buffer = ByteArray(DRAIN_BUFFER_BYTES)
    while (read(buffer) >= 0) Unit
}

private const val DRAIN_BUFFER_BYTES = 64 * 1024
