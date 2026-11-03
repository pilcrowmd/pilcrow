// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHub #19: the toolbar's Save, Undo and Redo, and the formatting bar's Undo and Redo, are greyed
 * out (on screen, not hidden, and a tap does nothing) while there is nothing to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-night-xxhdpi")
class ToolbarUnsavedStateTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var taps = 0

    private fun showToolbar(saveEnabled: Boolean, canUndo: Boolean, canRedo: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                PilcrowToolbar(
                    currentMode = ViewMode.EDITOR,
                    onSave = { taps++ },
                    saveEnabled = saveEnabled,
                    onUndo = { taps++ },
                    onRedo = { taps++ },
                    undoRedo = UndoRedoState(canUndo, canRedo),
                )
            }
        }
    }

    private fun showFormattingBar(canUndo: Boolean, canRedo: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                FormattingBar(
                    onAction = {},
                    onUndo = { taps++ },
                    onRedo = { taps++ },
                    undoRedo = UndoRedoState(canUndo, canRedo),
                )
            }
        }
    }

    private fun button(label: String): SemanticsNodeInteraction = composeRule.onNodeWithContentDescription(label)

    @Test
    fun nothingToDoGreysOutSaveUndoAndRedo() {
        showToolbar(saveEnabled = false, canUndo = false, canRedo = false)

        listOf("Save", "Undo", "Redo").forEach { button(it).assertExists().assertIsNotEnabled().performClick() }
        assertEquals("a greyed-out button did something", 0, taps)
    }

    @Test
    fun eachButtonIsOnWhenItHasSomethingToDo() {
        showToolbar(saveEnabled = true, canUndo = true, canRedo = false)
        button("Save").assertIsEnabled()
        button("Undo").assertIsEnabled()
        button("Redo").assertIsNotEnabled()
    }

    @Test
    fun redoAloneIsOnAfterUndoingEverything() {
        showToolbar(saveEnabled = false, canUndo = false, canRedo = true)
        button("Undo").assertIsNotEnabled()
        button("Redo").assertIsEnabled().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun theFormattingBarsUndoAndRedoFollowTheSameState() {
        showFormattingBar(canUndo = false, canRedo = true)
        button("Undo").assertIsNotEnabled().performClick()
        assertEquals("a greyed-out Undo did something", 0, taps)
        button("Redo").assertIsEnabled()
    }
}
