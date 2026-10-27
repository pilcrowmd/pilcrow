// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S8-A38: Back from a licence's page returns to the licence list, not to Settings. The list is where
 * the user came from, and the arrow on the page already did that; system Back skipped it, because
 * MainScreen's own Back handler (stood in for here by [outerBacks]) sends Licenses back to Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class LicensesBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var outerBacks = 0
    private var closes = 0

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    // As in MainScreen: the screen's handler is registered before the Licenses screen's.
                    BackHandler { outerBacks++ }
                    LicensesScreen(onClose = { closes++ })
                }
            }
        }
    }

    private fun paneTitles(): List<String> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .map { it.config[SemanticsProperties.PaneTitle] }

    private fun openFirstLicense() {
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).onFirst()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists() // precondition: the detail page
    }

    private fun systemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun systemBackFromALicensePageReturnsToTheList() {
        show()
        openFirstLicense()

        systemBack()

        assertEquals(listOf("Open source licenses"), paneTitles())
        assertEquals("Back must not have left Licenses", 0, outerBacks)
        assertEquals(0, closes)
    }

    @Test
    fun theArrowOnALicensePageReturnsToTheList() {
        show()
        openFirstLicense()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Open source licenses"), paneTitles())
        assertEquals(0, outerBacks)
    }

    @Test
    fun backFromTheListStillLeavesLicenses() {
        // Control: the page's handler is off on the list, so Back reaches the screen's handler as before.
        show()
        openFirstLicense()
        systemBack()

        systemBack()

        assertEquals(1, outerBacks)
    }
}
