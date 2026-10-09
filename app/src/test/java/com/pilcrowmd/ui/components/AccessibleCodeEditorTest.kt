// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.event.SelectionChangeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-265: what [AccessibleCodeEditor] tells TalkBack. Everything here is synchronous: Sora fires its
 * content and selection events inline, and the editor sends its accessibility events from inside
 * them, so each assertion reads state that the call before it has already published.
 *
 * Events are observed through the view's public [View.AccessibilityDelegate], the same hook any
 * host can install; only the three text event types the editor sends are recorded, so a focus
 * event the framework might add cannot satisfy or spoil an assertion.
 */
@RunWith(RobolectricTestRunner::class)
class AccessibleCodeEditorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<AccessibilityEvent>()

    private val recorder = object : View.AccessibilityDelegate() {
        override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) {
            if (event.eventType in TEXT_EVENT_TYPES) events += event
        }
    }

    /** An editor holding [text], cursor at [cursor], with the recorder installed and empty. */
    private fun editor(text: String, cursor: Int = 0, talkBack: Boolean = true): AccessibleCodeEditor {
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).setTouchExplorationEnabled(talkBack)
        val editor = AccessibleCodeEditor(context)
        editor.setText(text)
        editor.awaitLayout()
        val position = editor.text.indexer.getCharPosition(cursor)
        editor.setSelection(position.line, position.column)
        editor.accessibilityDelegate = recorder
        events.clear()
        return editor
    }

    /**
     * Barrier. After `setText` Sora measures line widths on a background thread, and an edit that
     * lands before that task starts crashes in `LineBreakLayout.afterInsert` (seen here, flaky). The
     * first published width means the task has started, and it holds the content's read lock until
     * it ends, so the next edit's write lock waits for it. That only holds for a thread-safe
     * `Content`, which is checked rather than assumed.
     */
    private fun AccessibleCodeEditor.awaitLayout() {
        assertTrue("barrier needs Sora's locking Content", text.isThreadSafe)
        val deadline = System.nanoTime() + LAYOUT_TIMEOUT_NANOS
        while (layout.layoutWidth == UNMEASURED_LAYOUT_WIDTH) {
            assertTrue("Sora never measured the text", System.nanoTime() < deadline)
            Thread.sleep(1)
        }
    }

    /**
     * Barrier. Sora refuses keyboard and IME input while its layout is busy (`isEditable` is
     * false); the measuring task clears that by posting to the main looper once it ends.
     */
    private fun AccessibleCodeEditor.awaitIdleLayout() {
        val deadline = System.nanoTime() + LAYOUT_TIMEOUT_NANOS
        while (!isEditable) {
            assertTrue("Sora's layout stayed busy", System.nanoTime() < deadline)
            idleMainLooper()
            Thread.sleep(1)
        }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun only(type: Int): AccessibilityEvent = events.single { it.eventType == type }

    private fun AccessibleCodeEditor.move(granularity: Int, forward: Boolean, extend: Boolean = false): Boolean {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity)
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
        }
        val action = if (forward) NEXT else PREVIOUS
        return performAccessibilityAction(action, arguments)
    }

    // --- Node info -------------------------------------------------------------------------------

    @Test
    fun nodeIsAnEditBoxNamedAfterTheFileWithReadingControls() {
        val editor = editor("# Title")
        editor.fileName = "notes.md"
        // View sets the node's class name only on an attached view, which is all TalkBack sees.
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(editor)

        val info = editor.createAccessibilityNodeInfo()

        assertEquals("android.widget.EditText", info.className)
        assertEquals("Markdown text, notes.md", info.hintText)
        assertEquals(
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH,
            info.movementGranularities,
        )
        val actions = info.actionList
        assertTrue(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY in actions)
        assertTrue(AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY in actions)
        assertTrue(AccessibilityAction.ACTION_SET_SELECTION in actions)
    }

    @Test
    fun aBlankFileNameLeavesTheBareName() {
        val editor = editor("")
        editor.fileName = "  "

        assertEquals("Markdown text", editor.createAccessibilityNodeInfo().hintText)
    }

    // --- Typing echo -----------------------------------------------------------------------------

    @Test
    fun insertSendsTextChangedLikeATextView() {
        val editor = editor("hello", cursor = 2)

        editor.commitText("a")

        val changed = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(2, changed.fromIndex)
        assertEquals(1, changed.addedCount)
        assertEquals(0, changed.removedCount)
        assertEquals("hello", changed.beforeText.toString())
        assertEquals("heallo", changed.text.single().toString())
        assertEquals("android.widget.EditText", changed.className)
    }

    /**
     * A lone delete is held until the main loop turns, in case an insert joins it as a replace.
     * The looper is paused under Robolectric, so nothing arrives until it is idled here; without
     * the flush nothing would ever arrive.
     */
    @Test
    fun aLoneDeleteArrivesWhenTheLoopTurnsThenItsCursorMove() {
        val editor = editor("hello", cursor = 3)

        editor.deleteText()
        assertEquals("held until the loop turns", emptyList<AccessibilityEvent>(), events)
        idleMainLooper()

        assertEquals(
            listOf(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED),
            events.map { it.eventType },
        )
        val changed = events[0]
        assertEquals(2, changed.fromIndex)
        assertEquals(0, changed.addedCount)
        assertEquals(1, changed.removedCount)
        assertEquals("hello", changed.beforeText.toString())
        assertEquals("helo", changed.text.single().toString())
        assertEquals(2 to 2, events[1].fromIndex to events[1].toIndex)
    }

    /** A delete still held when the editor leaves the screen is dropped, not spoken later. */
    @Test
    fun aDeleteHeldWhenTheEditorDetachesIsNeverSpoken() {
        val editor = editor("hello", cursor = 3)
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(editor)
        idleMainLooper()
        events.clear()

        editor.deleteText()
        assertEquals("helo", editor.text.toString())
        assertEquals("held until the loop turns", emptyList<AccessibilityEvent>(), events)
        (editor.parent as ViewGroup).removeView(editor)
        idleMainLooper()

        assertEquals(emptyList<AccessibilityEvent>(), events)
    }

    // --- Replace: Sora's delete + insert, spoken as one change like TextView's -----------------

    @Test
    fun aReplaceIsOneTextChange() {
        val editor = editor("hello world", cursor = 0)

        editor.text.replace(0, 6, 0, 11, "there")
        idleMainLooper()

        val changed = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(6, changed.fromIndex)
        assertEquals(5, changed.removedCount)
        assertEquals(5, changed.addedCount)
        assertEquals("hello world", changed.beforeText.toString())
        assertEquals("hello there", changed.text.single().toString())
    }

    @Test
    fun typingOverASelectionIsOneChangeAndOneCursorMove() {
        val editor = editor("hello", cursor = 0)
        editor.setSelectionRegion(0, 1, 0, 3, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)
        events.clear()

        editor.commitText("X")
        idleMainLooper()

        assertEquals("hXlo", editor.text.toString())
        val changed = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(1, changed.fromIndex)
        assertEquals(2, changed.removedCount)
        assertEquals(1, changed.addedCount)
        assertEquals("hello", changed.beforeText.toString())
        val selection = only(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        assertEquals(2 to 2, selection.fromIndex to selection.toIndex)
    }

    /**
     * What Gboard sends for each keystroke inside a word: the whole composing word again. Sora
     * appends when the new word only extends the old one (a plain insert), and otherwise replaces
     * the whole composing word: a delete plus an insert, which must be heard as one change.
     */
    @Test
    fun anImeComposingKeystrokeIsOneChange() {
        val editor = editor("", cursor = 0)
        editor.awaitIdleLayout()
        val input = editor.onCreateInputConnection(EditorInfo())
        input.setComposingText("hel", 1)
        idleMainLooper()
        events.clear()

        input.setComposingText("hell", 1)
        idleMainLooper()
        val added = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(listOf(3, 0, 1), listOf(added.fromIndex, added.removedCount, added.addedCount))
        assertEquals("hel", added.beforeText.toString())
        events.clear()

        input.setComposingText("help", 1)
        idleMainLooper()
        assertEquals("help", editor.text.toString())
        val replaced = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(listOf(0, 4, 4), listOf(replaced.fromIndex, replaced.removedCount, replaced.addedCount))
        assertEquals("hell", replaced.beforeText.toString())
    }

    @Test
    fun undoingAnInsertIsADeleteAndUndoingAReplaceIsOneChange() {
        val editor = editor("hello", cursor = 5)
        editor.commitText("!")
        idleMainLooper()
        events.clear()

        editor.undo()
        idleMainLooper()
        val undoneInsert = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(1 to 0, undoneInsert.removedCount to undoneInsert.addedCount)
        assertEquals("hello!", undoneInsert.beforeText.toString())
        events.clear()

        editor.text.replace(0, 0, 0, 5, "world")
        idleMainLooper()
        events.clear()
        editor.undo()
        idleMainLooper()

        assertEquals("hello", editor.text.toString())
        val undoneReplace = only(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        assertEquals(5 to 5, undoneReplace.removedCount to undoneReplace.addedCount)
        assertEquals("world", undoneReplace.beforeText.toString())
    }

    // --- Cursor and selection --------------------------------------------------------------------

    @Test
    fun typingMovesTheCursorAndSaysSo() {
        val editor = editor("hello", cursor = 2)

        editor.commitText("a")

        val selection = only(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        assertEquals(3, selection.fromIndex)
        assertEquals(3, selection.toIndex)
        assertEquals(6, selection.itemCount)
    }

    private fun selections() = events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED }
        .map { it.fromIndex to it.toIndex }

    /**
     * What Sora's Shift+arrow handler (`extendSelection`) does once it has the new caret: it calls
     * `setSelectionRegion(anchor, target, false, CAUSE_KEYBOARD_OR_CODE)` with the fixed end FIRST,
     * even when it is the greater one. Called here directly because the step before it, finding
     * the next caret position, goes through Android's text layout, which Robolectric stubs to
     * column 0.
     */
    private fun AccessibleCodeEditor.shiftArrowTo(anchor: Int, target: Int) =
        setSelectionRegion(0, anchor, 0, target, false, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)

    /**
     * TalkBack speaks a Shift+arrow selection only when fromIndex stays on the fixed end and
     * toIndex follows the moving one, as TextView reports it; extending left therefore has
     * fromIndex > toIndex. A left <= right pair is logged by TalkBack as "Unhandled selection
     * event" and said nothing.
     */
    @Test
    fun shiftArrowLeftReportsTheFixedEndFirst() {
        val editor = editor("hello world", cursor = 5)

        editor.shiftArrowTo(anchor = 5, target = 4)
        editor.shiftArrowTo(anchor = 5, target = 3)

        assertEquals(3 to 5, editor.cursor.left to editor.cursor.right)
        assertEquals(listOf(5 to 4, 5 to 3), selections())
    }

    @Test
    fun shiftArrowRightReportsTheFixedEndFirst() {
        val editor = editor("hello world", cursor = 5)

        (6..8).forEach { editor.shiftArrowTo(anchor = 5, target = it) }

        assertEquals(listOf(5 to 6, 5 to 7, 5 to 8), selections())
    }

    @Test
    fun collapsingASelectionReportsTheCaretTwice() {
        val editor = editor("hello world", cursor = 5)
        editor.shiftArrowTo(anchor = 5, target = 4)
        events.clear()

        editor.setSelection(0, 2, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)

        assertEquals(listOf(2 to 2), selections())
    }

    /** Sora's Ctrl+A handler calls selectAll(); from a caret mid-text it must still say (0, length). */
    @Test
    fun ctrlASaysTheWholeTextIsSelected() {
        val editor = editor("hello\nworld", cursor = 3)
        editor.awaitIdleLayout()

        val ctrlA = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)
        editor.onKeyDown(KeyEvent.KEYCODE_A, ctrlA)

        assertEquals(0 to 11, editor.cursor.left to editor.cursor.right)
        assertEquals(listOf(0 to 11), selections())
        events.clear()

        editor.setSelection(0, 3)
        editor.selectAll()
        assertEquals(listOf(0 to 11), selections())
    }

    // --- Silence ---------------------------------------------------------------------------------

    /** What Editor.kt does on a file load and on Reader to Editor: setText, then setSelection. */
    @Test
    fun fileLoadAndCursorRestoreAreSilentWithTalkBackOn() {
        val editor = editor("")

        editor.setText("# Notes\n\nA whole document that must not be read out as typing.\n")
        editor.setSelection(2, 5)

        assertEquals(emptyList<AccessibilityEvent>(), events)
        // Control: the same editor does speak, so the silence above is not a dead recorder.
        assertTrue(editor.move(CHARACTER, forward = true))
        assertTrue(events.any { it.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY })
    }

    @Test
    fun nothingIsSentWithTalkBackOff() {
        val editor = editor("hello", cursor = 2, talkBack = false)

        editor.commitText("a")
        editor.deleteText()
        editor.setSelection(0, 4, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)
        assertTrue(editor.move(WORD, forward = false))
        idleMainLooper()

        assertEquals("hello", editor.text.toString())
        assertEquals(0, editor.cursor.left)
        assertEquals(emptyList<AccessibilityEvent>(), events)
    }

    @Test
    fun nothingIsSentForATextOverSorasAccessibilityCap() {
        val editor = editor("twenty characters ok", cursor = 2)
        editor.props.maxAccessibilityTextLength = 10

        editor.commitText("a")
        editor.setSelection(0, 6, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)
        assertTrue(editor.move(WORD, forward = true))

        assertEquals(emptyList<AccessibilityEvent>(), events)
    }

    // --- Reading controls ------------------------------------------------------------------------

    private fun AccessibleCodeEditor.assertTraversed(from: Int, to: Int, granularity: Int, forward: Boolean) {
        val traversed = only(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY)
        assertEquals("from", from, traversed.fromIndex)
        assertEquals("to", to, traversed.toIndex)
        assertEquals(granularity, traversed.movementGranularity)
        assertEquals(if (forward) NEXT else PREVIOUS, traversed.action)
        assertEquals(text.toString(), traversed.text.single().toString())
        events.clear()
    }

    private fun AccessibleCodeEditor.assertCursor(index: Int) {
        assertEquals("left", index, cursor.left)
        assertEquals("right", index, cursor.right)
    }

    @Test
    fun characterStepsOneCharacterEachWay() {
        val editor = editor(DOC, cursor = 0)

        assertTrue(editor.move(CHARACTER, forward = true))
        editor.assertTraversed(0, 1, CHARACTER, forward = true)
        editor.assertCursor(1)

        assertTrue(editor.move(CHARACTER, forward = false))
        editor.assertTraversed(0, 1, CHARACTER, forward = false)
        editor.assertCursor(0)
    }

    @Test
    fun wordSkipsSpacesAndPunctuation() {
        val editor = editor(DOC, cursor = 0)

        assertTrue(editor.move(WORD, forward = true))
        editor.assertTraversed(0, 3, WORD, forward = true)
        editor.assertCursor(3)
        assertTrue(editor.move(WORD, forward = true))
        editor.assertTraversed(4, 7, WORD, forward = true)
        editor.assertCursor(7)

        assertTrue(editor.move(WORD, forward = false))
        editor.assertTraversed(4, 7, WORD, forward = false)
        editor.assertCursor(4)
    }

    @Test
    fun lineIsATextLineIncludingItsBreak() {
        val editor = editor(DOC, cursor = 0)

        assertTrue(editor.move(LINE, forward = true))
        editor.assertTraversed(0, 9, LINE, forward = true)
        editor.assertCursor(9)
        assertTrue(editor.move(LINE, forward = true))
        editor.assertTraversed(9, 20, LINE, forward = true)
        editor.assertCursor(20)

        assertTrue(editor.move(LINE, forward = false))
        editor.assertTraversed(9, 20, LINE, forward = false)
        editor.assertCursor(9)
    }

    @Test
    fun paragraphRunsBetweenBlankLines() {
        val editor = editor(DOC, cursor = 0)

        assertTrue(editor.move(PARAGRAPH, forward = true))
        editor.assertTraversed(0, 19, PARAGRAPH, forward = true)
        editor.assertCursor(19)
        assertTrue(editor.move(PARAGRAPH, forward = true))
        editor.assertTraversed(21, 29, PARAGRAPH, forward = true)
        editor.assertCursor(29)

        assertTrue(editor.move(PARAGRAPH, forward = false))
        editor.assertTraversed(21, 29, PARAGRAPH, forward = false)
        editor.assertCursor(21)
    }

    @Test
    fun extendingKeepsTheAnchorInBothDirections() {
        val editor = editor(DOC, cursor = 0)
        editor.move(WORD, forward = true, extend = true)
        editor.move(WORD, forward = true, extend = true)
        assertEquals(0 to 7, editor.cursor.left to editor.cursor.right)
        editor.move(WORD, forward = false, extend = true)
        assertEquals(0 to 4, editor.cursor.left to editor.cursor.right)

        val backwards = editor(DOC, cursor = 7)
        backwards.move(WORD, forward = false, extend = true)
        assertEquals(4 to 7, backwards.cursor.left to backwards.cursor.right)
        backwards.move(WORD, forward = false, extend = true)
        assertEquals(0 to 7, backwards.cursor.left to backwards.cursor.right)
        assertEquals("the fixed end is reported first", listOf(7 to 4, 7 to 0), selections())
    }

    @Test
    fun theDocumentEdgesRefuseToMove() {
        val atEnd = editor(DOC, cursor = DOC.length)
        assertFalse(atEnd.move(CHARACTER, forward = true))
        assertFalse(atEnd.move(LINE, forward = true))

        val atStart = editor(DOC, cursor = 0)
        assertFalse(atStart.move(WORD, forward = false))
        assertFalse(atStart.move(PARAGRAPH, forward = false))
        assertEquals(emptyList<AccessibilityEvent>(), events)
    }

    @Test
    fun setSelectionSelectsAndClampsToTheText() {
        val editor = editor(DOC, cursor = 0)

        assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection(4, 7)))
        assertEquals(4 to 7, editor.cursor.left to editor.cursor.right)
        val selected = only(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
        assertEquals(4 to 7, selected.fromIndex to selected.toIndex)

        assertTrue(editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection(21, 999)))
        assertEquals(21 to DOC.length, editor.cursor.left to editor.cursor.right)
    }

    private fun selection(start: Int, end: Int) = Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
    }

    private companion object {
        // "One two.\n" is 0..9, "Three four\n" 9..20, a blank line at 20, "Five six" 21..29.
        const val DOC = "One two.\nThree four\n\nFive six"

        // What LineBreakLayout.getLayoutWidth() returns before any line has been measured.
        const val UNMEASURED_LAYOUT_WIDTH = 214_748_364
        const val LAYOUT_TIMEOUT_NANOS = 5_000_000_000L

        const val CHARACTER = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
        const val WORD = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
        const val LINE = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
        const val PARAGRAPH = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
        const val NEXT = AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        const val PREVIOUS = AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY

        val TEXT_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
        )
    }
}
