// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A clickable Settings card is announced as a button: TalkBack reads its title, subtitle and
 * "Button", and offers "Double-tap to activate". Without the role it reads as plain text that
 * happens to respond to a tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsInfoCardAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun assertCardIsButton(title: String) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                SettingsScreen(appVersion = "1.0.0", showRateCard = true)
            }
        }
        composeRule.onNode(hasClickAction() and isButton and hasText(title, substring = true)).assertExists()
    }

    @Test
    fun rateCardIsAButton() = assertCardIsButton("Rate PilcrowMD")

    @Test
    fun gitHubCardIsAButton() = assertCardIsButton("Tell us what to build")
}
