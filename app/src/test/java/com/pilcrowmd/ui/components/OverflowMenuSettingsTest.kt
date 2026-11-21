// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The document toolbar's ⋮ menu opens Settings, so reaching them no longer means closing the document. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class OverflowMenuSettingsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(onOpenSettings: (() -> Unit)?) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                PilcrowToolbar(onOpenSettings = onOpenSettings)
            }
        }
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun tappingSettingsOpensThemAndClosesTheMenu() {
        var opened = 0
        show { opened++ }
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened)
        composeRule.onAllNodesWithText("Save a copy").assertCountEquals(0)
    }

    @Test
    fun noCallbackNoItem() {
        show(null)
        composeRule.onNodeWithText("Save a copy").assertExists()
        composeRule.onAllNodesWithText("Settings").assertCountEquals(0)
    }
}
