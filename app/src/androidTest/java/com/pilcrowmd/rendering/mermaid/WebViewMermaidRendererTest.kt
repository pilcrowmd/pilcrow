// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M-70 on a real WebView: the bundled Mermaid draws each diagram type offline, a broken diagram
 * comes back as "no picture" rather than an exception, and repeats are served from the cache.
 * Timings are logged under the `MermaidTiming` tag (`adb logcat -s MermaidTiming`).
 */
@RunWith(AndroidJUnit4::class)
class WebViewMermaidRendererTest {

    private lateinit var renderer: WebViewMermaidRenderer
    private val dark = MermaidStyle.from(DarkColorScheme)
    private val light = MermaidStyle.from(LightColorScheme)

    @Before
    fun setUp() {
        renderer = WebViewMermaidRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun render(source: String, width: Int = WIDTH, style: MermaidStyle = dark): Bitmap? =
        runBlocking { renderer.render(source, width, style) }

    private fun assertDrawn(name: String, source: String, style: MermaidStyle = dark): Bitmap {
        val bitmap = requireNotNull(render(source, style = style)) { "$name diagram was not drawn" }
        assertEquals("$name width", WIDTH, bitmap.width)
        assertTrue("$name height", bitmap.height > 0)
        return bitmap
    }

    @Test
    fun flowchartIsDrawnAtTheRequestedWidth() {
        assertDrawn("flowchart", FLOWCHART)
    }

    @Test
    fun thirtyNodeFlowchartIsDrawnInTime() {
        val coldMs = timed { assertNotNull(render(chain(30, "A"))) }
        val warmMs = timed { assertNotNull(render(chain(30, "B"))) }
        Log.i("MermaidTiming", "30-node flowchart: cold $coldMs ms, warm $warmMs ms")
        assertTrue("cold render took $coldMs ms", coldMs < TIMEOUT_MS)
        assertTrue("warm render took $warmMs ms", warmMs < TIMEOUT_MS)
    }

    @Test
    fun brokenSourceIsNoPictureNotAnException() {
        assertNull(render("flowchart TD\n  A -->"))
        assertNull(render("this is not a diagram at all"))
    }

    @Test
    fun darkAndLightBothDraw() {
        assertDrawn("dark flowchart", FLOWCHART, dark)
        assertDrawn("light flowchart", FLOWCHART, light)
    }

    @Test
    fun repeatIsServedFromTheCache() {
        val first = render(FLOWCHART)
        assertNotNull(first)
        val repeatMs = timed { assertSame(first, render(FLOWCHART)) }
        Log.i("MermaidTiming", "cache hit: $repeatMs ms")
        assertTrue("cache hit took $repeatMs ms", repeatMs < CACHE_HIT_MS)
    }

    @Test
    fun pieIsDrawn() {
        assertDrawn("pie", "pie title Pets\n  \"Dogs\" : 386\n  \"Cats\" : 85\n  \"Rats\" : 15")
    }

    @Test
    fun sequenceIsDrawn() {
        assertDrawn("sequence", "sequenceDiagram\n  Alice->>Bob: Hello\n  Bob-->>Alice: Hi back")
    }

    @Test
    fun classIsDrawn() {
        assertDrawn("class", "classDiagram\n  Animal <|-- Duck\n  Animal : +int age\n  Duck : +swim()")
    }

    @Test
    fun stateIsDrawn() {
        assertDrawn("state", "stateDiagram-v2\n  [*] --> Still\n  Still --> Moving\n  Moving --> [*]")
    }

    @Test
    fun ganttIsDrawn() {
        assertDrawn(
            "gantt",
            "gantt\n  title Plan\n  dateFormat YYYY-MM-DD\n  section Build\n" +
                "  Engine :a1, 2026-01-01, 10d\n  Reader :after a1, 5d",
        )
    }

    @Test
    fun erIsDrawn() {
        assertDrawn("er", "erDiagram\n  CUSTOMER ||--o{ ORDER : places\n  ORDER ||--|{ LINE-ITEM : contains")
    }

    @Test
    fun unicodeLabelsAreDrawn() {
        assertDrawn("unicode", "flowchart LR\n  A[Zażółć gęślą jaźń] --> B[日本語] --> C[🙂]")
    }

    /**
     * The timeout path itself: a 1 ms budget always runs out, which destroys the WebView and (API 29+)
     * kills the renderer process every WebView in the app shares. A NEW renderer must still draw after
     * that, and the timed-out one must time out cleanly again on its rebuilt engine.
     */
    @Test
    fun timedOutRenderIsNoPictureAndLaterWebViewsStillDraw() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val starved = WebViewMermaidRenderer(context, timeoutMs = 1)
        var picture: Bitmap? = null
        val timedOutMs = timed { picture = runBlocking { starved.render(FLOWCHART, WIDTH, dark) } }
        Log.i("MermaidTiming", "1 ms budget: answered in $timedOutMs ms")
        assertTrue("timed-out render took $timedOutMs ms", timedOutMs < QUICK_MS)
        assertNull("a render past its budget is no picture", picture)

        val fresh = runBlocking { WebViewMermaidRenderer(context).render(FLOWCHART, WIDTH, dark) }
        assertNotNull("a new renderer draws after the old one's process was killed", fresh)

        val againMs = timed { picture = runBlocking { starved.render(chain(5, "T"), WIDTH, dark) } }
        assertTrue("second timed-out render took $againMs ms", againMs < QUICK_MS)
        assertNull("the rebuilt engine times out cleanly too", picture)

        // Straight after a kill: a WebView built before the process is reported gone would hang.
        val straightAfter = runBlocking { WebViewMermaidRenderer(context).render(FLOWCHART, WIDTH, light) }
        assertNotNull("a new renderer draws straight after a kill", straightAfter)
    }

    /** NOT the timeout path: Mermaid's own maxEdges guard (500) rejects this graph before it is laid out. */
    @Test
    fun overMaxEdgesGraphIsRejectedByMermaidQuickly() {
        val hostile = buildString {
            append("flowchart TD\n")
            for (i in 0 until HOSTILE_EDGES) append("  N${i % HOSTILE_NODES} --> N${(i * 7 + 1) % HOSTILE_NODES}\n")
        }
        var picture: Bitmap? = null
        val hostileMs = timed { picture = render(hostile) }
        Log.i("MermaidTiming", "$HOSTILE_EDGES-edge flowchart (maxEdges guard): $hostileMs ms")
        assertTrue("over-maxEdges render took $hostileMs ms", hostileMs < QUICK_MS)
        assertNull(picture)
    }

    private fun chain(nodes: Int, prefix: String): String = buildString {
        append("flowchart TD\n")
        for (i in 1 until nodes) append("  $prefix$i[Step $i] --> $prefix${i + 1}[Step ${i + 1}]\n")
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private companion object {
        const val WIDTH = 800
        const val TIMEOUT_MS = 8_000L
        const val CACHE_HIT_MS = 100L
        const val QUICK_MS = 2_000L
        const val HOSTILE_EDGES = 3000
        const val HOSTILE_NODES = 400
        const val FLOWCHART = "flowchart TD\n  A[Start] --> B{Choice}\n  B -->|yes| C[Done]\n  B -->|no| A"
    }
}
