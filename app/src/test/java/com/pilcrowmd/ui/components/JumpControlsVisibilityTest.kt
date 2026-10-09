// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-195: the reader's jump buttons are drawn over the text, so they are hidden at rest, appear
 * while the list is dragged, and fade out [JUMP_CONTROLS_LINGER_MS] after it stops.
 *
 * Each assertion waits on the state the gesture actually publishes, with the Compose clock under
 * manual control, so the linger is only ever advanced by this test. The drag ends with the finger
 * held still for longer than the velocity window, so the release produces no fling and the list
 * goes straight to idle: the timer starts at `up()`, and nothing else can move it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-night-450dpi")
class JumpControlsVisibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val longDocument = (1..60).joinToString("\n\n") { "Paragraph $it of a document longer than the screen." }

    @Test
    fun hiddenAtRestOnALongDocument() {
        showReader()

        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).assertDoesNotExist()
    }

    @Test
    fun shownWhileDraggingThenFadeOutAfterTheLinger() {
        showReader()

        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -DRAG_STEP_PX)) }
        }
        settle()
        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertExists()
        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).assertExists()

        // Hold still past the velocity window, then lift: no fling, so the list is idle at `up()`.
        composeRule.onRoot().performTouchInput {
            advanceEventTime(HOLD_BEFORE_RELEASE_MS)
            up()
        }
        val released = composeRule.mainClock.currentTime
        settle()

        // Both checks are timed from the release with no settle frames after the advance, so each
        // lands at the stated instant: just short of the linger (still shown), and the linger plus
        // the fade (gone). The default fade takes about 0.4 s (measured, 2026-09-30).
        advanceTo(released + JUMP_CONTROLS_LINGER_MS - LINGER_MARGIN_MS)
        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertExists()

        advanceTo(released + JUMP_CONTROLS_LINGER_MS + FADE_OUT_ALLOWANCE_MS)
        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).assertDoesNotExist()
    }

    /**
     * The timing test reads [JUMP_CONTROLS_LINGER_MS], so it cannot notice the constant itself
     * changing. 1.5 s is the chosen design value, so it is pinned here on its own.
     */
    @Test
    fun lingerIsTheRuledOneAndAHalfSeconds() {
        assertEquals(1_500L, JUMP_CONTROLS_LINGER_MS)
    }

    /**
     * With TalkBack on (touch exploration), the buttons stay shown and do not fade: at rest, and
     * long after a scroll stops. Checked past the linger and the fade, so a fade would be seen.
     */
    @Test
    fun withTalkBackTheButtonsStayShownAndDoNotFade() {
        val accessibility = composeRule.activity.getSystemService(AccessibilityManager::class.java)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        showReader()

        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertExists()
        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).assertExists()

        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -DRAG_STEP_PX)) }
            advanceEventTime(HOLD_BEFORE_RELEASE_MS)
            up()
        }
        val released = composeRule.mainClock.currentTime
        advanceTo(released + JUMP_CONTROLS_LINGER_MS + FADE_OUT_ALLOWANCE_MS)
        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).assertExists()
        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).assertExists()
    }

    /** The labels TalkBack reads, and a 48 dp touch target around each 40 dp circle. */
    @Test
    fun shownButtonsKeepTheirLabelsAndTouchTargets() {
        showReader()

        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -DRAG_STEP_PX)) }
        }
        settle()

        listOf(SCROLL_TO_TOP, SCROLL_TO_BOTTOM).forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assertTouchWidthIsEqualTo(48.dp)
                .assertTouchHeightIsEqualTo(48.dp)
        }
        composeRule.onRoot().performTouchInput { up() }
    }

    private fun showReader() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                MarkdownPreview(content = longDocument, renderer = renderer)
            }
        }
        settle()
    }

    /** Moves the Compose clock to [time] and runs the work posted by then, and nothing more. */
    private fun advanceTo(time: Long) {
        composeRule.mainClock.advanceTimeBy(time - composeRule.mainClock.currentTime)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Frames and posted RecyclerView work, without moving the Compose clock past a frame. */
    private fun settle() {
        repeat(SETTLE_PASSES) {
            composeRule.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private companion object {
        const val SCROLL_TO_TOP = "Scroll to top"
        const val SCROLL_TO_BOTTOM = "Scroll to bottom"
        const val DRAG_STEPS = 10
        const val DRAG_STEP_PX = 40f
        const val HOLD_BEFORE_RELEASE_MS = 500L
        const val LINGER_MARGIN_MS = 50L
        const val FADE_OUT_ALLOWANCE_MS = 450L
        const val SETTLE_PASSES = 10
    }
}
