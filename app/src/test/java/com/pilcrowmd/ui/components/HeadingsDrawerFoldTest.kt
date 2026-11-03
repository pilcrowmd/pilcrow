// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S8-A49: the fold arrow folds and never jumps; the heading still jumps; one Collapse/Expand all. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class HeadingsDrawerFoldTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // User guide > (Reading > Themes, Fonts), (Editing > Toolbar, Saving), Questions
    private val outline = listOf(
        HeadingNode(1, "User guide", 0),
        HeadingNode(2, "Reading", 1),
        HeadingNode(3, "Themes", 2),
        HeadingNode(3, "Fonts", 3),
        HeadingNode(2, "Editing", 4),
        HeadingNode(3, "Toolbar", 5),
        HeadingNode(3, "Saving", 6),
        HeadingNode(2, "Questions", 7),
    )

    private val selected = mutableListOf<String>()

    private fun show(headings: List<HeadingNode> = outline) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                HeadingsDrawer(headings = headings, onHeadingSelected = { selected += it.text })
            }
        }
    }

    private fun shown(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size == 1

    private fun count(matcher: SemanticsMatcher) = composeRule.onAllNodes(matcher).fetchSemanticsNodes().size

    @Test
    fun arrowTapFoldsWithoutJumping() {
        show()
        composeRule.onNodeWithContentDescription("Collapse Editing").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), selected)
        assertEquals(false, shown("Toolbar"))
        assertEquals(false, shown("Saving"))
        assertEquals(true, shown("Editing"))
        assertEquals(true, shown("Themes"))

        composeRule.onNodeWithContentDescription("Expand Editing").performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), selected)
        assertEquals(true, shown("Toolbar"))
    }

    @Test
    fun headingTapStillJumps() {
        show()
        composeRule.onNodeWithText("Editing").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("Editing"), selected)
    }

    /**
     * The arrow is drawn 28dp wide but answers a finger out to 48dp. Below it is the next row's
     * empty arrow column: 30dp down (past 24) does nothing, 20dp down folds, as does 20dp left.
     */
    @Test
    fun arrowAnswersAFingerOutTo48dp() {
        show()
        val arrow = composeRule.onNodeWithContentDescription("Collapse Editing")
        arrow.performTouchInput { click(Offset(center.x, center.y + 30.dp.toPx())) }
        composeRule.waitForIdle()
        assertEquals(true, shown("Toolbar"))

        arrow.performTouchInput { click(Offset(center.x, center.y + 20.dp.toPx())) }
        composeRule.waitForIdle()
        assertEquals(false, shown("Toolbar"))

        composeRule.onNodeWithContentDescription("Expand Editing").performTouchInput {
            click(Offset(center.x - 20.dp.toPx(), center.y))
        }
        composeRule.waitForIdle()
        assertEquals(true, shown("Toolbar"))
        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun arrowSaysWhatItDoesAndItsState() {
        show()
        val arrow = composeRule.onNodeWithContentDescription("Collapse Editing")
        arrow.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        arrow.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Expand Editing")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        // Only headings with sub-headings get an arrow.
        assertEquals(0, count(hasContentDescription("Collapse Questions")))
        assertEquals(0, count(hasContentDescription("Collapse Themes")))
    }

    @Test
    fun collapseAllThenExpandAll() {
        show()
        composeRule.onNodeWithText("Collapse all").performClick()
        composeRule.waitForIdle()
        assertEquals(true, shown("User guide"))
        assertEquals(false, shown("Reading"))
        assertEquals(false, shown("Questions"))

        composeRule.onNodeWithText("Expand all").performClick()
        composeRule.waitForIdle()
        outline.forEach { assertEquals(it.text, true, shown(it.text)) }
        composeRule.onNodeWithText("Collapse all").assertIsDisplayed()
        assertEquals(emptyList<String>(), selected)
    }

    @Test
    fun anyFoldTurnsTheButtonToExpandAll() {
        show()
        composeRule.onNodeWithContentDescription("Collapse Reading").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Expand all").performClick()
        composeRule.waitForIdle()
        assertEquals(true, shown("Themes"))
    }

    @Test
    fun noButtonWhenNothingCanFold() {
        show(listOf(HeadingNode(1, "One", 0), HeadingNode(1, "Two", 1)))
        assertEquals(0, count(hasText("Collapse all")))
        assertEquals(0, count(hasText("Expand all")))
    }

    @Test
    fun foldsSurviveRecreationAndResetForANewFile() {
        var file by mutableStateOf("a.md")
        val restore = StateRestorationTester(composeRule)
        restore.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                HeadingsDrawer(headings = outline, documentKey = file)
            }
        }
        composeRule.onNodeWithContentDescription("Collapse Editing").performClick()
        composeRule.waitForIdle()

        restore.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(false, shown("Toolbar"))

        file = "b.md"
        composeRule.waitForIdle()
        assertEquals(true, shown("Toolbar"))
    }
}
