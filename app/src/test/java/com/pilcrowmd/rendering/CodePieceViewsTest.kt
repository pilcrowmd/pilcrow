// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.ReaderDocument
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #17: the pieces of one long code block pan together and are as wide as the widest of them, so
 * their lines stay lined up and a finger can pan any piece as far as the longest line anywhere in the
 * block. What a reader sees is asserted: the pan of one piece shown moves every other piece shown, a piece
 * bound later stands where the block stands, and wrapped lines (nothing to pan) are left alone.
 *
 * NATIVE graphics: the legacy mode fakes text metrics, so every line would be as wide as it is long in
 * chars and a 300 char line would fit a 400 px view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodePieceViewsTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    // The holders sit in a window, as they do in the reader: the shared width is posted to a view, and a
    // view that is not attached to a window runs nothing it posts.
    private lateinit var container: FrameLayout

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        container = FrameLayout(activity)
        activity.setContentView(container)
    }

    /** 100 lines of 300 chars: about 27 lines (8,000 chars) to a piece, so four pieces. */
    private fun wideBlock(widest: Int = 300, widestAtLine: Int = -1): String = (1..100).joinToString("") { n ->
        "w".repeat(if (n == widestAtLine) widest else 300) + "\n"
    }

    private class Built(val adapter: MarkwonAdapter, val firstPiece: Int, val pieceCount: Int)

    private fun build(code: String, wrap: Boolean = false): Built {
        val document = ReaderDocument.transform(markwon.parse("```text\n$code```\n"))
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, wrapCodeLines = wrap)
        adapter.setParsedMarkdown(markwon, document)
        return Built(adapter, blocks.indexOfFirst { it is FencedCodeBlock }, blocks.count { it is FencedCodeBlock })
    }

    private fun Built.bind(piece: Int): FencedCodeBlockEntry.Holder {
        assertTrue("the block has a piece $piece: it has $pieceCount", piece < pieceCount)
        val position = firstPiece + piece
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(position))
        container.addView(holder.itemView)
        adapter.onBindViewHolder(holder, position)
        return holder as FencedCodeBlockEntry.Holder
    }

    private fun layOut(holder: FencedCodeBlockEntry.Holder) {
        holder.itemView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        holder.itemView.layout(0, 0, holder.itemView.measuredWidth, holder.itemView.measuredHeight)
    }

    /** Runs what the pieces posted (the shared width), then lays the holders out once more with it. */
    private fun settle(vararg holders: FencedCodeBlockEntry.Holder) {
        shadowOf(Looper.getMainLooper()).idle()
        holders.forEach { layOut(it) }
    }

    @Test
    fun `panning one piece pans the other pieces shown`() {
        val built = build(wideBlock())
        val first = built.bind(0)
        val second = built.bind(1)
        layOut(first)
        layOut(second)
        assertTrue(
            "the lines are wider than the view, or there is nothing to pan: ${first.codeView.width}",
            first.codeView.width > WIDTH_PX + PAN_PX,
        )

        first.codeScroll.scrollTo(PAN_PX, 0)

        assertEquals("the piece panned", PAN_PX, first.codeScroll.scrollX)
        assertEquals("the other piece followed", PAN_PX, second.codeScroll.scrollX)

        second.codeScroll.scrollTo(PAN_PX / 2, 0)

        assertEquals("and the other way round", PAN_PX / 2, first.codeScroll.scrollX)
    }

    @Test
    fun `a piece bound after the pan stands where the block stands, once it is laid out`() {
        val built = build(wideBlock())
        val first = built.bind(0)
        val second = built.bind(1)
        layOut(first)
        layOut(second)
        first.codeScroll.scrollTo(PAN_PX, 0)

        val third = built.bind(2)
        layOut(third)

        assertEquals(PAN_PX, third.codeScroll.scrollX)
        assertEquals("the pieces already shown did not move", PAN_PX, first.codeScroll.scrollX)
    }

    @Test
    fun `a piece scrolled out of sight is left out of the pan`() {
        val built = build(wideBlock())
        val first = built.bind(0)
        val second = built.bind(1)
        layOut(first)
        layOut(second)
        // The recycler reuses a holder for another block: it leaves the block's pan.
        first.resetPiece()

        second.codeScroll.scrollTo(PAN_PX, 0)

        assertEquals(0, first.codeScroll.scrollX)
    }

    @Test
    fun `every piece takes the width of the widest piece, so each can pan as far as the longest line`() {
        // The 500 char line is in the second piece (lines 28 to 54).
        val built = build(wideBlock(widest = 500, widestAtLine = 30))
        val first = built.bind(0)
        val second = built.bind(1)
        layOut(first)
        layOut(second)
        assertTrue(
            "the fixture: the first piece alone is narrower (${first.codeView.width} vs ${second.codeView.width})",
            first.codeView.width < second.codeView.width,
        )

        settle(first, second)

        assertEquals("the narrower piece is as wide as the widest", second.codeView.width, first.codeView.width)
        first.codeScroll.scrollTo(second.codeView.width, 0)
        assertEquals(
            "it pans as far as the widest piece does",
            second.codeView.width - first.codeScroll.width,
            first.codeScroll.scrollX,
        )
    }

    @Test
    fun `with wrapped lines no piece is widened and none pans`() {
        val built = build(wideBlock(widest = 500, widestAtLine = 30), wrap = true)
        val first = built.bind(0)
        val second = built.bind(1)
        layOut(first)
        layOut(second)
        settle(first, second)

        assertEquals(0, first.codeView.minWidth)
        assertEquals(0, second.codeView.minWidth)
        assertTrue("wrapped to the view: ${first.codeView.width}", first.codeView.width <= WIDTH_PX)

        first.codeScroll.scrollTo(PAN_PX, 0)

        assertEquals("nothing to pan", 0, first.codeScroll.scrollX)
        assertEquals(0, second.codeScroll.scrollX)
    }

    @Test
    fun `a holder given back its ordinary block loses the piece's margins, padding and width`() {
        val built = build(wideBlock())
        val middle = built.bind(1)
        layOut(middle)
        settle(middle)
        val params = middle.itemView.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals("a middle piece touches its neighbours", 0, params.topMargin)
        assertEquals(0, params.bottomMargin)
        assertEquals(0, middle.codeView.paddingTop)
        assertTrue("and was widened", middle.codeView.minWidth > 0)

        middle.resetPiece()

        assertTrue("the layout's margin is back", params.topMargin > 0 && params.bottomMargin > 0)
        assertTrue("the layout's padding is back", middle.codeView.paddingTop > 0 && middle.codeView.paddingBottom > 0)
        assertEquals(0, middle.codeView.minWidth)
        assertTrue(middle.codeView.includeFontPadding)
    }

    private companion object {
        const val WIDTH_PX = 400
        const val PAN_PX = 200
    }
}
