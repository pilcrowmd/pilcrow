// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-309: the pane title is only useful if Compose turns it into the event TalkBack speaks. A screen
 * entering composition sends TYPE_WINDOW_STATE_CHANGED with CONTENT_CHANGE_TYPE_PANE_APPEARED, and
 * its text is the title. Accessibility is reported as running, as in [JumpControlsTalkBackTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class PaneTitleEventTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var settingsShown by mutableStateOf(false)

    private val accessibility: AccessibilityManager
        get() = composeRule.activity.getSystemService(AccessibilityManager::class.java)

    @Test
    fun aScreenEnteringCompositionSendsPaneAppeared() {
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setTouchExplorationEnabled(true)
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                if (settingsShown) SettingsScreen(appVersion = "1.0.0")
            }
        }
        settle()
        assertEquals("precondition: nothing announced before the screen", emptyList<String>(), paneEvents())

        composeRule.runOnIdle { settingsShown = true }
        settle()

        assertEquals(listOf("Settings"), paneEvents())
    }

    /** The text of every pane-appeared window-state event sent so far. */
    private fun paneEvents(): List<String> = shadowOf(accessibility).sentAccessibilityEvents
        .filter {
            it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                it.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0
        }
        .map { it.text.joinToString() }

    // Compose batches accessibility events on a 100 ms timer, so advance well past it.
    private fun settle() = repeat(SETTLE_PASSES) {
        composeRule.mainClock.advanceTimeBy(FRAME_MS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        const val SETTLE_PASSES = 10
        const val FRAME_MS = 50L
    }
}
