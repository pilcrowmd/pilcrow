// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The toolbar's left control is ONE mode button showing the action (Edit in Read mode, Read in Edit
 * mode); Save shows in Edit mode only; the Read/Edit switch is 48dp wide and 48dp tall and every other
 * action keeps a 42dp target; all of them fit a 384dp (S24+) bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-night-xxhdpi")
class ToolbarModeButtonTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val modes = mutableListOf<ViewMode>()
    private var saves = 0

    private fun show(mode: ViewMode) {
        val editing = mode == ViewMode.EDITOR
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                PilcrowToolbar(
                    currentMode = mode,
                    onModeSelected = { modes += it },
                    onSave = { saves++ },
                    onUndo = if (editing) ({}) else null,
                    onRedo = if (editing) ({}) else null,
                )
            }
        }
    }

    private fun button(label: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(label)

    @Test
    fun readModeShowsEditAndSwitchesToTheEditor() {
        show(ViewMode.READER)
        button("Edit").assertExists()
        button("Read").assertDoesNotExist()
        button("Save").assertDoesNotExist()
        button("Export PDF").assertExists()
        button("Table of contents").assertExists()

        button("Edit").performClick()
        assertEquals(listOf(ViewMode.EDITOR), modes)
    }

    @Test
    fun editModeShowsReadAndSwitchesToTheReader() {
        show(ViewMode.EDITOR)
        button("Read").assertExists()
        button("Edit").assertDoesNotExist()
        button("Export PDF").assertDoesNotExist()
        button("Undo").assertExists()
        button("Redo").assertExists()

        button("Read").performClick()
        assertEquals(listOf(ViewMode.READER), modes)

        button("Save").assertExists().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun theOldSegmentLabelsAreGoneInReadMode() = assertNoSegmentLabels(ViewMode.READER)

    @Test
    fun theOldSegmentLabelsAreGoneInEditMode() = assertNoSegmentLabels(ViewMode.EDITOR)

    private fun assertNoSegmentLabels(mode: ViewMode) {
        show(mode)
        listOf("Reader", "Editor", "Reader, selected", "Editor, selected").forEach {
            button(it).assertDoesNotExist()
        }
    }

    @Test
    fun readModeActionsFitTheBar() = assertFits(ViewMode.READER, listOf("Edit", "Export PDF"))

    @Test
    fun editModeActionsFitTheBar() = assertFits(ViewMode.EDITOR, listOf("Read", "Save", "Undo", "Redo"))

    private fun assertFits(mode: ViewMode, modeSpecific: List<String>) {
        show(mode)
        val labels = modeSpecific + listOf("Search", "Table of contents", "More actions", "Close file")
        labels.forEach { label ->
            button(label).assertIsDisplayed().assertWidthIsAtLeast(42.dp).assertHeightIsAtLeast(42.dp)
        }
        val right = button("Close file").getUnclippedBoundsInRoot().right
        assertTrue("Close file right edge $right must be <= 384dp", right <= 384.dp)
    }

    @Test
    fun readModeEditIconIsDrawnAtLeast28dp() = assertIconSize(ViewMode.READER, "Edit")

    @Test
    fun editModeReadIconIsDrawnAtLeast28dp() = assertIconSize(ViewMode.EDITOR, "Read")

    /** The Icon is the unmerged child of the button; the merged node is the button itself. */
    private fun assertIconSize(mode: ViewMode, label: String) {
        show(mode)
        composeRule.onNodeWithContentDescription(label, useUnmergedTree = true)
            .assertWidthIsAtLeast(28.dp)
            .assertHeightIsAtLeast(28.dp)
    }

    @Test
    fun readModeButtonIs48dpBy48dp() = assertModeButtonSize(ViewMode.READER, "Edit")

    @Test
    fun editModeButtonIs48dpBy48dp() = assertModeButtonSize(ViewMode.EDITOR, "Read")

    private fun assertModeButtonSize(mode: ViewMode, label: String) {
        show(mode)
        button(label).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
    }

    /**
     * A guard: Compose's minimum touch target already gave the 42 dp button 48 x 48 dp; this keeps it.
     * The physical tap tests above are the real check of the touch area.
     */
    @Test
    fun readModeButtonHasA48dpTouchArea() = assertModeButtonTouchArea(ViewMode.READER, "Edit")

    @Test
    fun editModeButtonHasA48dpTouchArea() = assertModeButtonTouchArea(ViewMode.EDITOR, "Read")

    private fun assertModeButtonTouchArea(mode: ViewMode, label: String) {
        show(mode)
        val touch = button(label).fetchSemanticsNode().touchBoundsInRoot
        val density = composeRule.density.density
        assertTrue("touch width ${touch.width / density}dp must be >= 48dp", touch.width / density >= 48f - 0.01f)
        assertTrue("touch height ${touch.height / density}dp must be >= 48dp", touch.height / density >= 48f - 0.01f)
    }

    /**
     * A physical tap, not a semantics bound: a bar of [Column] { toolbar, 200dp clickable } is tapped at
     * [y] dp from the top, on the mode button's centre line. Compose does not route a tap outside the
     * parent Row and Column to a child, so this fails if the bar is shorter than the button's touch area.
     */
    private fun tapAt(mode: ViewMode, label: String, y: Float): Pair<List<ViewMode>, Boolean> {
        var belowTapped = false
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                Column {
                    PilcrowToolbar(
                        currentMode = mode,
                        onModeSelected = { modes += it },
                        onSave = {},
                        onUndo = if (mode == ViewMode.EDITOR) ({}) else null,
                        onRedo = if (mode == ViewMode.EDITOR) ({}) else null,
                    )
                    Box(Modifier.fillMaxWidth().height(200.dp).clickable { belowTapped = true })
                }
            }
        }
        val centerX = button(label).fetchSemanticsNode().boundsInRoot.center.x
        val density = composeRule.density.density
        composeRule.onRoot().performTouchInput { click(Offset(centerX, y * density)) }
        composeRule.waitForIdle()
        return modes.toList() to belowTapped
    }

    @Test
    fun readModeTapNearTheBottomOfTheButtonSwitchesToTheEditor() {
        val (switched, belowTapped) = tapAt(ViewMode.READER, "Edit", y = 46f)
        assertEquals(listOf(ViewMode.EDITOR), switched)
        assertTrue("the tap must not fall through to the content below the bar", !belowTapped)
    }

    @Test
    fun editModeTapNearTheBottomOfTheButtonSwitchesToTheReader() {
        val (switched, belowTapped) = tapAt(ViewMode.EDITOR, "Read", y = 46f)
        assertEquals(listOf(ViewMode.READER), switched)
        assertTrue("the tap must not fall through to the content below the bar", !belowTapped)
    }

    @Test
    fun aTapBelowTheBarGoesToTheContentNotTheModeButton() {
        val (switched, belowTapped) = tapAt(ViewMode.READER, "Edit", y = 52f)
        assertEquals(emptyList<ViewMode>(), switched)
        assertTrue("a tap below the bar belongs to the content below", belowTapped)
    }

    @Test
    fun longPressOnTheModeButtonShowsItsWord() {
        show(ViewMode.READER)
        button("Edit").performTouchInput { longClick() }
        composeRule.onNodeWithText("Edit").assertIsDisplayed()
    }
}
