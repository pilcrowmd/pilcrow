// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * M-353 / Essential Safeguard 2. A CRLF file is held in memory with LF only and written back with
 * "\r\n" by the ViewModel. Sora keeps a separator per line, so a "\r\n" that is PASTED (or committed
 * by an input method) stays in `editor.text.toString()`; the save then turned its "\n" into "\r\n"
 * again and wrote "\r\r\n" (measured on the emulator: 0d 0d 0a). [PilcrowCodeEditor] folds every
 * "\r\n" pair to "\n" as the text enters the editor, so the content callback never sees a CR there.
 *
 * Both entry paths are exercised: the editor's own Paste and an input method's `commitText`. What is
 * read back is `editor.text.toString()`, the string [MarkdownEditor] hands to `onContentChange`. A lone
 * "\r" is user content and must survive. A third path, an accessibility service's ACTION_SET_TEXT,
 * reaches Sora's `setText` and never `commitText`; it is covered too.
 *
 * The editor is not composed through [MarkdownEditor]: the fold has nothing to do with wrapping or
 * composition, so a plain editor with Sora's default line-break layout is enough. Sora builds that
 * layout on its own thread pool and refuses input (`isEditable` false, no InputConnection) until it is
 * done, so [acceptInput] waits for it. The text is read straight from the document: its
 * ContentChangeEvent is not reliably published on an editor that was never laid out (1 run in 3 had
 * none).
 */
@RunWith(RobolectricTestRunner::class)
class PilcrowCodeEditorFoldTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var editor: PilcrowCodeEditor

    @Before
    fun host() {
        editor = PilcrowCodeEditor(context)
        acceptInput()
        // Sora creates its InputConnection lazily, on the first onCreateInputConnection, as a real IME would.
        assertNotNull(editor.onCreateInputConnection(EditorInfo()))
    }

    // Sora refuses input (isEditable false, no InputConnection) until its layout has been built, and
    // inserting before that fails inside the layout. Wait on the state the editor publishes, as
    // MarkdownEditorPlainSwitchTest does for its styles.
    private fun acceptInput() {
        val deadline = System.currentTimeMillis() + 30_000
        while (!editor.isEditable && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("precondition: the editor must accept input", editor.isEditable)
    }

    private fun paste(text: String) {
        acceptInput()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
        editor.pasteText()
    }

    private fun imeCommit(text: String) {
        acceptInput()
        val connection = editor.onCreateInputConnection(EditorInfo())
        assertNotNull(connection)
        assertTrue("IME commitText must be accepted", connection!!.commitText(text, 1))
    }

    @Test
    fun pastedCrlfIsFoldedToLf() {
        paste("PASTE-A\r\nPASTE-B")

        val last = editor.text.toString()
        assertTrue("folded text expected, got ${last.escaped()}", last.contains("PASTE-A\nPASTE-B"))
        assertFalse("no CR may survive a pasted CRLF, got ${last.escaped()}", last.contains('\r'))
    }

    @Test
    fun imeCommittedCrlfIsFoldedToLf() {
        imeCommit("X\r\nY")

        val last = editor.text.toString()
        assertTrue("folded text expected, got ${last.escaped()}", last.contains("X\nY"))
        assertFalse("no CR may survive a committed CRLF, got ${last.escaped()}", last.contains('\r'))
    }

    @Test
    fun loneCrIsKeptOnPasteAndOnCommit() {
        paste("a\rb")
        assertEquals("a\rb", editor.text.toString())

        imeCommit("c\rd")
        assertEquals("a\rbc\rd", editor.text.toString())
    }

    @Test
    fun crBeforeCrlfKeepsItsCr() {
        // "\r\r\n" is a real CR followed by a CRLF; only the pair folds.
        paste("p\r\r\nq")
        assertEquals("p\r\nq", editor.text.toString())
    }

    @Test
    fun onePasteIsOneUndoStep() {
        paste("U1\r\nU2")
        assertTrue("paste must be undoable", editor.canUndo())

        editor.undo()

        assertEquals("one undo takes the whole paste back", "", editor.text.toString())
    }

    private fun accessibilitySetText(arguments: Bundle): Boolean {
        acceptInput()
        return editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun setTextArguments(text: String) = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }

    @Test
    fun accessibilitySetTextFoldsCrlfToLf() {
        assertTrue(accessibilitySetText(setTextArguments("one\r\ntwo\r\n")))

        assertEquals("one\ntwo\n", editor.text.toString())
    }

    @Test
    fun accessibilitySetTextKeepsALoneCr() {
        assertTrue(accessibilitySetText(setTextArguments("a\rb")))

        assertEquals("a\rb", editor.text.toString())
    }

    @Test
    fun accessibilitySetTextDoesNotMutateTheCallersBundle() {
        val arguments = setTextArguments("one\r\ntwo")

        accessibilitySetText(arguments)

        assertEquals(
            "one\r\ntwo",
            arguments.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString(),
        )
    }

    private fun String.escaped() = replace("\r", "\\r").replace("\n", "\\n")
}
