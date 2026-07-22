// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Interaction tests for Fix #5 — clearing the Recents list now goes through a confirm dialog.
 * Cancel keeps the list; only the confirm action invokes onClearRecents.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WelcomeClearRecentsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val recents = listOf(
        RecentFileUi(Uri.parse("content://test/doc.md"), "doc.md", 0L, available = true),
    )

    @Test
    fun clearShowsConfirmDialog_andCancelKeepsList() {
        var cleared = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                WelcomeScreen(recentFiles = recents, onClearRecents = { cleared = true })
            }
        }

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.onNodeWithText("Clear recent files?").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertFalse("Cancel must not clear the recents", cleared)
    }

    @Test
    fun confirmInvokesClear() {
        var cleared = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                WelcomeScreen(recentFiles = recents, onClearRecents = { cleared = true })
            }
        }

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.onNodeWithText("Clear all").performClick()
        composeRule.waitForIdle()
        assertTrue("Confirm must clear the recents", cleared)
    }
}
