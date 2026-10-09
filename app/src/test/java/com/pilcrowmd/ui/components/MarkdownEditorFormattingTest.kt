// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.FormatAction
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-217 on a real Sora [CodeEditor]: a formatting-bar tap edits through the editor's own text, lands
 * the selection where the rules say, and is ONE undo step of its own. The rules themselves are
 * pinned by `MarkdownFormattingTest`; this pins [applyFormat].
 *
 * NATIVE graphics with word wrap off, and never through `MarkdownEditor`, for the reason
 * `MarkdownEditorListContinuationTest` gives: a word-wrap layout never finishes under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownEditorFormattingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** An on-screen, editable editor holding [text] with the cursor at [line]:[column]. */
    private fun editor(text: String, line: Int, column: Int): CodeEditor {
        val editor = CodeEditor(ApplicationProvider.getApplicationContext())
        compose.setContent { AndroidView(factory = { editor }, modifier = Modifier.fillMaxSize()) }
        compose.runOnIdle { editor.setText(text) }
        awaitEditable(editor)
        editor.setSelection(line, column)
        return editor
    }

    /** Polls, idling the main looper, which is where Sora posts the end of its measuring. */
    private fun awaitEditable(editor: CodeEditor) {
        val deadline = System.currentTimeMillis() + EDITABLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (editor.isEditable) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the editor never became editable")
    }

    /**
     * The closing `**` is inserted right where the typing ended, which is exactly the shape Sora
     * merges into the typing's undo action; the opening `**` is a second action. Without the batch
     * edit, one undo would leave `ab**`.
     */
    @Test
    fun boldIsOneUndoStepAndTheTypingBeforeItStays() {
        val editor = editor("a", 0, 1)
        editor.commitText("b")
        assertEquals("precondition: typed", "ab", editor.text.toString())
        editor.setSelectionRegion(0, 0, 0, 2)

        editor.applyFormat(FormatAction.Bold)

        assertEquals("**ab**", editor.text.toString())
        assertEquals("the same text stays selected (start)", 2, editor.cursor.left)
        assertEquals("the same text stays selected (end)", 4, editor.cursor.right)

        editor.undo()
        assertEquals("one undo takes back the whole tap", "ab", editor.text.toString())
        editor.undo()
        assertEquals("the second takes back the typing", "a", editor.text.toString())
        assertFalse(editor.canUndo())
    }

    @Test
    fun aLineActionOverTwoLinesIsOneUndoStep() {
        val editor = editor("a\nb", 0, 0)
        editor.setSelectionRegion(0, 0, 1, 1)

        editor.applyFormat(FormatAction.Quote)

        assertEquals("> a\n> b", editor.text.toString())
        editor.undo()
        assertEquals("one undo restores both lines", "a\nb", editor.text.toString())
        assertFalse(editor.canUndo())
    }

    @Test
    fun noSelectionInsertsThePairWithTheCursorBetween() {
        val editor = editor("ab", 0, 1)

        editor.applyFormat(FormatAction.Strikethrough)

        assertEquals("a~~~~b", editor.text.toString())
        assertFalse("no selection", editor.cursor.isSelected)
        assertEquals(3, editor.cursor.left)
    }

    private companion object {
        const val EDITABLE_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
