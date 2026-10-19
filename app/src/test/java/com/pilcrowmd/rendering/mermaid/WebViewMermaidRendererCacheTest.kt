// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-70: a cached diagram is answered without suspending, so the reader's UNDISPATCHED bind lands it in
 * the bind itself and never shows the empty panel for a frame first.
 *
 * The render is started UNDISPATCHED on a test dispatcher that is never advanced: any suspension on the
 * way to the cache (a hop to another dispatcher) would resume on that dispatcher, and so would never
 * complete here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WebViewMermaidRendererCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val style = MermaidStyle(dark = true, background = "#000000", text = "#ffffff", line = "#888888")
    private val source = "flowchart TD\n  A --> B\n"

    @Test
    fun `a cached diagram is answered without suspending`() {
        val renderer = WebViewMermaidRenderer(context)
        val picture = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        renderer.cachePictureForTest(source, 400, style, picture)
        val scope = CoroutineScope(StandardTestDispatcher())
        var answer: Bitmap? = null

        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { answer = renderer.render(source, 400, style) }

        try {
            assertTrue("the render finished inside the call, with no suspension", job.isCompleted)
            assertSame(picture, answer)
        } finally {
            scope.cancel()
        }
    }
}
