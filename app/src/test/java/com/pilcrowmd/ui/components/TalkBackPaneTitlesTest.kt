// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-309: moving between screens (Welcome, Settings, Licenses) gave TalkBack
 * nothing to announce, so a screen change was silent. Each screen's root now carries a pane title.
 *
 * Each check reads EVERY pane title in the unmerged tree and expects exactly the one list, so a
 * title on the wrong node, a second title, or none at all fails it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class TalkBackPaneTitlesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme, content = content)
            }
        }
    }

    private fun paneTitles(): List<String> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .map { it.config[SemanticsProperties.PaneTitle] }

    @Test
    fun welcomeIsPilcrowMD() {
        show { WelcomeScreen() }
        assertEquals(listOf("PilcrowMD"), paneTitles())
    }

    @Test
    fun settingsIsSettings() {
        show { SettingsScreen(appVersion = "1.0.0") }
        assertEquals(listOf("Settings"), paneTitles())
    }

    @Test
    fun licensesListIsOpenSourceLicenses() {
        show { LicensesScreen(onClose = {}) }
        assertEquals(listOf("Open source licenses"), paneTitles())
    }

    @Test
    fun licenseDetailIsTheLicenseName() {
        show { LicenseDetailView(License(name = "IBM Plex Mono", license = "OFL-1.1"), onBack = {}) }
        assertEquals(listOf("IBM Plex Mono"), paneTitles())
    }
}
