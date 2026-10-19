// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * [MermaidDiagramRenderer] backed by one hidden [WebView] used only as a compiler (M-70): Mermaid
 * runs in the page, draws the diagram to a PNG, and the app collects the PNG through
 * `evaluateJavascript`. The offline Mermaid security locks are kept here:
 *
 * - no JavaScript interface: results only come back through `evaluateJavascript` callbacks;
 * - no file or content access, network loads blocked, and the [MermaidWebViewClient] serves only
 *   the bundled Mermaid script;
 * - one render at a time ([mutex]), each bounded by [RENDER_TIMEOUT_MS]; the WebView is only ever
 *   touched on the main thread, which only ever suspends, never waits. A render that runs out of
 *   time destroys the WebView, since a hostile script may be spinning in it, and the next render
 *   builds a fresh one.
 *
 * Pictures are cached in memory by source, width and style, and so are failures, so a broken
 * diagram is not compiled again on every scroll. No document text is ever logged.
 *
 * [timeoutMs] is [RENDER_TIMEOUT_MS] in the app; tests only shorten it, to reach the timeout path
 * without a diagram that is slow on purpose.
 */
class WebViewMermaidRenderer internal constructor(appContext: Context, private val timeoutMs: Long) :
    MermaidDiagramRenderer {

    constructor(appContext: Context) : this(appContext, RENDER_TIMEOUT_MS)

    private val context = appContext.applicationContext
    private val mutex = Mutex()
    private val pictures = PictureCache()
    private val failures = LruCache<Key, Boolean>(MAX_FAILURES)
    private val trimScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Main thread only.
    private var webView: WebView? = null
    private var unavailable = false
    private var nextId = 0

    private data class Key(
        val sha256: String,
        val length: Int,
        val widthPx: Int,
        val density: Float,
        val style: MermaidStyle,
    )

    /** The pictures, by key, within [cacheBytes]. */
    private class PictureCache : LruCache<Key, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount

        /**
         * Caches [bitmap], unless it is too large to share the cache: LruCache evicts everything, the
         * new entry included, for one larger than its whole budget, so a quarter of it is the limit.
         */
        fun keep(key: Key, bitmap: Bitmap) {
            if (bitmap.allocationByteCount <= maxSize() / MAX_ENTRY_FRACTION) put(key, bitmap)
        }
    }

    private sealed interface Outcome {
        data class Drawn(val bitmap: Bitmap) : Outcome

        data object Failed : Outcome

        data object Unavailable : Outcome
    }

    override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? {
        // The key is built on the calling thread, before any suspension, so a cached answer lands in
        // the caller's own frame (the reader's UNDISPATCHED bind shows no empty panel for it). Hashing
        // is cheap: Mermaid's own maxTextSize caps a source at 50 000 characters.
        val key = key(source, widthPx, style)
        pictures.get(key)?.let { return it }
        if (failures.get(key) != null) return null
        return mutex.withLock {
            // A caller that queued behind the lock may find its answer already there.
            pictures.get(key) ?: if (failures.get(key) != null) null else draw(key, source)
        }
    }

    private fun key(source: String, widthPx: Int, style: MermaidStyle) = Key(
        sha256(source),
        source.length,
        widthPx.coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX),
        context.resources.displayMetrics.density,
        style,
    )

    /** Test seam: caches [bitmap] as the picture for these arguments, as a finished render would. */
    internal fun cachePictureForTest(source: String, widthPx: Int, style: MermaidStyle, bitmap: Bitmap) =
        pictures.keep(key(source, widthPx, style), bitmap)

    /** Drops every cached picture and the WebView; the next render starts afresh. */
    override fun trim() {
        pictures.evictAll()
        failures.evictAll()
        trimScope.launch {
            mutex.withLock {
                destroyEngine()
                unavailable = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Safeguard 3: a broken diagram or engine must not crash
    private suspend fun draw(key: Key, source: String): Bitmap? {
        // Set once the page has answered for THIS render: from then on, running out of time is
        // the diagram's doing, not a cold or stuck engine's, and is remembered as a failure.
        var attributable = false
        val outcome = try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main.immediate) {
                    drawOnPage(source, key) { attributable = true }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.Failed
        }
        if (outcome == null) withContext(Dispatchers.Main.immediate) { destroyEngine(terminate = true) }
        if (outcome == Outcome.Failed || (outcome == null && attributable)) failures.put(key, true)
        return (outcome as? Outcome.Drawn)?.bitmap?.also { pictures.keep(key, it) }
    }

    private suspend fun drawOnPage(source: String, key: Key, onReady: () -> Unit): Outcome {
        processGone?.let { gone ->
            withTimeoutOrNull(GONE_WAIT_MS) { gone.await() }
            processGone = null
        }
        val view = engine() ?: return Outcome.Unavailable
        if (!awaitReady(view)) return Outcome.Unavailable
        onReady()
        val id = nextId++
        val style = key.style
        val cfg = JSONObject()
            .put("dark", style.dark)
            .put("background", style.background)
            .put("text", style.text)
            .put("line", style.line)
            .put("border", style.border ?: JSONObject.NULL)
            .put("slices", JSONArray(style.slices))
            .put("width", key.widthPx)
            .put("density", key.density.toDouble())
        // The source travels as a quoted JSON string literal, never spliced in raw.
        view.evaluateJavascript("pilcrowRender($id, ${JSONObject.quote(source)}, $cfg)", null)
        val result = awaitResult(view, id) ?: return Outcome.Failed
        view.evaluateJavascript(MermaidPage.forgetScript(id), null)
        val bitmap = if (result.optString("state") == "ok") {
            withContext(Dispatchers.Default) { decodePng(result.optString("png")) }
        } else {
            null
        }
        return if (bitmap == null) Outcome.Failed else Outcome.Drawn(bitmap)
    }

    /** True once Mermaid is loaded in the page; false if it cannot load at all. */
    private suspend fun awaitReady(view: WebView): Boolean {
        while (true) {
            when (view.evaluate(MermaidPage.READY_PROBE)?.let(::decodeString)) {
                "ready" -> return true
                "failed" -> {
                    unavailable = true
                    destroyEngine()
                    return false
                }
            }
            delay(POLL_MS)
            if (webView !== view) return false
        }
    }

    /** The page's answer for render [id]; `null` if the page died under it. */
    private suspend fun awaitResult(view: WebView, id: Int): JSONObject? {
        while (true) {
            delay(POLL_MS)
            if (webView !== view) return null
            view.evaluate(MermaidPage.pollScript(id))?.let(::decodeObject)?.let { return it }
        }
    }

    @Suppress("TooGenericExceptionCaught") // no usable WebView on this device: no pictures, no crash
    private fun engine(): WebView? {
        webView?.let { return it }
        if (unavailable) return null
        return try {
            WebView(context).also { view ->
                view.lockDown()
                view.webViewClient = MermaidWebViewClient(context.assets) { gone ->
                    // Never take the app down with the page: forget it, the next render rebuilds.
                    if (webView === gone) webView = null
                    processGone?.complete(Unit)
                    gone.destroy()
                }
                view.loadUrl(MermaidPage.PAGE_URL)
                webView = view
            }
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    /**
     * Forgets the WebView. With [terminate], also kills its renderer process where Android allows it
     * (API 29+): destroy() is not guaranteed to stop a script stuck in a loop, and every WebView in the app
     * shares that one renderer, so the next diagram would hang behind it. A killed renderer reports
     * itself gone, and [MermaidWebViewClient] destroys the view then.
     */
    private fun destroyEngine(terminate: Boolean = false) {
        val view = webView ?: return
        webView = null
        val killed = terminate &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            view.webViewRenderProcess?.terminate() == true
        if (killed) {
            processGone = CompletableDeferred()
            Log.i(TIMING_TAG, "renderer terminated")
        } else {
            view.destroy()
        }
    }
}

private const val RENDER_TIMEOUT_MS = 8_000L
private const val TIMING_TAG = "MermaidTiming"
private const val POLL_MS = 50L
private const val GONE_WAIT_MS = 1_000L

/**
 * Main thread only. Set when a render killed the renderer process, completed when WebView reports it
 * gone. A WebView built in between can attach to the dying process and never load, so the next
 * render, by any instance, waits for it: every WebView in the app shares that one process.
 */
private var processGone: CompletableDeferred<Unit>? = null
private const val MIN_WIDTH_PX = 320
private const val MAX_WIDTH_PX = 1600

// A failure entry is a key and a flag: cheap enough to remember every broken diagram of a long note.
private const val MAX_FAILURES = 256

private const val MAX_CACHE_BYTES = 24L * 1024 * 1024
private const val MEMORY_FRACTION = 16
private const val MAX_ENTRY_FRACTION = 4

/** The compiler page's settings: script on, everything else it does not need off. */
@SuppressLint("SetJavaScriptEnabled") // the page's whole purpose
@Suppress("DEPRECATION") // the file-URL and database switches are deprecated but still honoured
private fun WebView.lockDown() {
    settings.apply {
        javaScriptEnabled = true
        blockNetworkLoads = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
    }
}

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
}

/** The picture cache's budget in bytes: a sixteenth of the heap, at most 24 MB. */
private fun cacheBytes(): Int = minOf(Runtime.getRuntime().maxMemory() / MEMORY_FRACTION, MAX_CACHE_BYTES).toInt()

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** `evaluateJavascript` hands back the script's value JSON-encoded; this unwraps a string. */
private fun decodeString(json: String): String? = try {
    JSONTokener(json).nextValue() as? String
} catch (_: JSONException) {
    null
}

/** The poll's answer, an object once the render has settled, `null` while it is still running. */
private fun decodeObject(json: String): JSONObject? = decodeString(json)?.let {
    try {
        JSONTokener(it).nextValue() as? JSONObject
    } catch (_: JSONException) {
        null
    }
}

/** The page's PNG as a bitmap; `null` if it is not one, or if there is no memory for it (Safeguard 3). */
private fun decodePng(base64: String): Bitmap? {
    if (base64.isEmpty()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}
