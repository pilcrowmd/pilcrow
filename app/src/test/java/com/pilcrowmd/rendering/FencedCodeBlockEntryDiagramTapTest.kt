// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRequest
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.commonmark.node.FencedCodeBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * S8-A7: a drawn Mermaid diagram opens the full-screen viewer when tapped, and nothing else does.
 *
 * The renderer SUSPENDS until the test answers it, so "pending" is provably pending and every
 * "drawn" assertion runs after the answer has gone through the main dispatcher. A broken diagram
 * (shown as its source), a pending one and a holder rebound to a code block must carry no click
 * listener, no clickable flag and no "open full screen" label: TalkBack would otherwise offer to open
 * a picture that is not there.
 */
@RunWith(RobolectricTestRunner::class)
class FencedCodeBlockEntryDiagramTapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Answers when the test says so; remembers the width it was asked for. */
    private class GatedRenderer : MermaidDiagramRenderer {
        var askedWidth = -1
        private val answer = CompletableDeferred<Bitmap?>()

        override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? {
            askedWidth = widthPx
            return answer.await()
        }

        fun answer(bitmap: Bitmap?) = answer.complete(bitmap)
    }

    private val renderer = GatedRenderer()
    private val opened = mutableListOf<MermaidDiagramRequest>()
    private val entry = FencedCodeBlockEntry(
        context,
        mermaid = MermaidDiagrams(renderer, scope, onOpen = { opened += it }),
    )

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun holder() = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))

    private fun markwon(): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.render(any()) } answers { android.text.SpannableString(firstArg<FencedCodeBlock>().literal) }
        every { m.setParsedMarkdown(any(), any()) } answers { firstArg<TextView>().text = secondArg() }
    }

    private val diagram = FencedCodeBlock().apply {
        info = "mermaid"
        literal = "flowchart TD\n  A --> B\n"
    }
    private val code = FencedCodeBlock().apply {
        info = "python"
        literal = "return 42\n"
    }

    private fun clickLabel(holder: FencedCodeBlockEntry.Holder): CharSequence? =
        AccessibilityNodeInfoCompat.wrap(holder.mermaidImage.createAccessibilityNodeInfo())
            .actionList
            .firstOrNull { it.id == AccessibilityActionCompat.ACTION_CLICK.id }
            ?.label

    private fun assertNotTappable(holder: FencedCodeBlockEntry.Holder, what: String) {
        assertFalse("$what: not clickable", holder.mermaidImage.isClickable)
        assertFalse("$what: no click listener", holder.mermaidImage.hasOnClickListeners())
        assertTrue("$what: no 'open full screen' label", clickLabel(holder) != "open full screen")
    }

    @Test
    fun `a drawn diagram opens the viewer when tapped`() {
        val holder = holder()
        entry.bindMermaid(markwon(), holder, diagram)
        renderer.answer(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        idle()

        assertTrue("clickable once drawn", holder.mermaidImage.isClickable)
        holder.mermaidImage.performClick()

        assertEquals(listOf(MermaidDiagramRequest(diagram.literal, renderer.askedWidth)), opened)
        assertEquals("TalkBack says 'double-tap to open full screen'", "open full screen", clickLabel(holder))
    }

    @Test
    fun `a broken diagram is not tappable`() {
        val holder = holder()
        entry.bindMermaid(markwon(), holder, diagram)
        renderer.answer(null)
        idle()

        assertNotTappable(holder, "broken")
        holder.mermaidImage.performClick()
        assertTrue("a tap opens nothing", opened.isEmpty())
    }

    @Test
    fun `a pending diagram is not tappable`() {
        val holder = holder()
        entry.bindMermaid(markwon(), holder, diagram)
        idle()
        assertEquals("precondition: the render started and waits", true, renderer.askedWidth > 0)

        assertNotTappable(holder, "pending")
    }

    @Test
    fun `a holder rebound from a drawn diagram to a code block is not tappable`() {
        val holder = holder()
        entry.bindMermaid(markwon(), holder, diagram)
        renderer.answer(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        idle()
        assertTrue("precondition: the drawn diagram is tappable", holder.mermaidImage.isClickable)

        // The router's order: reset, then the next lane binds.
        holder.resetMermaid()
        entry.bindHolder(markwon(), holder, code)
        idle()

        assertNotTappable(holder, "rebound")
    }
}
