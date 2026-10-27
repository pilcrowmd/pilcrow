// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.CodeBlockSplitter
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.MermaidStyle
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.commonmark.node.FencedCodeBlock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * S8-A31 lead 4: a Mermaid fence past [CodeBlockSplitter.MERMAID_MAX_CHARS] is never handed to the
 * renderer (no cache key hashed over it, no WebView page for it) and is shown as source in pieces, each
 * a bounded TextView. Bound through the reader's own adapter, so the router is under test: a piece of
 * such a fence still carries the `mermaid` info string, and only the router's check for a piece sends it
 * to the code lane instead of the diagram lane.
 *
 * The fixtures are 5,000 and 5,001 lines of 10 chars: exactly the limit, and one line over it. The
 * control at the limit proves the renderer IS reached for a diagram that may be drawn, so a count of zero
 * for the long one is not just a bind that never asks.
 */
@RunWith(RobolectricTestRunner::class)
class MermaidSourceGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var markwon: Markwon

    private class CountingRenderer : MermaidDiagramRenderer {
        var calls = 0

        override suspend fun render(source: String, widthPx: Int, style: MermaidStyle): Bitmap? {
            calls++
            return null
        }
    }

    private val renderer = CountingRenderer()

    @Before
    fun setUp() {
        markwon = buildPilcrowMarkwon(context)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun diagram(lines: Int): String =
        (1..lines).joinToString("") { n -> n.toString().padStart(9, 'a').takeLast(9) + "\n" }

    /** Binds every code item the reader gets for a mermaid fence of [lines] lines. */
    private fun bindFence(lines: Int, search: SearchHighlight = SearchHighlight()): List<FencedCodeBlockEntry.Holder> {
        val document = ReaderDocument.transform(markwon.parse("```mermaid\n${diagram(lines)}```\n"))
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(
            context,
            markwon,
            searchHighlight = search,
            mermaid = MermaidDiagrams(renderer, scope),
        )
        adapter.setParsedMarkdown(markwon, document)
        val parent = FrameLayout(context)
        return blocks.indices.filter { blocks[it] is FencedCodeBlock }.map { position ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            shadowOf(Looper.getMainLooper()).idle()
            holder as FencedCodeBlockEntry.Holder
        }
    }

    @Test
    fun `a fence past the limit never reaches the renderer and shows as bounded source pieces`() {
        val holders = bindFence(5_001)

        assertEquals("the renderer was never asked", 0, renderer.calls)
        assertTrue("it is shown in pieces: ${holders.size}", holders.size > 1)
        holders.forEach { holder ->
            assertEquals("source, not a picture", View.VISIBLE, holder.codeScroll.visibility)
            assertEquals(View.GONE, holder.mermaidImage.visibility)
            assertTrue(
                "a bounded TextView: ${holder.codeView.text.length}",
                holder.codeView.text.length <= CodeBlockSplitter.LONG_BLOCK_CHARS,
            )
        }
        // A code block is padded with line ends and no-break spaces (and a piece's last line end is
        // dropped), so compare the lines' own characters.
        assertEquals(
            "none of the source is lost",
            diagram(5_001).replace("\n", ""),
            holders.joinToString("") { it.codeView.text.toString() }.replace(Regex("[\n\u00A0]"), ""),
        )
    }

    /**
     * Search counts the source of a cut fence (a piece is a code block, not a diagram), so the pieces
     * must paint it too. A piece keeps the `mermaid` info string, so the fence-language check the whole
     * Mermaid lane uses to skip painting must not apply to one.
     */
    @Test
    fun `the pieces of a fence past the limit paint search matches`() {
        val colour = 0xFF123456.toInt()
        val holders = bindFence(5_001, SearchHighlight(query = "aaaaa", otherColor = colour, focusedColor = colour))

        assertTrue("fixture: it is shown in pieces: ${holders.size}", holders.size > 1)
        holders.forEachIndexed { index, holder ->
            val text = holder.codeView.text as Spanned
            assertTrue(
                "piece $index has no search highlight",
                text.getSpans(0, text.length, BackgroundColorSpan::class.java).any { it.backgroundColor == colour },
            )
        }
    }

    @Test
    fun `a fence at the limit is still drawn, so the renderer is asked once`() {
        val holders = bindFence(5_000)

        assertEquals(1, holders.size)
        assertEquals("control: the renderer was asked", 1, renderer.calls)
    }

    /** The view layer's own gate, for a node nothing has cut (the PDF does not cut, but it binds via bindDiagram). */
    @Test
    fun `bindMermaid refuses a source past the limit even when nothing cut it`() {
        val entry = FencedCodeBlockEntry(context, mermaid = MermaidDiagrams(renderer, scope))
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        val node = FencedCodeBlock().apply {
            info = "mermaid"
            literal = diagram(5_001)
        }
        assertTrue("fixture: not a piece", node !is CodeBlockPiece)

        entry.bindMermaid(markwon, holder, node)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, renderer.calls)
        assertEquals(View.VISIBLE, holder.codeScroll.visibility)
        assertEquals(View.VISIBLE, holder.mermaidCaption.visibility)
    }
}
