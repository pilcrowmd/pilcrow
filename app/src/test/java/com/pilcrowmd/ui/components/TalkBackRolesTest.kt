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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-306: a TalkBack pass heard these controls without a role ("Section 1", "Open source
 * licenses", "IBM Plex Mono. OFL-1.1", "Send us a request") and the Settings section labels
 * were not headings, so heading navigation skipped them.
 *
 * Each check asserts the MERGED node TalkBack focuses: it carries the click action, the role
 * AND the visible label together, so a role on some other node cannot satisfy it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class TalkBackRolesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme, content = content)
            }
        }
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun button(label: String) =
        composeRule.onNode(hasClickAction() and isButton and hasText(label, substring = true))

    @Test
    fun tocHeadingEntryIsALabelledButton() {
        val selected = mutableListOf<String>()
        show {
            HeadingsDrawer(
                headings = listOf(HeadingNode(1, "Section 1", 0), HeadingNode(2, "Section 2", 1)),
                onHeadingSelected = { selected += it.text },
            )
        }
        button("Section 2").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Section 2"), selected)
    }

    @Test
    fun settingsOpenSourceLicensesRowIsALabelledButton() {
        var opened = 0
        show { SettingsScreen(appVersion = "1.0.0", onOpenLicenses = { opened++ }) }
        // The row sits below the fold: an off-screen click lands outside the window and is dropped.
        button("Open source licenses").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun licenseRowIsALabelledButton() {
        var selected = 0
        show { LicenseRowItem(License(name = "IBM Plex Mono", license = "OFL-1.1"), onSelect = { selected++ }) }
        button("IBM Plex Mono").performClick()
        composeRule.waitForIdle()
        assertEquals(1, selected)
    }

    @Test
    fun sendUsARequestIsALabelledButton() {
        show { GitHubIntegrationScreen(onClose = {}) }
        button("Send us a request").assertExists()
    }

    /** M-307: the X was a bare `clickable`, so TalkBack read "Close Settings" with no role. */
    @Test
    fun settingsCloseIsALabelledButton() {
        var closed = 0
        show { SettingsScreen(appVersion = "1.0.0", onClose = { closed++ }) }
        composeRule.onNode(hasClickAction() and isButton and hasContentDescription("Close Settings"))
            .performClick()
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun settingsSectionLabelsAreHeadings() {
        show { SettingsScreen(appVersion = "1.0.0") }
        val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        listOf("APPEARANCE", "FEEDBACK", "RENDERING").forEach { label ->
            composeRule.onAllNodes(isHeading and hasText(label)).assertCountEquals(1)
        }
    }
}
