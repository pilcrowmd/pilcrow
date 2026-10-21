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
 * M-392: the "Email us" and "Report an issue on GitHub" rows in the FEEDBACK section. Each row is
 * present, is announced as a button, and a tap calls its own callback and no other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsContactRowsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var emailTaps = 0
    private var issueTaps = 0
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun setSettings() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                SettingsScreen(
                    appVersion = "1.0.0",
                    onEmailUs = { emailTaps++ },
                    onReportIssue = { issueTaps++ },
                )
            }
        }
    }

    @Test
    fun `contact rows show their title and subtitle`() {
        setSettings()
        composeRule.onNodeWithText("Email us").performScrollTo().assertExists()
        composeRule.onNodeWithText("pilcrowmd@gmail.com").performScrollTo().assertExists()
        composeRule.onNodeWithText("Report an issue on GitHub").performScrollTo().assertExists()
        composeRule.onNodeWithText("Opens in your browser").performScrollTo().assertExists()
    }

    @Test
    fun `tapping Email us calls only onEmailUs`() {
        setSettings()
        composeRule.onNodeWithText("Email us").performScrollTo().performClick()
        assertEquals(1, emailTaps)
        assertEquals(0, issueTaps)
    }

    @Test
    fun `tapping the email address row calls only onEmailUs`() {
        setSettings()
        composeRule.onNodeWithText("pilcrowmd@gmail.com").performScrollTo().performClick()
        assertEquals(1, emailTaps)
        assertEquals(0, issueTaps)
    }

    @Test
    fun `tapping Report an issue calls only onReportIssue`() {
        setSettings()
        composeRule.onNodeWithText("Report an issue on GitHub").performScrollTo().performClick()
        assertEquals(0, emailTaps)
        assertEquals(1, issueTaps)
    }

    @Test
    fun `email row is a button`() {
        setSettings()
        composeRule.onNode(hasClickAction() and isButton and hasText("Email us", substring = true)).assertExists()
    }

    @Test
    fun `report issue row is a button`() {
        setSettings()
        composeRule.onNode(
            hasClickAction() and isButton and hasText("Report an issue on GitHub", substring = true),
        ).assertExists()
    }
}
