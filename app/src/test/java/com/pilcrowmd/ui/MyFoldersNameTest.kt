// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.screenshot.FilesSample
import com.pilcrowmd.ui.components.FilesScreen
import com.pilcrowmd.ui.components.PilcrowToolbar
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S8-A38: the screen of added folders is called "My folders" everywhere the user meets its name: the
 * Welcome row that opens it, the reader menu item that opens it, and its own title. The old names
 * ("Browse folders", "Files") must be gone from all three, or two names for one screen is back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class MyFoldersNameTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertNamedMyFolders() {
        composeRule.onNodeWithText("My folders").assertExists()
        composeRule.onAllNodesWithText("Browse folders").assertCountEquals(0)
        composeRule.onAllNodesWithText("Files").assertCountEquals(0)
    }

    @Test
    fun theWelcomeRowIsMyFolders() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) { WelcomeScreen() }
        }
        assertNamedMyFolders()
    }

    @Test
    fun theReaderMenuItemIsMyFolders() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) { PilcrowToolbar(onOpenFiles = {}) }
        }
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.waitForIdle()
        assertNamedMyFolders()
    }

    @Test
    fun theScreenTitleIsMyFolders() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) { FilesScreen(state = FilesSample.loaded) }
        }
        assertNamedMyFolders()
    }
}
