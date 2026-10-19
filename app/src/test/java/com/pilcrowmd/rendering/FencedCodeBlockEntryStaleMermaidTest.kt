// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.commonmark.node.FencedCodeBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-70: the reader's Mermaid lane, against a renderer that really SUSPENDS until the test answers it,
 * so every assertion about a result runs after that result has gone through the main dispatcher, and
 * every "pending" assertion runs while the render is provably still waiting (a test that cannot fail is not a test).
 *
 * Code, YAML and Mermaid blocks share one recycled holder, so a render that outlives its bind must
 * never draw over the block the holder shows next (M-222's class). Two things stop it: the router's one
 * reset cancels the render on every rebind (M-256), and the holder's key refuses whatever still lands.
 * Each case is built so that only the guard it names can decide it: the stale-key cases bind through the
 * entry directly, where no router reset runs, so the key check is the only thing left to refuse the
 * answer; the router cases bind through the reader's adapter.
 *
 * Seen failing without each fix: with the key check removed, both stale-key cases applied the old
 * answer; with `resetMermaid()` removed from the router, the front-matter card kept the caption (M-120)
 * and the pending render was never cancelled (M-256).
 */
@RunWith(RobolectricTestRunner::class)
class FencedCodeBlockEntryStaleMermaidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Each source waits on its own answer from the test; records starts and cancellations. */
    private class GatedRenderer : MermaidDiagramRenderer {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        private val answers = mutableMapOf<String, CompletableDeferred<Bitmap?>>()

        override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? {
            started += source
            try {
                return answers.getOrPut(source) { CompletableDeferred() }.await()
            } catch (e: CancellationException) {
                cancelled += source
                throw e
            }
        }

        fun answer(source: String, bitmap: Bitmap?) = answers.getValue(source).complete(bitmap)
    }

    private val renderer = GatedRenderer()

    @After
    fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun shownBitmap(holder: FencedCodeBlockEntry.Holder): Bitmap? =
        (holder.mermaidImage.drawable as? BitmapDrawable)?.bitmap

    /** A Markwon that renders the node's literal as plain text. */
    private fun literalMarkwon(): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.render(any()) } answers { android.text.SpannableString(firstArg<FencedCodeBlock>().literal) }
        every { m.setParsedMarkdown(any(), any()) } answers { firstArg<TextView>().text = secondArg() }
    }

    private fun mermaidNode(literal: String) = FencedCodeBlock().apply {
        info = "mermaid"
        this.literal = literal
    }

    private val first = mermaidNode("flowchart TD\n  A --> B\n")
    private val second = mermaidNode("flowchart TD\n  C --> D\n")

    private val entry = FencedCodeBlockEntry(context, mermaid = MermaidDiagrams(renderer, scope))

    private fun entryHolder() = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))

    /** Binds [first], and checks the render is really waiting with the empty panel showing. */
    private fun bindPending(holder: FencedCodeBlockEntry.Holder, node: FencedCodeBlock = first) {
        entry.bindMermaid(literalMarkwon(), holder, node)
        idle()
        assertEquals("precondition: the render started", node.literal, renderer.started.last())
        assertEquals("pending: the panel shows", View.VISIBLE, holder.mermaidImage.visibility)
        assertNull("pending: the panel is empty", holder.mermaidImage.drawable)
        assertEquals("pending: no code", View.GONE, holder.codeScroll.visibility)
        assertEquals("pending: no caption", View.GONE, holder.mermaidCaption.visibility)
    }

    // ---- M-228: a broken diagram's source is not a search match ----

    /**
     * Search does not count text inside a Mermaid fence (the diagram is a picture), so the source a
     * broken diagram falls back to must not paint matches either, or the screen would show matches the
     * counter skips. The ordinary fence is the control: the same query is painted there.
     */
    @Test
    fun `a broken diagram's source paints no search matches, an ordinary fence does`() {
        val search =
            SearchHighlight(query = "flowchart", otherColor = 0xFF00FF00.toInt(), focusedColor = 0xFF00FF00.toInt())
        val searching =
            FencedCodeBlockEntry(context, searchHighlight = search, mermaid = MermaidDiagrams(renderer, scope))
        val holder = searching.createHolder(LayoutInflater.from(context), FrameLayout(context))

        searching.bindMermaid(literalMarkwon(), holder, first)
        idle()
        renderer.answer(first.literal, null)
        idle()
        assertEquals("precondition: the source shows", View.VISIBLE, holder.codeScroll.visibility)
        assertEquals("the fallback paints no match", 0, highlights(holder.codeView))

        val plain = FencedCodeBlock().apply {
            info = "text"
            literal = first.literal
        }
        searching.bindHolder(literalMarkwon(), holder, plain)
        assertTrue("control: an ordinary fence paints the match", highlights(holder.codeView) > 0)
    }

    private fun highlights(view: TextView): Int = (view.text as? android.text.Spanned)
        ?.getSpans(0, view.text.length, android.text.style.BackgroundColorSpan::class.java)?.size ?: 0

    // ---- (b) and (c): what an answer shows ----

    @Test
    fun `no picture shows the source as a code block with the caption`() {
        val holder = entryHolder()
        bindPending(holder)

        renderer.answer(first.literal, null)
        idle()

        assertEquals(View.GONE, holder.mermaidImage.visibility)
        assertEquals(View.VISIBLE, holder.codeScroll.visibility)
        assertEquals(View.VISIBLE, holder.copyButton.visibility)
        assertEquals("Safeguard 3: an undrawn diagram shows its source", first.literal, holder.codeView.text.toString())
        assertEquals(View.VISIBLE, holder.mermaidCaption.visibility)
        assertEquals("Diagram could not be drawn. Showing its source.", holder.mermaidCaption.text.toString())
        assertEquals(DarkColorScheme.secondaryText.toArgb(), holder.mermaidCaption.currentTextColor)
    }

    @Test
    fun `a picture is shown in place of the code, with no caption`() {
        val holder = entryHolder()
        bindPending(holder)
        val picture = bitmap()

        renderer.answer(first.literal, picture)
        idle()

        assertSame(picture, shownBitmap(holder))
        assertEquals(View.VISIBLE, holder.mermaidImage.visibility)
        assertEquals(View.GONE, holder.codeScroll.visibility)
        assertEquals(View.GONE, holder.copyButton.visibility)
        assertEquals(View.GONE, holder.mermaidCaption.visibility)
    }

    // ---- (a): the key refuses a stale answer ----

    @Test
    fun `a picture for the diagram the holder showed before is not drawn`() {
        val holder = entryHolder()
        bindPending(holder, first)
        bindPending(holder, second)

        renderer.answer(first.literal, bitmap())
        idle()
        assertNull("the old diagram's picture must not land on the new one", holder.mermaidImage.drawable)

        val picture = bitmap()
        renderer.answer(second.literal, picture)
        idle()
        assertSame("control: the current diagram's own picture lands", picture, shownBitmap(holder))
    }

    @Test
    fun `no picture for the diagram the holder showed before does not show its source`() {
        val holder = entryHolder()
        bindPending(holder, first)
        bindPending(holder, second)

        renderer.answer(first.literal, null)
        idle()

        assertEquals("the old diagram's fallback must not show", View.GONE, holder.codeScroll.visibility)
        assertEquals(View.GONE, holder.mermaidCaption.visibility)
        assertEquals(View.VISIBLE, holder.mermaidImage.visibility)
    }

    // ---- (d) and (e): the router's one reset ----

    // Front matter must open the document; the diagram and a code block follow it.
    private val markdown = listOf(
        "---\ntitle: Stress test\n---",
        "```mermaid\nflowchart TD\n  A --> B{{Broken\n```",
        "```python\ndef f():\n    return 42\n```",
    ).joinToString("\n\n")

    private fun readerAdapter(): MarkwonAdapter {
        val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
        return RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            mermaid = MermaidDiagrams(renderer, scope),
        ).also { it.setMarkdown(markwon, markdown) }
    }

    private fun MarkwonAdapter.holder(): FencedCodeBlockEntry.Holder =
        onCreateViewHolder(FrameLayout(context), getItemViewType(MERMAID)) as FencedCodeBlockEntry.Holder

    @Test
    fun `M-120 - front matter bound after a Mermaid fallback has no caption`() {
        val adapter = readerAdapter()
        val holder = adapter.holder()
        adapter.onBindViewHolder(holder, MERMAID)
        idle()
        renderer.answer(renderer.started.single(), null)
        idle()
        assertEquals("precondition: the fallback caption shows", View.VISIBLE, holder.mermaidCaption.visibility)

        adapter.onBindViewHolder(holder, FRONT_MATTER)
        idle()

        assertTrue("precondition: the card shows", holder.codeView.text.contains("Stress test"))
        assertEquals("the front-matter card must not keep the caption", View.GONE, holder.mermaidCaption.visibility)
    }

    @Test
    fun `M-256 - a pending render is cancelled when the holder is rebound to a code block`() {
        val adapter = readerAdapter()
        val holder = adapter.holder()
        adapter.onBindViewHolder(holder, MERMAID)
        idle()
        val source = renderer.started.single()

        adapter.onBindViewHolder(holder, CODE)
        idle()

        assertEquals("the old render is cancelled", listOf(source), renderer.cancelled)
        renderer.answer(source, null)
        idle()
        assertTrue("the code block keeps its text", holder.codeView.text.contains("return 42"))
        assertEquals(View.GONE, holder.mermaidImage.visibility)
        assertEquals(View.GONE, holder.mermaidCaption.visibility)
    }

    private companion object {
        const val FRONT_MATTER = 0
        const val MERMAID = 1
        const val CODE = 2
    }
}
