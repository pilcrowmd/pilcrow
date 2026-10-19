// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import android.content.res.AssetManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * The compiler page's only door to the outside (security lock 3): it answers exactly two
 * URLs, [MermaidPage.PAGE_URL] with the page and [MermaidPage.SCRIPT_URL] from the bundled asset,
 * and every other request with an empty 404. Nothing reaches the network or the file system, and
 * the page can never navigate away.
 *
 * [onGone] is told when the page's renderer process dies, so the engine can be rebuilt; the app
 * itself must never go down with it.
 */
internal class MermaidWebViewClient(private val assets: AssetManager, private val onGone: (WebView) -> Unit) :
    WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        if (request.method == "GET" && request.url.toString() == MermaidPage.PAGE_URL) {
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(MermaidPage.html.toByteArray()))
        }
        if (request.method == "GET" && request.url.toString() == MermaidPage.SCRIPT_URL) {
            try {
                return WebResourceResponse("application/javascript", "utf-8", assets.open(MermaidPage.SCRIPT_ASSET))
            } catch (_: IOException) {
                // The asset is missing (a build without the fetch task): fall through to the 404,
                // the script tag's onerror fires, and every render answers "no picture".
            }
        }
        return notFound()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone(view)
        return true
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        NOT_FOUND,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val NOT_FOUND = 404
    }
}
