// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.testing.withRealSize
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.DocumentId
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * GitHub #19: the toolbar's Undo and Redo follow the real editor's history. Both are greyed out on a
 * freshly opened file; an edit turns Undo on; undoing everything greys Undo and turns Redo on; redoing
 * everything greys Redo again; another file, or a reload of this one, greys both.
 *
 * The editor and the toolbar are composed as MainScreen composes them (the hoisted editor, its state
 * read through [rememberUndoRedoState]), and every step goes through the toolbar's own buttons. Edits
 * are typed through the editor's text, as [MarkdownEditorKeepsTextTest] does, on an editor given a
 * real size first ([withRealSize]).
 */
@RunWith(RobolectricTestRunner::class)
class UndoRedoStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = "# Title\n\nText\n"
    private val hoisted = AccessibleCodeEditor(ApplicationProvider.getApplicationContext()).withRealSize()
    private var documentId by mutableStateOf(DocumentId.mint())
    private var text by mutableStateOf(opened)

    private fun showEditorAndToolbar() {
        compose.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                val history by rememberUndoRedoState(hoisted)
                Column {
                    PilcrowToolbar(
                        currentMode = ViewMode.EDITOR,
                        onUndo = { hoisted.undo() },
                        onRedo = { hoisted.redo() },
                        undoRedo = history,
                    )
                    // Keyed on the document as MainScreen keys it: another id rebuilds the node.
                    key(documentId) {
                        MarkdownEditor(
                            content = text,
                            onContentChange = { text = it },
                            codeEditorInstance = hoisted,
                            documentId = documentId,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun button(label: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(label)

    private fun type(at: Int, what: String) {
        compose.runOnUiThread { hoisted.text.insert(0, at, what) }
        compose.waitForIdle()
    }

    /** Taps [label] until it greys out; returns how many taps that took. */
    private fun tapUntilGreyedOut(label: String): Int {
        var taps = 0
        while (taps < MAX_TAPS && hoisted.let { if (label == "Undo") it.canUndo() else it.canRedo() }) {
            button(label).performClick()
            compose.waitForIdle()
            taps++
        }
        return taps
    }

    @Test
    fun aFreshlyOpenedFileHasNothingToUndoOrRedo() {
        showEditorAndToolbar()
        button("Undo").assertIsNotEnabled()
        button("Redo").assertIsNotEnabled()
    }

    @Test
    fun anEditTurnsUndoOnAndLeavesRedoOff() {
        showEditorAndToolbar()
        type(0, "x")
        assertEquals("precondition: the edit reached the text", "x$opened", text)

        button("Undo").assertIsEnabled()
        button("Redo").assertIsNotEnabled()
    }

    @Test
    fun undoingEverythingGreysUndoAndRedoingEverythingGreysRedo() {
        showEditorAndToolbar()
        type(0, "x")
        type(1, "y")
        val edited = text

        assertTrue("nothing was undone", tapUntilGreyedOut("Undo") > 0)
        assertEquals("precondition: back at the opened text", opened, text)
        button("Undo").assertIsNotEnabled()
        button("Redo").assertIsEnabled()

        assertTrue("nothing was redone", tapUntilGreyedOut("Redo") > 0)
        assertEquals("precondition: at the newest edit again", edited, text)
        button("Redo").assertIsNotEnabled()
        button("Undo").assertIsEnabled()
    }

    @Test
    fun anotherFileGreysBoth() {
        showEditorAndToolbar()
        type(0, "x")
        button("Undo").performClick()
        compose.waitForIdle()
        button("Redo").assertIsEnabled()
        type(0, "z")
        button("Undo").assertIsEnabled()

        documentId = DocumentId.mint()
        text = "# Another\n"
        compose.waitForIdle()

        button("Undo").assertIsNotEnabled()
        button("Redo").assertIsNotEnabled()
    }

    /** A reload publishes the file's text under a new document id, even when the text is the same. */
    @Test
    fun aReloadGreysBoth() {
        showEditorAndToolbar()
        type(0, "x")
        button("Undo").performClick()
        compose.waitForIdle()
        assertEquals("precondition: back at the opened text", opened, text)
        button("Redo").assertIsEnabled()

        documentId = DocumentId.mint()
        compose.waitForIdle()

        button("Undo").assertIsNotEnabled()
        button("Redo").assertIsNotEnabled()
    }

    private companion object {
        const val MAX_TAPS = 20
    }
}
