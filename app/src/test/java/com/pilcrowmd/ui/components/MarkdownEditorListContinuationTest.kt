// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
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
 * M-168 on a real Sora [CodeEditor]: Enter on a list line writes the next marker, and that edit is
 * ONE undo step of its own. The decision itself is pinned by `ListContinuationTest`; this pins the
 * key-event handling, the cursor and the undo history, through the same [continueListsOnEnter] that
 * `MarkdownEditor` installs.
 *
 * ## Why NATIVE graphics, and why not through `MarkdownEditor`
 *
 * The editor only accepts input once a background layout pass finishes, and that pass runs on a
 * static two-thread pool inside Sora. Under Robolectric, a WORD-WRAP layout (which `MarkdownEditor`
 * turns on) never finishes: both threads were caught spinning in the ICU word breaker, after which
 * every later editor in the same sandbox waits forever. Robolectric shares a sandbox, and so that
 * pool, between test classes with the same graphics mode, and the LEGACY-mode editor tests compose
 * `MarkdownEditor`. NATIVE mode is a separate sandbox in which nothing composes the editor, and the
 * editors here keep word wrap off. Do not compose `MarkdownEditor` in this class.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownEditorListContinuationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * An on-screen editor holding [text], with the handler installed and the cursor at
     * [line]:[column]. setText measures its lines on a background thread and the editor reports
     * itself NOT editable until that finishes (Sora ignores Enter meanwhile, and an insert before it
     * throws), so this waits for [CodeEditor.isEditable] before handing the editor over.
     */
    private fun editor(text: String, line: Int, column: Int): CodeEditor {
        val editor = CodeEditor(ApplicationProvider.getApplicationContext())
        compose.setContent { AndroidView(factory = { editor }, modifier = Modifier.fillMaxSize()) }
        compose.runOnIdle {
            editor.setText(text)
            editor.continueListsOnEnter()
        }
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

    private fun CodeEditor.pressEnter(keyCode: Int = KeyEvent.KEYCODE_ENTER) {
        onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun CodeEditor.assertCursor(line: Int, column: Int) {
        assertFalse("no selection", cursor.isSelected)
        assertEquals("cursor line", line, cursor.leftLine)
        assertEquals("cursor column", column, cursor.leftColumn)
    }

    @Test
    fun enterOnABulletWritesTheNextBulletAsOneUndoStep() {
        val editor = editor("- one", 0, 5)

        editor.pressEnter()

        assertEquals("- one\n- ", editor.text.toString())
        editor.assertCursor(1, 2)

        editor.undo()
        assertEquals("one undo restores the text before Enter", "- one", editor.text.toString())
        assertFalse("setText reset the history, so nothing is left to undo", editor.canUndo())
    }

    /**
     * The undo step must be the Enter's own, NOT merged into the typing before it. Sora merges an
     * insert that starts where the previous one ended (within 8 s) into one undo action, so without
     * the batch edit the typed "b" and the inserted "\n- " would undo together.
     */
    @Test
    fun theContinuationDoesNotMergeIntoTheTypingBeforeIt() {
        val editor = editor("- a", 0, 3)
        editor.commitText("b")
        assertEquals("precondition: typed", "- ab", editor.text.toString())

        editor.pressEnter()
        assertEquals("- ab\n- ", editor.text.toString())

        editor.undo()
        assertEquals("the first undo takes back only the Enter", "- ab", editor.text.toString())
        editor.undo()
        assertEquals("the second takes back the typing", "- a", editor.text.toString())
        assertFalse(editor.canUndo())
    }

    @Test
    fun numpadEnterContinuesTheListToo() {
        val editor = editor("- one", 0, 5)

        editor.pressEnter(KeyEvent.KEYCODE_NUMPAD_ENTER)

        assertEquals("- one\n- ", editor.text.toString())
        editor.assertCursor(1, 2)
    }

    @Test
    fun enterInTheMiddleOfAnItemSplitsItBehindTheNewMarker() {
        val editor = editor("1. foo bar", 0, 7)

        editor.pressEnter()

        assertEquals("1. foo \n2. bar", editor.text.toString())
        editor.assertCursor(1, 3)
    }

    @Test
    fun enterOnAnEmptyItemClearsTheLineAndEndsTheList() {
        val editor = editor("- a\n  - [x] ", 1, 8)

        editor.pressEnter()

        assertEquals("- a\n", editor.text.toString())
        editor.assertCursor(1, 0)

        editor.undo()
        assertEquals("- a\n  - [x] ", editor.text.toString())
        assertFalse(editor.canUndo())
    }

    @Test
    fun enterOnAPlainLineIsAnOrdinaryNewline() {
        val editor = editor("plain", 0, 5)

        editor.pressEnter()

        assertEquals("plain\n", editor.text.toString())
        editor.assertCursor(1, 0)
    }

    @Test
    fun enterOnAListLineInsideAFenceIsAnOrdinaryNewline() {
        val editor = editor("```\n- a", 1, 3)

        editor.pressEnter()

        assertEquals("```\n- a\n", editor.text.toString())
        editor.assertCursor(2, 0)
    }

    @Test
    fun enterWithASelectionIsLeftToTheEditor() {
        val editor = editor("- abc", 0, 0)
        editor.setSelectionRegion(0, 3, 0, 5)

        editor.pressEnter()

        assertEquals("the selection is replaced by a plain newline", "- a\n", editor.text.toString())
    }

    private companion object {
        const val EDITABLE_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
