// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-126 — a load that fails must say so, and must keep saying so on the screen the user is left
 * looking at.
 *
 * **Why this asserts on the welcome screen and not on the toast.** The toast clears itself after
 * 2.8 s. A user whose file failed to open is then sitting on a welcome screen with no document and
 * no explanation, which is the state that reads as "the app is broken" — the exact complaint this
 * row exists to prevent. The persistent line is the part that survives the toast, so it is the part
 * worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WelcomeLoadErrorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(message: String?) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                WelcomeScreen(loadErrorMessage = message)
            }
        }
    }

    @Test
    fun aFailedOpenIsStatedOnTheWelcomeScreen() {
        show("Couldn't open that file")

        composeRule.onNodeWithText("Couldn't open that file").assertIsDisplayed()
    }

    @Test
    fun withNoFailureTheWelcomeScreenSaysNothingExtra() {
        // The default must leave the resting screen exactly as it was — this is what keeps every
        // existing golden valid, the same construction `isLoading` uses.
        show(null)

        composeRule.onAllNodesWithText("Couldn't open that file").assertCountEquals(0)
    }
}
