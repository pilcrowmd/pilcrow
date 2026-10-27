// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S8-A30: all switches sit together, with no "Rendering" heading; Feedback has one "Email us"
 * entry (subtitle "Tell us what to build") and no separate feature-request card.
 *
 * Positions are read from layout, so the order is checked without scrolling, and the viewport is
 * tall enough that nothing is left uncomposed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp-night-xxhdpi")
class SettingsTidyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setSettings() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                SettingsScreen(appVersion = "1.0.0")
            }
        }
    }

    private fun top(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    @Test
    fun `wrap switch comes right after the formatting bar switch`() {
        setSettings()
        val bar = top("Show formatting bar")
        val wrap = top("Wrap long lines in code blocks")
        val feedback = top("FEEDBACK")
        assertTrue("wrap after formatting bar", wrap > bar)
        assertTrue("wrap before Feedback", wrap < feedback)
    }

    @Test
    fun `there is no Rendering heading`() {
        setSettings()
        composeRule.onAllNodesWithText("RENDERING", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `there is no separate feature request card, Email us carries the words`() {
        setSettings()
        composeRule.onAllNodesWithText("Tell us what to build").assertCountEquals(1)
        composeRule.onAllNodesWithText("pilcrowmd@gmail.com", substring = true).assertCountEquals(0)
        assertTrue(top("Email us") < top("Report an issue on GitHub"))
    }
}
