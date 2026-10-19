// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.pilcrowmd.testing.useKeyboardMode
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-307 #1: after Close file the keyboard landed nowhere on the Welcome screen. With a hardware
 * keyboard the Settings gear (the screen's first stop in reading order) now takes focus when the
 * screen enters composition. In touch mode, TalkBack included, the request is not made at all; that
 * half has no test, because Compose refuses focus to a button in touch mode whatever asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class WelcomeFocusTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var welcomeShown by mutableStateOf(false)

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun inKeyboardModeTheSettingsGearTakesFocusWhenTheScreenAppears() {
        lateinit var manager: InputModeManager
        composeRule.setContent {
            manager = LocalInputModeManager.current
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                if (welcomeShown) WelcomeScreen()
            }
        }
        composeRule.runOnIdle {
            manager.useKeyboardMode()
            welcomeShown = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Settings").assertIsFocused()
    }
}
