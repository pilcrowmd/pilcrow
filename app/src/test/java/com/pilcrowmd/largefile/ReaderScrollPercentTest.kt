// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.largefile

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.rendering.DetailsState
import com.pilcrowmd.ui.components.JUMP_CONTROLS_LINGER_MS
import com.pilcrowmd.ui.components.SCROLL_PERCENT_TAG
import com.pilcrowmd.ui.components.ScrollPercentValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scroll percent badge in the real reader: it appears while the list is being scrolled, only on a
 * document more than 6 screens tall, and TalkBack has nothing to read in it. The number is read from
 * the badge's own semantics (a custom property that accessibility services never see), so the test
 * checks what the screen shows; the arithmetic behind it is `ScrollPercentTest`.
 *
 * The finger is held down on the list (a drag with no release), so the list stays in its scrolling
 * state and the test does not depend on how long the badge lingers afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderScrollPercentTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: ReaderHarness
    private var heldDown: Pair<RecyclerView, Long>? = null

    @After
    fun tearDown() {
        heldDown?.let { (rv, downTime) -> rv.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_CANCEL, 0f)) }
        harness.close()
    }

    /**
     * Note: this test device measures text at 1 px a character and a 38 px line, so the estimate's
     * rows are 38 px high here against 77 px in the layout, and [LONG] is chosen to be long by the
     * estimate (600 x 38 px is over 6 screens of 2541 px), not only by the layout.
     *
     * [paragraphs] one-line paragraphs, 77 px each in this test device's layout (about 33 to a
     * screen). One text row each, so the row estimate and the layout agree on how tall they are.
     */
    private fun document(paragraphs: Int) =
        (1..paragraphs).joinToString("\n\n") { i -> "Paragraph $i. lorem ipsum dolor sit amet" }

    private fun open(paragraphs: Int): RecyclerView = openText(document(paragraphs), minBlocks = paragraphs)

    /** Opens [text] and waits until the list has at least [minBlocks] blocks and is laid out. */
    private fun openText(text: String, minBlocks: Int): RecyclerView {
        harness = ReaderHarness(composeRule, tempFolder)
        val vm = harness.viewModel(text)
        harness.show(vm)
        vm.loadFile(Uri.parse("content://test/scroll-percent.md"))
        harness.awaitReader("the reader shows blocks and is laid out") {
            val rv = harness.readerList()
            rv != null && (rv.adapter?.itemCount ?: 0) >= minBlocks && rv.childCount > 0 && rv.height > 0
        }
        return harness.readerList()!!
    }

    private fun motion(downTime: Long, action: Int, y: Float) =
        MotionEvent.obtain(downTime, downTime + 10, action, 200f, y, 0)

    /** Puts a finger on the list and drags it up the screen, and leaves it there. */
    private fun dragAndHold(rv: RecyclerView) {
        val downTime = SystemClock.uptimeMillis()
        heldDown = rv to downTime
        val h = rv.height.toFloat()
        rv.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_DOWN, h * 0.8f))
        listOf(0.7f, 0.5f, 0.2f).forEach { rv.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_MOVE, h * it)) }
        harness.awaitReader("the list is being dragged") { rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING }
    }

    private fun badgeShown() =
        composeRule.onAllNodesWithTag(SCROLL_PERCENT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a long document shows the badge while it is scrolled, not at rest`() {
        val rv = open(paragraphs = LONG)
        assertTrue("the fixture must scroll", rv.canScrollVertically(1))
        harness.awaitReader("settled") { true }
        assertFalse("the badge was shown before any scrolling", badgeShown())

        dragAndHold(rv)
        harness.awaitReader("the badge appears", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() }

        // To the end of the document: still scrolling, still there.
        rv.scrollBy(0, PAST_THE_END_PX)
        harness.awaitReader("the end is reached") { !rv.canScrollVertically(1) }
        assertTrue("the badge went away at the end", badgeShown())
    }

    @Test
    fun `a short document never shows the badge`() {
        val rv = open(paragraphs = 60)
        assertTrue("the fixture must scroll, or nothing is tested", rv.canScrollVertically(1))

        dragAndHold(rv)
        // The scroll listener has run for the drag; give the badge every chance to appear.
        repeat(SAMPLES) {
            harness.awaitReader("sample") { true }
            assertFalse("a document of a few screens showed the badge", badgeShown())
        }
        assertEquals(RecyclerView.SCROLL_STATE_DRAGGING, rv.scrollState)
    }

    @Test
    fun `a short page with a big closed details section never shows the badge`() {
        // 60 paragraphs on view (under two screens) and a closed section of 500 more.
        // Counted as rows it would make the page look like 6 screens or more; it takes no room.
        val section = (1..500).joinToString("\n\n") { "Hidden $it. " + "lorem ipsum dolor sit amet ".repeat(7) }
        val rv = openText(
            document(60) + "\n\n<details>\n<summary>More</summary>\n\n$section\n\n</details>",
            minBlocks = 61,
        )
        assertTrue("the fixture must scroll, or nothing is tested", rv.canScrollVertically(1))

        dragAndHold(rv)
        repeat(SAMPLES) {
            harness.awaitReader("sample") { true }
            assertFalse("closed text made a short page look long", badgeShown())
        }
    }

    /** The number the badge shows on screen right now. */
    private fun shownPercent() = composeRule.onNodeWithTag(SCROLL_PERCENT_TAG, useUnmergedTree = true)
        .fetchSemanticsNode().config[ScrollPercentValue]

    @Test
    fun `closing a details section that made the page long is noticed by a scroll that is not a drag`() {
        // 60 paragraphs and an OPEN section of 300 more: long. The finger stays down (the badge shows
        // for the long page), then the section is closed and the list scrolled by a jump, not a drag.
        val section = (1..500).joinToString("\n\n") { "Inside $it. lorem ipsum dolor sit amet" }
        val rv = openText(
            document(60) + "\n\n<details open>\n<summary>More</summary>\n\n$section\n\n</details>",
            minBlocks = 61,
        )
        val details = rv.getTag(R.id.details_state) as DetailsState
        dragAndHold(rv)
        harness.awaitReader("the long page shows the badge", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() }

        val range = details.toggle(details.sections.single().header)
        rv.adapter!!.notifyItemRangeChanged(range.first, range.count())
        harness.awaitReader("the section is closed") { details.isHidden(range.first + 1) }
        rv.scrollBy(0, SMALL_JUMP_PX)
        harness.awaitReader("the badge goes", timeoutMs = BADGE_TIMEOUT_MS) { !badgeShown() }
    }

    @Test
    fun `the number shown is 0 at the top, in between in the middle, 100 at the end, and goes back down`() {
        val rv = open(paragraphs = LONG)
        dragAndHold(rv)
        harness.awaitReader("the badge appears", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() }

        rv.scrollBy(0, -PAST_THE_END_PX)
        harness.awaitReader("the top shows 0", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() && shownPercent() == 0 }

        rv.scrollBy(0, MIDDLE_PX)
        harness.awaitReader("the middle shows a number", timeoutMs = BADGE_TIMEOUT_MS) { shownPercent() != 0 }
        val middle = shownPercent()
        // 23,100 of 46,200 px, of evenly sized blocks.
        assertTrue("middle was $middle", middle in 45..55)

        rv.scrollBy(0, -BACK_UP_PX)
        harness.awaitReader("the number goes down", timeoutMs = BADGE_TIMEOUT_MS) { shownPercent() < middle }
        assertTrue("scrolling up gave ${shownPercent()} after $middle", shownPercent() in 1 until middle)

        rv.scrollBy(0, PAST_THE_END_PX)
        harness.awaitReader("the end shows 100", timeoutMs = BADGE_TIMEOUT_MS) { shownPercent() == 100 }
    }

    @Test
    fun `the badge fades out once the finger is up and the linger has passed`() {
        val rv = open(paragraphs = LONG)
        dragAndHold(rv)
        val downTime = heldDown!!.second
        harness.awaitReader("the badge appears", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() }

        // Finger away: the list stops scrolling; the badge stays for the linger, then goes.
        rv.dispatchTouchEvent(motion(downTime, MotionEvent.ACTION_CANCEL, 0f))
        heldDown = null
        harness.awaitReader("the list is at rest") { rv.scrollState == RecyclerView.SCROLL_STATE_IDLE }
        composeRule.mainClock.advanceTimeBy(JUMP_CONTROLS_LINGER_MS + LINGER_MARGIN_MS)
        harness.awaitReader("the badge is gone", timeoutMs = BADGE_TIMEOUT_MS) { !badgeShown() }
    }

    @Test
    fun `the badge is invisible to TalkBack`() {
        val rv = open(paragraphs = LONG)
        dragAndHold(rv)
        harness.awaitReader("the badge appears", timeoutMs = BADGE_TIMEOUT_MS) { badgeShown() }

        // The tree TalkBack walks (merged): no node carries the number.
        composeRule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("%", substring = true), useUnmergedTree = true).assertCountEquals(0)
        val badge = composeRule.onAllNodesWithTag(SCROLL_PERCENT_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes().single()
        assertFalse("the badge has text", badge.config.contains(SemanticsProperties.Text))
        assertFalse("the badge has a description", badge.config.contains(SemanticsProperties.ContentDescription))
    }

    private companion object {
        const val BADGE_TIMEOUT_MS = 10_000L
        const val SAMPLES = 10
        const val PAST_THE_END_PX = 1_000_000
        const val MIDDLE_PX = 23_100
        const val LONG = 600
        const val BACK_UP_PX = 5_000
        const val LINGER_MARGIN_MS = 1_000L
        const val SMALL_JUMP_PX = 200
    }
}
