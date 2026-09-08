// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-06: a footnote definition is ALWAYS the last block of a document, and
 * `scrollToPositionWithOffset(target, 0)` asks for it at the TOP of the viewport. RecyclerView
 * cannot scroll past the end of its content, so that request is CLAMPED and the block lands at the
 * BOTTOM instead — about a screen short of where the user was sent.
 *
 * These tests pin the geometry directly rather than the rendered document, because the clamp is a
 * property of the list, not of Markdown. `blockHeight` is deliberately much smaller than
 * `viewportHeight` so the shortfall is unambiguous: without the fix the target cannot be topped at
 * all, with it the target sits exactly at the top.
 */
@RunWith(RobolectricTestRunner::class)
class FootnoteJumpClampTest {

    private val viewportHeight = 900
    private val blockHeight = 100
    private val blockCount = 30

    /** A list of fixed-height blocks, laid out in a fixed viewport — the reader's shape, minimal. */
    private fun listOfBlocks(
        applyBottomSpacer: Boolean,
        blocks: Int = blockCount,
        blockPx: Int = blockHeight,
        verticalMarginPx: Int = 0,
    ): RecyclerView {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recyclerView = RecyclerView(context)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = blocks
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val text = TextView(parent.context)
                text.layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, blockPx).apply {
                    topMargin = verticalMarginPx
                    bottomMargin = verticalMarginPx
                }
                return object : RecyclerView.ViewHolder(text) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = "block $position"
            }
        }
        if (applyBottomSpacer) applyReaderBottomSpacer(recyclerView)
        layOut(recyclerView)
        return recyclerView
    }

    private fun layOut(recyclerView: RecyclerView) {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
        )
        recyclerView.layout(0, 0, 600, viewportHeight)
    }

    private fun jumpToLast(recyclerView: RecyclerView) {
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        layoutManager.scrollToPositionWithOffset(blockCount - 1, 0)
        layOut(recyclerView)
    }

    @Test
    fun `WITHOUT the spacer the last block cannot be topped — this is the M-06 bug`() {
        val recyclerView = listOfBlocks(applyBottomSpacer = false)
        jumpToLast(recyclerView)
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        assertNotEquals(
            "Guard: if the last block CAN be topped without the spacer, the M-06 diagnosis is " +
                "wrong and the fix below is not what makes the test pass.",
            blockCount - 1,
            layoutManager.findFirstVisibleItemPosition(),
        )
        assertEquals(
            "The clamp lands the target at the BOTTOM of the viewport, which is the reported symptom.",
            blockCount - 1,
            layoutManager.findLastVisibleItemPosition(),
        )
    }

    @Test
    fun `WITH the spacer the last block reaches the top of the viewport`() {
        val recyclerView = listOfBlocks(applyBottomSpacer = true)
        jumpToLast(recyclerView)
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        assertEquals(
            "A footnote definition is always the last block; topping it is the whole of M-06.",
            blockCount - 1,
            layoutManager.findFirstVisibleItemPosition(),
        )
        assertEquals(
            "and it must sit AT the top edge, not merely be the first visible item.",
            0,
            recyclerView.getChildAt(0).top,
        )
    }

    /**
     * THE COUPLING M-06's RULING DOES NOT MENTION, pinned so the fix cannot silently break it.
     *
     * Jump-to-bottom relies on the SAME clamp M-06 removes: `scrollToPosition(last)` asks
     * LinearLayoutManager to put the last block at the TOP, and today only the clamp turns that
     * into "the end of the document at the bottom of the screen". Once the spacer makes the top
     * reachable, the unchanged call would land the last block at the top with dead space below —
     * a visible regression in an interaction M-06 never set out to touch. So the end of the
     * document must still come to rest AT the bottom edge.
     */
    @Test
    fun `jump-to-bottom still rests the document's end at the bottom edge, spacer or not`() {
        val recyclerView = listOfBlocks(applyBottomSpacer = true)
        scrollToDocumentEnd(recyclerView)
        // `scrollToPosition` defers to the next layout pass, and the closing alignment waits on
        // that same pass — so drive one, the way a real frame would.
        layOut(recyclerView)
        val lastChild = (recyclerView.layoutManager as LinearLayoutManager)
            .findViewByPosition(blockCount - 1)
        assertEquals(
            "The last block's bottom must sit on the viewport's bottom edge — the spacer is " +
                "headroom for the jump, never dead space the reader has to scroll through.",
            viewportHeight,
            lastChild?.bottom,
        )
    }

    /**
     * THE REGRESSION THE GOLDENS CAUGHT, pinned here so it cannot come back silently.
     *
     * The spacer inflates the scroll range by a viewport, so `range > height` — the test the
     * floating jump controls used — became true for EVERY document, and the controls started
     * appearing on short ones that already fit. Six `link_and_image` goldens failed on it.
     */
    @Test
    fun `a document that fits the viewport does NOT report as overflowing, spacer notwithstanding`() {
        val shortDocument = listOfBlocks(applyBottomSpacer = true, blocks = 3) // 300px in a 900px viewport
        assertFalse(
            "The jump controls hide on a document that fits; the spacer is headroom, not content.",
            documentOverflowsViewport(shortDocument),
        )
    }

    @Test
    fun `a document taller than the viewport still reports as overflowing`() {
        val longDocument = listOfBlocks(applyBottomSpacer = true) // 30 blocks = 3000px
        assertTrue(
            "Guard: if this were false the check above would pass by always saying no.",
            documentOverflowsViewport(longDocument),
        )
    }

    /**
     * REVIEW FINDING (round 1), reproduced before it was fixed.
     *
     * `documentOverflowsViewport` first asked `computeVerticalScrollRange() - viewport > viewport`.
     * That assumed the spacer is always inside the range, and it is not: LinearLayoutManager
     * ESTIMATES the range from the children it has laid out, so at the top of a document the final
     * block — the only one carrying the spacer — contributes nothing. Subtracting a viewport
     * unconditionally then under-reports by a full screen, and the jump controls vanish on a
     * document between one and two screens tall. The original tests missed it because 300 px and
     * 3000 px are both far from that boundary.
     */
    @Test
    fun `a document just over one viewport still reports as overflowing`() {
        val justOver = listOfBlocks(applyBottomSpacer = true, blocks = 11) // 1100px in a 900px viewport
        assertTrue(
            "A document the reader must scroll to finish MUST offer the jump controls.",
            documentOverflowsViewport(justOver),
        )
    }

    /**
     * REVIEW FINDING (round 1): with an empty list `itemCount - 1` is -1, and
     * `getChildAdapterPosition` returns NO_POSITION — also -1 — for a view animating out. The two
     * compared equal, so a departing view was handed a full viewport of offset mid-animation.
     */
    @Test
    fun `an empty list gives nothing a spacer`() {
        val empty = listOfBlocks(applyBottomSpacer = true, blocks = 0)
        assertEquals(
            "Guard: NO_POSITION must never be mistaken for 'the last item'.",
            0,
            empty.computeVerticalScrollRange(),
        )
    }

    /**
     * REVIEW FINDING (round 2), reproduced before it was fixed.
     *
     * `lastBlock.bottom > height` reads a coordinate relative to the viewport, and it SHRINKS as
     * the reader scrolls down — hitting exactly `height` at the end of the document, where the test
     * then reported "does not overflow" and the jump controls vanished. The document did not
     * change size; only the scroll offset did. **Any formulation that reads a scroll-dependent
     * coordinate has this bug.**
     */
    @Test
    fun `a long document still overflows when scrolled to its end`() {
        val document = listOfBlocks(applyBottomSpacer = true)
        scrollToDocumentEnd(document)
        layOut(document)
        assertTrue(
            "The controls must not disappear the moment the reader reaches the bottom.",
            documentOverflowsViewport(document),
        )
    }

    /**
     * REVIEW FINDING (round 2): the single tall block is the worst case for the same bug, because
     * `findFirstVisibleItemPosition()` stays 0 throughout, so the early "it plainly scrolls" exit
     * never fires and the shrinking `bottom` is the only thing left deciding.
     */
    @Test
    fun `one block taller than the viewport overflows, at the top and at the bottom`() {
        val tall = listOfBlocks(applyBottomSpacer = true, blocks = 1, blockPx = viewportHeight * 2)
        assertTrue("at the top", documentOverflowsViewport(tall))
        tall.scrollBy(0, viewportHeight)
        layOut(tall)
        assertTrue("and still, scrolled into it", documentOverflowsViewport(tall))
    }

    /**
     * REVIEW FINDING (round 3), the half of it that was TRUE, reproduced before it was fixed.
     *
     * `last.bottom - first.top` reads the views' own edges and so drops item MARGINS —
     * `adapter_code_block.xml` and `adapter_latex_block.xml` carry 4 dp top and bottom on the item
     * root. A document that overflows by less than its margins was therefore reported as fitting,
     * and the jump controls went missing on it. The other half of that finding — that decorations
     * are dropped too — is not a defect but the requirement: the only decoration here is the
     * spacer, and counting it reports every document as overflowing.
     */
    @Test
    fun `item margins count toward the document height`() {
        // Two blocks of 430 px = 860 px of content in a 900 px viewport: WITHOUT margins it fits.
        // With 20 px above and below each, it occupies 940 px and does not. Both ends stay laid out
        // (the last is merely clipped), so neither the count guard nor the "an end is missing"
        // guard can answer this — the margins are the only thing that decides, which is what makes
        // it a real test rather than one that passes on an earlier branch.
        val overflowingOnlyByItsMargins = listOfBlocks(
            applyBottomSpacer = true,
            blocks = 2,
            blockPx = 430,
            verticalMarginPx = 20,
        )
        val layoutManager = overflowingOnlyByItsMargins.layoutManager as LinearLayoutManager
        assertNotNull("guard: the first block must be laid out", layoutManager.findViewByPosition(0))
        assertNotNull("guard: the last block must be laid out too", layoutManager.findViewByPosition(1))
        assertTrue(
            "A document that overflows only by its margins still overflows — the reader must " +
                "still be offered the jump controls.",
            documentOverflowsViewport(overflowingOnlyByItsMargins),
        )
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
