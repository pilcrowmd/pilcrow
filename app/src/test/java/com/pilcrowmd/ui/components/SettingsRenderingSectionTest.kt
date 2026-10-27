// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings lists only real settings. LaTeX renders natively and cannot be switched, so a static
 * "LaTeX Math ... Built-in" row was noise, not a setting.
 *
 * The wrap-lines row is asserted PRESENT first, so an absent "LaTeX Math" cannot be the result of
 * the screen simply not being composed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsRenderingSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `settings has no static LaTeX Math row`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    SettingsScreen(
                        lineNumbersEnabled = true,
                        onLineNumbersChanged = {},
                        openInEditMode = false,
                        onOpenInEditModeChanged = {},
                        wrapCodeLines = false,
                        onWrapCodeLinesChanged = {},
                        formattingBarEnabled = true,
                        onFormattingBarChanged = {},
                        appVersion = "1.0.0",
                    )
                }
            }
        }
        composeRule.onNodeWithText("Wrap long lines in code blocks", substring = true).assertExists()
        composeRule.onAllNodesWithText("LaTeX Math", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("Built-in", substring = true, useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
