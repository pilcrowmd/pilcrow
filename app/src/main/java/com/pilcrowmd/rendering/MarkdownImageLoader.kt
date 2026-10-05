// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import coil.size.Scale
import com.pilcrowmd.domain.markdown.ImageDestination
import com.pilcrowmd.repository.ImageLookup
import com.pilcrowmd.repository.RelativeImageResolver
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import okhttp3.Call
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.source
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Base64

/**
 * M-93: loads a markdown image into its [AsyncDrawable], or gives it an [ImagePlaceholderDrawable].
 *
 * Only an embedded `data:` image, an absolute `content://` URI and a path relative to the note
 * ([imageBase]) reach [imageLoader]; every other destination gets the placeholder at once, with no
 * I/O ([ImageDestination]). A relative path that a folder grant would bring in gets the placeholder
 * with "Tap to show". Decoding runs on
 * Coil's background dispatchers, downsampled to the screen width and at most [MAX_DECODE_HEIGHT_PX]
 * tall; any failure — missing file, no grant, corrupt bytes, an unsupported format, out of memory —
 * ends as the placeholder, never as a crash (Safeguard 3).
 *
 * Called on the main thread only (Markwon's scheduler), so [requests] needs no lock.
 */
internal class MarkdownImageLoader(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val colorScheme: PilcrowColorScheme,
    private val scale: Float,
    private val imageBase: ImageBase = ImageBase(),
) : AsyncDrawableLoader() {

    private val requests = HashMap<AsyncDrawable, Disposable>()

    override fun load(drawable: AsyncDrawable) {
        val data: Any = when (val destination = ImageDestination.classify(drawable.destination)) {
            is ImageDestination.Content -> Uri.parse(destination.uri)
            is ImageDestination.Embedded -> destination
            is ImageDestination.Relative -> imageBase.noteUri?.let { RelativeImage(it, destination.path) }
                ?: return drawable.setResult(placeholderFor(drawable))
            else -> return drawable.setResult(placeholderFor(drawable))
        }
        val metrics = context.resources.displayMetrics
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(metrics.widthPixels, MAX_DECODE_HEIGHT_PX)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .target(
                onSuccess = { result ->
                    requests.remove(drawable)
                    drawable.setResult(sized(result, metrics.density))
                },
            )
            .listener(
                onError = { _, error ->
                    requests.remove(drawable)
                    drawable.setResult(placeholderFor(drawable, tapToShow = error.throwable is NeedsFolderAccess))
                },
            )
            .build()
        // A memory-cache hit completes inside enqueue, and then there is nothing left to cancel.
        imageLoader.enqueue(request).takeUnless { it.isDisposed }?.let { requests[drawable] = it }
    }

    override fun cancel(drawable: AsyncDrawable) {
        requests.remove(drawable)?.dispose()
    }

    override fun placeholder(drawable: AsyncDrawable): Drawable? = null

    private fun placeholderFor(drawable: AsyncDrawable, tapToShow: Boolean = false): Drawable {
        val metrics = context.resources.displayMetrics
        val maxWidth = drawable.lastKnownCanvasWidth.takeIf { it > 0 } ?: metrics.widthPixels
        val alt = (drawable as? AltTextAsyncDrawable)?.altText.orEmpty()
        val action = ImagePlaceholderDrawable.TAP_TO_SHOW.takeIf { tapToShow }
        return ImagePlaceholderDrawable(alt, colorScheme, metrics, scale, maxWidth, action)
    }

    /**
     * An image's pixels are read as dp, the way a browser reads them as CSS pixels, so a small icon
     * keeps its size on a dense screen. Markwon then narrows anything wider than the text column.
     */
    private fun sized(result: Drawable, density: Float): Drawable = result.apply {
        val bitmap = (this as? BitmapDrawable)?.bitmap
        val w = bitmap?.width ?: intrinsicWidth
        val h = bitmap?.height ?: intrinsicHeight
        setBounds(0, 0, (w * density).toInt().coerceAtLeast(1), (h * density).toInt().coerceAtLeast(1))
    }

    internal companion object {
        /**
         * Taller than this is fitted down: a bitmap past the GPU's texture limit does not draw, and
         * this caps one image at (screen width × 4096 × 4) bytes.
         */
        const val MAX_DECODE_HEIGHT_PX = 4096

        /** All decoded markdown images together, kept for re-binds and scrolling back. */
        const val MEMORY_CACHE_BYTES = 32 * 1024 * 1024

        /**
         * The reader's own Coil instance, separate from the Mermaid one. No disk cache (no copies of
         * the user's pictures in the cache folder) and a [callFactory] that refuses every network
         * call, so nothing reaches the network even if a URL got past [ImageDestination].
         */
        fun createImageLoader(
            context: Context,
            callFactory: Call.Factory = RefuseNetwork,
            // Lets a test see which thread a decode runs on.
            eventListener: EventListener = EventListener.NONE,
            // Turns `images/x.png` into a document in a granted folder. Null: relative paths never load.
            relativeImages: RelativeImageResolver? = null,
        ): ImageLoader =
            ImageLoader.Builder(context)
                .callFactory(callFactory)
                .eventListener(eventListener)
                .components {
                    add(EmbeddedImageFetcher.Factory())
                    if (relativeImages != null) add(RelativeImageFetcher.Factory(relativeImages))
                }
                .memoryCache { MemoryCache.Builder(context).maxSizeBytes(MEMORY_CACHE_BYTES).build() }
                .diskCache(null as DiskCache?)
                .networkObserverEnabled(false)
                // Software bitmaps: the reader's text can be drawn to a software canvas.
                .allowHardware(false)
                .build()
    }
}

/** Every network call fails. The reader's image path never needs one (M-93, M-70). */
internal object RefuseNetwork : Call.Factory {
    override fun newCall(request: Request): Call = throw IOException("The reader loads no remote images")
}

/** Decodes a `data:` image's base64 on Coil's fetcher dispatcher, never on the main thread. */
internal class EmbeddedImageFetcher(private val data: ImageDestination.Embedded, private val options: Options) :
    Fetcher {
    override suspend fun fetch(): FetchResult {
        // The MIME decoder skips the line breaks a long data URI is often wrapped with.
        val bytes = Base64.getMimeDecoder().decode(data.base64)
        return SourceResult(ImageSource(Buffer().write(bytes), options.context), data.mimeType, DataSource.MEMORY)
    }

    class Factory : Fetcher.Factory<ImageDestination.Embedded> {
        override fun create(data: ImageDestination.Embedded, options: Options, imageLoader: ImageLoader): Fetcher =
            EmbeddedImageFetcher(data, options)
    }
}

/** The note the reader is showing, which relative image paths are read against. Main thread only. */
class ImageBase {
    var noteUri: Uri? = null
}

/** A relative image path, with the note it is relative to. */
internal data class RelativeImage(val noteUri: Uri, val path: String)

/** The picture is next to the note, and a folder grant would show it. */
internal class NeedsFolderAccess : IOException("The note's folder has not been granted")

/**
 * Resolves a relative path through the granted folder, then reads it, all on Coil's fetcher
 * dispatcher. Missing, outside the grant, or unreadable: an error, so the placeholder shows.
 */
internal class RelativeImageFetcher(
    private val data: RelativeImage,
    private val options: Options,
    private val resolver: RelativeImageResolver,
) : Fetcher {
    override suspend fun fetch(): FetchResult = when (val lookup = resolver.resolve(data.noteUri, data.path)) {
        is ImageLookup.Found -> {
            val stream = options.context.contentResolver.openInputStream(lookup.uri)
                ?: throw FileNotFoundException("Could not open ${data.path}")
            SourceResult(ImageSource(stream.source().buffer(), options.context), null, DataSource.DISK)
        }
        ImageLookup.NeedsFolder -> throw NeedsFolderAccess()
        ImageLookup.Unavailable -> throw FileNotFoundException("No picture at ${data.path}")
    }

    class Factory(private val resolver: RelativeImageResolver) : Fetcher.Factory<RelativeImage> {
        override fun create(data: RelativeImage, options: Options, imageLoader: ImageLoader): Fetcher =
            RelativeImageFetcher(data, options, resolver)
    }
}
