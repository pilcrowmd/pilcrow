// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Languages spec D1: the Settings Language row shows the current choice, opens a dialog of the
 * choices each written in its own language, and reports the one tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsLanguageRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chosen = mutableListOf<AppLanguage>()

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    SettingsScreen(
                        appLanguage = AppLanguage.SYSTEM,
                        onAppLanguageSelected = { chosen += it },
                        appVersion = "1.0.0",
                    )
                }
            }
        }
    }

    @Test
    fun `tapping the row lists the languages and tapping one reports it`() {
        show()
        composeRule.onNodeWithText("Language").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("System default").assertIsDisplayed()

        composeRule.onNodeWithText("Language").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Deutsch").assertIsDisplayed()

        composeRule.onNodeWithText("Deutsch").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(AppLanguage.GERMAN), chosen)
    }
}
