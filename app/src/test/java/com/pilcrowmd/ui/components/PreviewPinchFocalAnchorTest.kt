// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.app.Activity
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Guards the pinch focal anchor: during a pinch, the block under the fingers stays under the fingers while the
 * text grows around it.
 *
 * ## The defect
 *
 * Each pinch frame re-scrolled the list from the focal block's own layout callback. That callback
 * fires INSIDE the list's layout pass, and a scroll requested there is dropped when the pass
 * completes, so the re-scroll never landed: the list kept its first row's top fixed and the focal
 * block slid down by however much everything above it grew. On the phone, a spread on paragraph 5
 * pushed paragraph 5 off the bottom of the screen.
 *
 * ## Why the fixture is shaped this way
 *
 * The list runs in a real window and every frame is a real traversal (layout, then pre-draw, then
 * draw), because the defect lives in the ordering of those passes; calling `layout()` by hand would
 * skip exactly what failed. The focal row is in the MIDDLE with grown rows above it, so a list that
 * merely keeps its first row still is far off (hundreds of px), and only the focal re-scroll can
 * put the point back within [TOLERANCE_PX]. Two frames, so the second frame starts from a list the
 * first frame already moved.
 *
 * The second test moves the fingers while they spread. The point of text that was under them when
 * the pinch began must travel with them; re-taking the point under the fingers on every frame only
 * cancels the growth, so the text stays where it is while the fingers slide away from it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewPinchFocalAnchorTest {

    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private class Rows : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = ROWS

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setTextSize(TypedValue.COMPLEX_UNIT_PX, BASE_TEXT_PX)
            },
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // Explicit line breaks: the row's height follows the text size, not the wrapping width.
            holder.text.text = (1..LINES).joinToString("\n") { "Row $position, line $it" }
        }
    }

    private fun frame() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))

    private fun showList(): RecyclerView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val rv = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            itemAnimator = null
            adapter = Rows()
        }
        val root = FrameLayout(activity).apply { addView(rv, FrameLayout.LayoutParams(WIDTH_PX, VIEWPORT_PX)) }
        activity.setContentView(root)
        frame()
        // Put the focal row in the middle of the viewport, with rows above it on screen.
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(FOCAL_ROW, VIEWPORT_PX / 3)
        frame()
        return rv
    }

    @Test
    fun theBlockUnderTheFingersStaysUnderTheFingersDuringAndAfterThePinch() {
        val rv = showList()
        val before = rv.layoutManager!!.findViewByPosition(FOCAL_ROW)
        assertNotNull("focal row must be on screen", before)
        before!!
        val focusX = WIDTH_PX / 2f
        val focusY = before.top + before.height / 2f
        val fraction = (focusY - before.top) / before.height
        val startHeight = before.height
        val rowsAbove = (0 until rv.childCount).count { rv.getChildAdapterPosition(rv.getChildAt(it)) < FOCAL_ROW }
        assertTrue("rows above the focal row must be on screen, or keeping the top still would pass", rowsAbove >= 1)

        for (factor in listOf(1.25f, 1.5f)) {
            applyPinchFrameKeepingFocus(rv, focusX, focusY, factor)
            frame()
            val row = rv.layoutManager!!.findViewByPosition(FOCAL_ROW)
            assertNotNull("focal row left the screen at ×$factor", row)
            row!!
            assertTrue("the row did not grow at ×$factor (${row.height} vs $startHeight)", row.height > startHeight)
            assertEquals("focal point moved at ×$factor", focusY, row.top + row.height * fraction, TOLERANCE_PX)
        }

        // After the fingers stop: a further frame with no input leaves it where it is.
        frame()
        val after = rv.layoutManager!!.findViewByPosition(FOCAL_ROW)!!
        assertEquals("focal point moved after the pinch", focusY, after.top + after.height * fraction, TOLERANCE_PX)
    }

    @Test
    fun theTextUnderTheFingersTravelsWithTheFingersWhileTheySlideUp() {
        val rv = showList()
        val start = rv.layoutManager!!.findViewByPosition(FOCAL_ROW)!!
        val focusX = WIDTH_PX / 2f
        val startY = start.top + start.height / 2f
        val fraction = (startY - start.top) / start.height
        beginPinchScaleGesture(rv)

        // The fingers land; then they spread while both travel up; the last frame only moves the
        // fingers, at the same scale.
        for ((factor, travel) in listOf(1f to 0f, 1.25f to 60f, 1.5f to 120f, 1.5f to 180f)) {
            val focusY = startY - travel
            applyPinchFrameKeepingFocus(rv, focusX, focusY, factor)
            frame()
            val row = rv.layoutManager!!.findViewByPosition(FOCAL_ROW)
            assertNotNull("focal row left the screen at ×$factor, ${travel}px up", row)
            row!!
            assertEquals(
                "the text under the fingers did not travel with them at ×$factor, ${travel}px up",
                focusY,
                row.top + row.height * fraction,
                TOLERANCE_PX,
            )
        }
    }

    private companion object {
        const val ROWS = 20
        const val LINES = 5
        const val FOCAL_ROW = 5
        const val BASE_TEXT_PX = 40f
        const val WIDTH_PX = 600
        const val VIEWPORT_PX = 1200
        const val FRAME_MS = 50L
        const val TOLERANCE_PX = 3f
    }
}
