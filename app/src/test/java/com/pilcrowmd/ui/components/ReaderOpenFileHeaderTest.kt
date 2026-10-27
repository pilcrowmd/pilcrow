// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.recyclerview.widget.LinearLayoutManager
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The line that names the open file sits over the top of the reader's first block and scrolls away
 * with it, without being a row of the list: every adapter position is still a block index and the saved
 * place ([ScrollAnchor]) still counts from the top of the first block's room.
 *
 * The document is 120 one-line blocks. About 34 of them fill the screen (40 of them scrolled only to
 * block 6 before the end stopped the list), so the end of the list is some 86 blocks down: a place at
 * block 20 is far from it, and the end cannot be what decides where a restore lands. Nothing else in the
 * fixture is near an edge either, so the line's room above block 0 is the only thing that can move
 * block 0, the anchor or the line.
 * Every check reads the laid-out views, after the list has laid out the first block (asserted, not
 * assumed). The frame is NEVER drawn here: the line must follow the list by itself, because with
 * hardware drawing a scroll does not redraw the frame (an earlier version moved the line only in
 * `dispatchDraw`; a test that forced a draw passed, the device did not).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ReaderOpenFileHeaderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blocks = 120
    private val content = (1..blocks).joinToString("\n\n") { "Block number $it." }

    private var fontScale by mutableStateOf(1f)

    private fun show(openFile: OpenFileLine?, scrollPosition: ScrollAnchor = ScrollAnchor()): ReaderFrame {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            MarkdownPreview(
                model = remember { renderer.readerModelFor(content) },
                renderer = renderer,
                mermaidRenderer = NullMermaidRenderer,
                fontScale = fontScale,
                scrollPosition = scrollPosition,
                openFile = openFile,
            )
        }
        settle()
        return checkNotNull(findFrame(composeRule.activity.window.decorView)) { "the reader never appeared" }
    }

    private fun findFrame(view: View): ReaderFrame? = when (view) {
        is ReaderFrame -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findFrame(view.getChildAt(it)) }
        else -> null
    }

    /** Lets the list lay out what it was asked for. Nothing is drawn: the line must not wait for a draw. */
    private fun settle() {
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun ReaderFrame.blockText(position: Int): String =
        ((list.findViewHolderForAdapterPosition(position)?.itemView) as? TextView)?.text?.toString()
            ?: error("block $position is not laid out")

    private val noteLine = OpenFileLine("plan.md", listOf("Notes", "projects"))

    @Test
    fun `the line is over block 0, and adapter positions are still block indexes`() {
        val frame = show(noteLine)
        val list = frame.list

        assertEquals("one row per block, none for the line", blocks, list.adapter?.itemCount)
        assertEquals("Block number 1.", frame.blockText(0))
        assertEquals("Block number 2.", frame.blockText(1))

        val caption = frame.caption
        assertEquals(View.VISIBLE, caption.visibility)
        assertEquals("Notes › projects › plan.md", caption.text.toString())
        assertEquals("File: Notes, projects, plan.md", caption.contentDescription.toString())
        assertTrue("the line has a height", caption.height > 0)

        val block0 = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertEquals("block 0 starts below the line", caption.height, block0.top)
        assertEquals("the line starts at the top of the list", 0f, caption.translationY, 0f)
    }

    @Test
    fun `the saved place at rest is the first block at offset 0, as without the line`() {
        val frame = show(noteLine)

        assertEquals(ScrollAnchor(0, 0), frame.list.currentScrollAnchor())
    }

    @Test
    fun `scrolling moves the line with the text and the place counts from the top of the line`() {
        val frame = show(noteLine)
        frame.blockText(0) // block 0 is laid out before it is scrolled

        frame.list.scrollBy(0, SCROLL_PX)
        frame

        assertEquals(ScrollAnchor(0, -SCROLL_PX), frame.list.currentScrollAnchor())
        assertEquals("the line went up with the text", -SCROLL_PX.toFloat(), frame.caption.translationY, 0f)
    }

    @Test
    fun `a jump to a block puts the line out of sight, and a jump back brings it home`() {
        val frame = show(noteLine)
        frame.blockText(0)
        val layoutManager = frame.list.layoutManager as LinearLayoutManager

        layoutManager.scrollToPositionWithOffset(FAR_BLOCK, 0)
        settle()

        assertEquals("the jump landed", "Block number ${FAR_BLOCK + 1}.", frame.blockText(FAR_BLOCK))
        assertNull("block 0 is recycled", frame.list.findViewHolderForAdapterPosition(0))
        val height = frame.caption.height
        assertTrue("the line has a height", height > 0)
        assertEquals("the line waits above the screen", -height.toFloat(), frame.caption.translationY, 0f)
        assertTrue(
            "nothing of the line is on screen",
            frame.caption.top + frame.caption.translationY + height <= 0f,
        )

        layoutManager.scrollToPositionWithOffset(0, 0)
        settle()

        assertEquals("Block number 1.", frame.blockText(0))
        assertEquals("the line is back over block 0", 0f, frame.caption.translationY, 0f)
    }

    @Test
    fun `a scroll far enough to recycle block 0 takes the line off the screen`() {
        val frame = show(noteLine)
        frame.blockText(0)

        frame.list.scrollBy(0, FAR_SCROLL_PX)

        assertNull("block 0 is recycled", frame.list.findViewHolderForAdapterPosition(0))
        assertEquals(-frame.caption.height.toFloat(), frame.caption.translationY, 0f)
    }

    @Test
    fun `a saved place in block 0 is restored to the same place`() {
        val frame = show(noteLine, scrollPosition = ScrollAnchor(0, -SCROLL_PX))

        assertEquals(ScrollAnchor(0, -SCROLL_PX), frame.list.currentScrollAnchor())
        assertEquals(-SCROLL_PX.toFloat(), frame.caption.translationY, 0f)
    }

    @Test
    fun `a saved place further down is restored, and the line is out of sight`() {
        val frame = show(noteLine, scrollPosition = ScrollAnchor(20, 0))

        assertEquals(ScrollAnchor(20, 0), frame.list.currentScrollAnchor())
        assertEquals("Block number 21.", frame.blockText(20))
        assertEquals(
            "the line waits above the screen while block 0 is not laid out",
            -frame.caption.height.toFloat(),
            frame.caption.translationY,
            0f,
        )
    }

    @Test
    fun `a change of text size that resizes the line keeps the place in block 0`() {
        val frame = show(noteLine)
        frame.blockText(0) // block 0 is laid out before it is scrolled
        frame.list.scrollBy(0, SCROLL_PX)
        assertEquals(
            "precondition: block 0 is the first block, scrolled",
            ScrollAnchor(0, -SCROLL_PX),
            frame.list.currentScrollAnchor(),
        )
        val heightBefore = frame.caption.height

        fontScale = 1.5f
        settle()
        frame.blockText(0) // block 0 is laid out again after the rebuild
        frame

        assertNotEquals("precondition: the line changed size", heightBefore, frame.caption.height)
        assertEquals(ScrollAnchor(0, -SCROLL_PX), frame.list.currentScrollAnchor())
        assertEquals(-SCROLL_PX.toFloat(), frame.caption.translationY, 0f)
    }

    @Test
    fun `without a line nothing is reserved and block 0 is at the top`() {
        val frame = show(null)

        assertEquals(View.GONE, frame.caption.visibility)
        assertEquals(0, frame.list.findViewHolderForAdapterPosition(0)!!.itemView.top)
        assertEquals(ScrollAnchor(0, 0), frame.list.currentScrollAnchor())
    }

    @Test
    fun `a file with no folder is its name alone, with the plain description`() {
        val frame = show(OpenFileLine("plan.md"))

        assertEquals("plan.md", frame.caption.text.toString())
        assertEquals("File: plan.md", frame.caption.contentDescription.toString())
        assertNotNull(frame.caption.compoundDrawablesRelative[0])
    }

    private companion object {
        const val DRAIN_PASSES = 5
        const val SCROLL_PX = 7
        const val FAR_BLOCK = 30
        const val FAR_SCROLL_PX = 6000
    }
}
