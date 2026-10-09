// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every on/off setting is ONE accessible control: the whole row is toggleable with `Role.Switch`,
 * carries its title as its label, and is at least 48 dp tall. Before this, the label sat in a
 * Column beside a bare `Switch`; the row was not clickable, so TalkBack focused the Switch alone
 * and announced "On, switch" with no name, and its touch target was 41x38 dp.
 *
 * The label check runs over EVERY toggleable node, so a bare `Switch` that keeps its own
 * `onCheckedChange` — the defect's shape — fails it even if its row is also toggleable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsSwitchRowAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val toggled = mutableMapOf<String, Boolean>()

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    SettingsScreen(
                        lineNumbersEnabled = true,
                        onLineNumbersChanged = { toggled[LINE_NUMBERS] = it },
                        openInEditMode = false,
                        onOpenInEditModeChanged = { toggled[OPEN_IN_EDIT] = it },
                        mermaidCloudEnabled = false,
                        onMermaidCloudChanged = { toggled[MERMAID] = it },
                        wrapCodeLines = false,
                        onWrapCodeLinesChanged = { toggled[WRAP] = it },
                        formattingBarEnabled = true,
                        onFormattingBarChanged = { toggled[FORMATTING_BAR] = it },
                        appVersion = "1.0.0",
                    )
                }
            }
        }
    }

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun switchRow(title: String) =
        composeRule.onNode(isToggleable() and isSwitch and hasText(title, substring = true))

    private fun assertRowToggles(title: String, key: String, expected: Boolean) {
        show()
        val row = switchRow(title)
        row.performScrollTo()
        row.assertHeightIsAtLeast(48.dp)
        row.assertWidthIsAtLeast(48.dp)
        row.performClick()
        composeRule.waitForIdle()
        assertEquals("tapping the \"$title\" row flips its setting", expected, toggled[key])
    }

    @Test
    fun lineNumbersRowIsOneLabelledSwitch() = assertRowToggles(LINE_NUMBERS, LINE_NUMBERS, expected = false)

    @Test
    fun openInEditModeRowIsOneLabelledSwitch() = assertRowToggles(OPEN_IN_EDIT, OPEN_IN_EDIT, expected = true)

    @Test
    fun wrapCodeLinesRowIsOneLabelledSwitch() = assertRowToggles(WRAP, WRAP, expected = true)

    @Test
    fun formattingBarRowIsOneLabelledSwitch() = assertRowToggles(FORMATTING_BAR, FORMATTING_BAR, expected = false)

    @Test
    fun mermaidRowIsOneLabelledSwitch() = assertRowToggles(MERMAID, MERMAID, expected = true)

    @Test
    fun noToggleableNodeLacksALabel() {
        show()
        val nodes = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        assertEquals("one toggleable node per switch setting", 5, nodes.size)
        nodes.forEach { node ->
            val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            assertTrue("toggleable node ${node.id} has no label", label.isNotBlank())
        }
    }

    private companion object {
        const val LINE_NUMBERS = "Line numbers"
        const val OPEN_IN_EDIT = "Open in edit mode"
        const val WRAP = "Wrap long lines in code blocks"
        const val FORMATTING_BAR = "Show formatting bar"
        const val MERMAID = "Mermaid Diagrams"
    }
}
