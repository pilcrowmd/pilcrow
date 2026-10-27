// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.pilcrowmd.ui.components.PilcrowToolbar
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S8-A43: a swiped-in top bar hides after 3 s, but not while its ⋮ menu is open (the menu is a
 * popup of the bar and would close with it). The 3 s start again when the menu closes.
 *
 * The clock is the compose test clock, so the waits are exact. Every check on "still shown" is
 * made after MORE than 3 s, so only the menu can be what keeps the bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class ReadingBarsMenuTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var bars: ReadingBars

    /** Idle waits do not move the test clock; only this does. Wait first so the timer has started. */
    private fun passTime(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
    }

    private fun show(menuOpen: Boolean = false) {
        composeRule.setContent { bars = rememberReadingBars(wanted = true) }
        composeRule.waitForIdle()
        // One UI-thread step, so the hide timer never sees the bar without the menu.
        composeRule.runOnUiThread {
            bars.onEdgeSwipe()
            bars.onMenuOpenChanged(menuOpen)
        }
    }

    @Test
    fun withNoMenuTheBarHidesByItself() {
        show()
        passTime(4_000)
        composeRule.runOnIdle { assertFalse("control: the timer never hid the bar", bars.revealed) }
    }

    @Test
    fun anOpenMenuKeepsTheBar() {
        show(menuOpen = true)
        passTime(6_000)
        composeRule.runOnIdle { assertTrue("bar hid under an open menu", bars.revealed) }
    }

    @Test
    fun theBarHidesOnceTheMenuCloses() {
        show(menuOpen = true)
        passTime(6_000)
        composeRule.runOnUiThread { bars.onMenuOpenChanged(false) }
        passTime(4_000)
        composeRule.runOnIdle { assertFalse("never hid after the menu closed", bars.revealed) }
    }

    @Test
    fun theToolbarMenuTellsTheBarsWhenItOpensAndCloses() {
        val seen = mutableListOf<Boolean>()
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                PilcrowToolbar(onMenuOpenChanged = { seen += it })
            }
        }
        composeRule.runOnIdle { assertEquals(listOf(false), seen) }
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(false, true), seen)
    }
}
