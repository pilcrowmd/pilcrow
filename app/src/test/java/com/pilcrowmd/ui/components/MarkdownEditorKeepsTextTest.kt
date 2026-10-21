// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.testing.withRealSize
import com.pilcrowmd.viewmodel.DocumentId
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #15 / M-340: a trip to the reader and back must not cost the editor its undo history. The
 * hoisted editor outlives the trip; what used to lose the history was the factory calling `setText`,
 * which gives Sora a new `Content`. With the same [DocumentId] and the same text the factory keeps the
 * `Content` (its history, caret and scroll); for another document, even one with the same text, it
 * must not (two documents never share an undo history).
 *
 * The edit is typed through the editor's own text, as `MarkdownEditorSyntaxHighlightingTest` does,
 * and the editor is given a real size first (see [withRealSize]).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownEditorKeepsTextTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hoisted = AccessibleCodeEditor(ApplicationProvider.getApplicationContext()).withRealSize()
    private var mode by mutableStateOf(ViewMode.EDITOR)
    private var documentId by mutableStateOf(DocumentId.mint())
    private var text by mutableStateOf("# Title\n\nText\n")

    private var caret by mutableStateOf(0)

    /** The editor in the Edit mode, as MainScreen holds it: text, caret and document come from state. */
    private fun showEditorInEditMode() {
        compose.setContent {
            if (mode == ViewMode.EDITOR) {
                MarkdownEditor(
                    content = text,
                    onContentChange = { text = it },
                    codeEditorInstance = hoisted,
                    documentId = documentId,
                    initialCursor = caret,
                    onCursorChange = { caret = it },
                )
            }
        }
        compose.waitForIdle()
    }

    /** The editor on screen with a typed edit in its undo history. */
    private fun editorWithAnEdit() {
        showEditorInEditMode()
        assertFalse("precondition: nothing to undo yet", hoisted.canUndo())

        compose.runOnUiThread { hoisted.text.insert(0, 0, "x") }
        compose.waitForIdle()

        assertEquals("precondition: the edit reached the text", "x# Title\n\nText\n", text)
        assertTrue("precondition: the edit is in the undo history", hoisted.canUndo())
    }

    private fun toReaderAndBack(whileAway: () -> Unit = {}) {
        mode = ViewMode.READER
        compose.waitForIdle()
        whileAway()
        mode = ViewMode.EDITOR
        compose.waitForIdle()
    }

    @Test
    fun theSameDocumentComesBackWithItsUndoHistory() {
        editorWithAnEdit()
        val contentBefore = hoisted.text

        toReaderAndBack()

        assertSame("the editor kept its Content", contentBefore, hoisted.text)
        assertTrue("and so its undo history", hoisted.canUndo())
    }

    @Test
    fun anotherDocumentWithTheSameTextDoesNotInheritTheUndoHistory() {
        editorWithAnEdit()

        toReaderAndBack { documentId = DocumentId.mint() }

        assertEquals("the text is the same", "x# Title\n\nText\n", hoisted.text.toString())
        assertFalse("but the history is another document's", hoisted.canUndo())
    }

    @Test
    fun theCaretAndTheScrollComeBackWhereTheyWereLeft() {
        text = (0 until 300).joinToString("\n", postfix = "\n") { "line $it" }
        showEditorInEditMode()
        compose.runOnUiThread {
            hoisted.setSelection(150, 2)
            // Sora reads its scroll position from the scroller; finish the move now.
            hoisted.scroller.startScroll(0, 0, 0, SCROLLED_TO, 0)
            hoisted.scroller.computeScrollOffset()
            hoisted.scroller.forceFinished(true)
        }
        compose.waitForIdle()
        val caretBefore = hoisted.cursor.left
        val scrollBefore = hoisted.offsetY
        assertEquals("precondition: the caret is where it was put", text.indexOf("line 150") + 2, caretBefore)
        assertTrue("precondition: the editor is scrolled ($scrollBefore)", scrollBefore > 0)

        toReaderAndBack()

        assertEquals("the caret", caretBefore, hoisted.cursor.left)
        assertEquals("the scroll", scrollBefore, hoisted.offsetY)
    }

    private companion object {
        const val SCROLLED_TO = 900
    }
}
