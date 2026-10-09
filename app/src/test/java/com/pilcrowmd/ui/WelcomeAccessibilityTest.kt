// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-268: under TalkBack every tappable control on the Welcome screen announces itself as a button,
 * and the decorative sparkle in the divider is not read at all. Before the fix the six controls were
 * bare `clickable`s with no role, so TalkBack read their text and nothing to say they act, and the
 * divider was read as "four-pointed star".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class WelcomeAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val recents = listOf(
        RecentFileUi(Uri.parse("content://test/doc.md"), "doc.md", 0L, available = true),
    )

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                WelcomeScreen(recentFiles = recents)
            }
        }
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun assertButton(control: SemanticsNodeInteraction) = control.assert(isButton)

    @Test
    fun openMdFileIsAButton() {
        show()
        assertButton(composeRule.onNode(hasText("Open MD File") and hasClickAction()))
    }

    @Test
    fun createMdFileIsAButton() {
        show()
        assertButton(composeRule.onNode(hasText("Create MD File") and hasClickAction()))
    }

    @Test
    fun browseAllFilesIsAButton() {
        show()
        assertButton(composeRule.onNode(hasText("Browse all files", substring = true) and hasClickAction()))
    }

    @Test
    fun clearRecentsIsAButton() {
        show()
        assertButton(composeRule.onNode(hasText("Clear") and hasClickAction()))
    }

    @Test
    fun recentFileRowIsAButton() {
        show()
        assertButton(composeRule.onNode(hasText("doc.md") and hasClickAction()))
    }

    @Test
    fun removeFromRecentsIsAButton() {
        show()
        assertButton(composeRule.onNode(hasContentDescription("Remove from recents") and hasClickAction()))
    }

    @Test
    fun decorativeSparkleIsNotRead() {
        show()
        composeRule.onNodeWithText("Beautiful Markdown Reading").assertExists()
        composeRule.onNodeWithText("✦", useUnmergedTree = true).assertDoesNotExist()
    }
}
