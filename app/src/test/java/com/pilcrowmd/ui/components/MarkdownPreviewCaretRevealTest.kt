// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Edit to Read with the keyboard open, through the real reader composable. The reader restores the
 * scroll anchor saved when its list was full height; the keyboard (imePadding in the screen) has
 * since shortened the list, so the block the caret sat in can be below its bottom edge. With the
 * keyboard open the reader brings that block into view; with it closed it moves nothing.
 *
 * The list is a short box ("the space above the keyboard") holding 40 one-line blocks, anchored at
 * block 30, so the last block starts out below the edge. The keyboard-closed case runs the same
 * fixture and asserts that block IS still out of view, which is what makes the open case a proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewCaretRevealTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val blockCount = 40
    private val lastBlock = blockCount - 1
    private val content = (0 until blockCount).joinToString("\n\n") { "Paragraph $it." }

    /** (block, seq) the ViewModel would publish; (-1, 0) is "nothing pending". */
    private val reveal = mutableStateOf(-1 to 0)

    /**
     * A parameter the reader's `update` captures but which, with no search matches, moves nothing:
     * changing it makes `update` run again, as the real screen does whenever any state it reads changes.
     */
    private val unrelated = mutableStateOf(0)
    private val acked = mutableListOf<Int>()

    private fun show(imeVisible: Boolean) {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember { renderer.readerModelFor(content) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = ScrollAnchor(index = ANCHOR_BLOCK, offset = 0),
                    currentMatchIndex = unrelated.value,
                    caretRevealBlock = reveal.value.first,
                    caretRevealSeq = reveal.value.second,
                    onCaretRevealHandled = { acked += it },
                    imeVisible = imeVisible,
                )
            }
        }
        drain()
    }

    private fun drain() {
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun findList(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
        else -> null
    }

    private fun list() = findList(composeRule.activity.window.decorView)!!

    private fun RecyclerView.block(position: Int): View? =
        (layoutManager as LinearLayoutManager).findViewByPosition(position)

    private fun RecyclerView.firstVisible() = (layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

    private fun publishReveal(block: Int, seq: Int) {
        composeRule.runOnIdle { reveal.value = block to seq }
        drain()
    }

    @Test
    fun `with the keyboard open the caret block is brought fully above its edge, and acknowledged`() {
        show(imeVisible = true)
        publishReveal(lastBlock, seq = 1)

        val rv = list()
        val block = rv.block(lastBlock)
        assertTrue("the caret block is laid out", block != null)
        assertTrue(
            "fully inside the list: ${block!!.top}..${block.bottom} of ${rv.height}",
            block.top >= 0 && block.bottom <= rv.height,
        )
        assertEquals(listOf(1), acked)
    }

    @Test
    fun `with the keyboard closed nothing moves, and the reveal is still acknowledged`() {
        show(imeVisible = false)
        val rv = list()
        val firstBefore = rv.firstVisible()
        val topBefore = rv.block(firstBefore)!!.top
        assertTrue(
            "guard: without the reveal the caret block is out of view, so the case above proves something",
            rv.block(lastBlock).let { it == null || it.bottom > rv.height },
        )

        publishReveal(lastBlock, seq = 1)

        assertEquals(firstBefore, rv.firstVisible())
        assertEquals(topBefore, rv.block(firstBefore)!!.top)
        assertEquals(listOf(1), acked)
    }

    @Test
    fun `a list the user has already dragged is not moved by a late reveal, which is still acknowledged`() {
        show(imeVisible = true)
        val rv = list()
        dragDown(rv)

        publishReveal(lastBlock, seq = 1)

        assertTrue(
            "the list stays where the user left it, short of the caret block",
            rv.block(lastBlock).let { it == null || it.bottom > rv.height },
        )
        assertEquals(listOf(1), acked)
    }

    /** A finger drag that scrolls toward the top, ended by a cancel so no fling follows. */
    private fun dragDown(rv: RecyclerView) {
        composeRule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) = rv.dispatchTouchEvent(MotionEvent.obtain(now, now, action, 100f, y, 0))
            send(MotionEvent.ACTION_DOWN, 100f)
            send(MotionEvent.ACTION_MOVE, 200f)
            send(MotionEvent.ACTION_MOVE, 300f)
            assertEquals("guard: the finger is dragging", RecyclerView.SCROLL_STATE_DRAGGING, rv.scrollState)
            send(MotionEvent.ACTION_CANCEL, 300f)
        }
        drain()
    }

    @Test
    fun `a reveal is performed once per seq and not replayed by a later recomposition`() {
        show(imeVisible = true)
        publishReveal(lastBlock, seq = 1)
        val rv = list()
        composeRule.runOnUiThread { rv.scrollBy(0, -SCROLL_BACK_PX) }
        val scrolledBack = rv.firstVisible()

        composeRule.runOnIdle { unrelated.value += 1 }
        drain()

        assertEquals("not pulled back to the caret block", scrolledBack, rv.firstVisible())
        assertEquals(listOf(1), acked)
    }

    @Test
    fun `a new seq reveals again`() {
        show(imeVisible = true)
        publishReveal(lastBlock, seq = 1)
        val rv = list()
        composeRule.runOnUiThread { rv.scrollBy(0, -SCROLL_BACK_PX) }
        assertTrue("guard: scrolled away from the end", rv.block(lastBlock).let { it == null || it.bottom > rv.height })

        publishReveal(lastBlock, seq = 2)

        val block = rv.block(lastBlock)
        assertTrue("revealed again", block != null && block.bottom <= rv.height && block.top >= 0)
        assertEquals(listOf(1, 2), acked)
    }

    private companion object {
        const val DRAIN_PASSES = 5
        const val BOX_DP = 150
        const val ANCHOR_BLOCK = 30
        const val SCROLL_BACK_PX = 600
    }
}
