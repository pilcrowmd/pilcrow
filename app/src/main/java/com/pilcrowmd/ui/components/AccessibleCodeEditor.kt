// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.pilcrowmd.R
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways
import java.text.BreakIterator

private const val EDIT_TEXT_CLASS_NAME = "android.widget.EditText"

private const val GRANULARITIES = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD or
    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE or
    AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH

/**
 * The app's editor: Sora's [CodeEditor] plus what TalkBack needs from a text box (M-265,
 * `docs/proposals/editor-talkback.md` option (a)). Drawing, input, undo and the text buffer stay
 * Sora's; this class only reports to accessibility services and answers their navigation actions.
 * It never changes the text, so edits still leave only through Sora's `ContentChangeEvent`.
 *
 * Events are sent only while touch exploration is on and the text fits Sora's own
 * `maxAccessibilityTextLength`, so nobody else pays for them. Two things stay silent on purpose: a
 * whole-text replace (`setText`, i.e. a file load or the Reader to Editor switch) and a selection
 * set with Sora's unknown cause (the app's cursor restore after a load). Echoing either would read
 * the whole document out as if it had been typed.
 *
 * It extends [PilcrowCodeEditor], so it inherits the CRLF fold on every insertion (M-353).
 */
class AccessibleCodeEditor(context: Context) : PilcrowCodeEditor(context) {

    /** Spoken after the editor's name, e.g. "Markdown text, notes.md". */
    var fileName: String = ""

    // The selection as TextView reports it: (fixed end, moving end), so the first can be greater.
    // TalkBack speaks a keyboard selection only while fromIndex stays on the fixed end. Sora keeps
    // that end in `selectionAnchor` and leaves it at one end of every selection it makes (its
    // setSelectionRegion moves it to the right end otherwise); with no selection both are the caret.
    private val selectionEnds: Pair<Int, Int>
        get() {
            val anchoredRight = cursor.isSelected && selectionAnchor?.index == cursor.right
            return if (anchoredRight) cursor.right to cursor.left else cursor.left to cursor.right
        }

    // A delete waiting to learn whether it is half of a replace (see onTextChanged).
    private class PendingDelete(val start: Int, val removedCount: Int, val before: String)

    private var pendingDelete: PendingDelete? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Announces a lone delete at the end of the main-loop turn it happened in, then the cursor
    // move that was held back with it (TextView's order).
    private val flushPendingDelete: Runnable = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            val pending = pendingDelete ?: return
            pendingDelete = null
            val after = pending.before.removeRange(pending.start, pending.start + pending.removedCount)
            announceTextChange(pending.start, pending.removedCount, 0, pending.before, after)
            speak(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) { current ->
                fromIndex = selectionEnds.first
                toIndex = selectionEnds.second
                itemCount = current.length
            }
        }
    }

    init {
        subscribeAlways<ContentChangeEvent> { event -> onTextChanged(event) }
        subscribeAlways<SelectionChangeEvent> { event ->
            when {
                event.cause == SelectionChangeEvent.CAUSE_UNKNOWN -> Unit
                pendingDelete == null -> speak(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) { current ->
                    fromIndex = selectionEnds.first
                    toIndex = selectionEnds.second
                    itemCount = current.length
                }
                // The delete's own cursor move waits for it; anything else flushes it, and the
                // flush announces the cursor this event reports.
                event.cause != SelectionChangeEvent.CAUSE_TEXT_MODIFICATION -> flushPendingDelete.run()
            }
        }
    }

    // TalkBack gives the edit-box role only to a class that resolves to EditText.
    override fun getAccessibilityClassName(): CharSequence = EDIT_TEXT_CLASS_NAME

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo {
        val info = super.createAccessibilityNodeInfo()
        val name = context.getString(R.string.editor_accessibility_name)
        info.hintText = if (fileName.isBlank()) name else "$name, $fileName"
        // Same condition under which Sora adds its text and editing actions.
        if (isEnabled && props.maxAccessibilityTextLength > 0) {
            info.movementGranularities = GRANULARITIES
            info.addAction(AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
            info.addAction(AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
            info.addAction(AccessibilityAction.ACTION_SET_SELECTION)
        }
        return info
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> traverse(arguments, forward = true)
        AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> traverse(arguments, forward = false)
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> {
            val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) ?: -1
            val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) ?: -1
            val valid = start >= 0 && end >= 0
            if (valid) select(anchor = start.coerceAtMost(text.length), focus = end.coerceAtMost(text.length))
            valid
        }
        else -> super.performAccessibilityAction(action, arguments)
    }

    // Sora's own selectAll (also what its Ctrl+A key handler calls) uses the unknown cause, which
    // this class keeps silent, and leaves the anchor wherever the caret was. Same region, anchored
    // at the start, known cause: announced as (0, length).
    override fun selectAll() = select(anchor = 0, focus = text.length)

    // Off screen, a held delete is dropped rather than spoken, and its flush is not left posted.
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(flushPendingDelete)
        pendingDelete = null
    }

    // Sora reports a replace (an IME composing keystroke, typing over a selection, autocorrect,
    // undoing a replace) as a DELETE and then an INSERT at the same place. TextView reports it as
    // one change, and TalkBack would otherwise speak the deleted text and then the whole new word.
    // So a delete is held until the end of this main-loop turn, and an insert at its start joins
    // it. Everything else flushes the held delete first. The text is already changed when Sora
    // fires, so the before-text is rebuilt from it.
    private fun onTextChanged(event: ContentChangeEvent) {
        if (!canSpeak) {
            pendingDelete = null
            return
        }
        val start = event.changeStart.index
        val changed = event.changedText
        val current = text.toString()
        val pending = pendingDelete
        if (event.action == ContentChangeEvent.ACTION_INSERT && pending != null && pending.start == start) {
            pendingDelete = null
            mainHandler.removeCallbacks(flushPendingDelete)
            announceTextChange(start, pending.removedCount, changed.length, pending.before, current)
            return
        }
        flushPendingDelete.run()
        when (event.action) {
            ContentChangeEvent.ACTION_INSERT -> {
                val before = current.removeRange(start, start + changed.length)
                announceTextChange(start, 0, changed.length, before, current)
            }
            ContentChangeEvent.ACTION_DELETE -> {
                val before = StringBuilder(current).insert(start, changed).toString()
                pendingDelete = PendingDelete(start, changed.length, before)
                mainHandler.post(flushPendingDelete)
            }
        }
    }

    // Fields as android.widget.TextView sends them.
    private fun announceTextChange(start: Int, removed: Int, added: Int, before: String, after: String) {
        speak(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, after) {
            fromIndex = start
            removedCount = removed
            addedCount = added
            beforeText = before
        }
    }

    // Moves by one segment from the moving end of the selection, as View.traverseAtGranularity does.
    private fun traverse(arguments: Bundle?, forward: Boolean): Boolean {
        val granularity = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT) ?: 0
        val extend = arguments?.getBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN) == true
        val (anchor, focus) = selectionEnds
        val segment = TextSegments.find(text.toString(), granularity, focus, forward) ?: return false
        val newFocus = if (forward) segment.second else segment.first
        select(anchor = if (extend) anchor else newFocus, focus = newFocus)
        speak(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY) {
            fromIndex = segment.first
            toIndex = segment.second
            movementGranularity = granularity
            action = if (forward) {
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            } else {
                AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            }
        }
        return true
    }

    // A known cause, so the selection subscriber above announces the move exactly once. The anchor
    // is set first, so Sora's setSelectionRegion keeps it as the fixed end.
    private fun select(anchor: Int, focus: Int) {
        selectionAnchor = text.indexer.getCharPosition(anchor).fromThis()
        val start = text.indexer.getCharPosition(minOf(anchor, focus))
        val startLine = start.line
        val startColumn = start.column
        val end = text.indexer.getCharPosition(maxOf(anchor, focus))
        setSelectionRegion(startLine, startColumn, end.line, end.column, SelectionChangeEvent.CAUSE_KEYBOARD_OR_CODE)
    }

    private val canSpeak: Boolean
        get() {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager?.isTouchExplorationEnabled == true && text.length <= props.maxAccessibilityTextLength
        }

    // [shown] is the text the event carries; by default the editor's current text.
    private fun speak(type: Int, shown: String? = null, fill: AccessibilityEvent.(current: String) -> Unit) {
        if (!canSpeak) return
        val current = shown ?: text.toString()

        @Suppress("DEPRECATION") // AccessibilityEvent(int) needs API 30; minSdk is 26.
        val event = AccessibilityEvent.obtain(type)
        event.className = EDIT_TEXT_CLASS_NAME
        event.packageName = context.packageName
        event.setSource(this)
        event.text.add(current)
        event.fill(current)
        sendAccessibilityEventUnchecked(event)
    }
}

/**
 * Segments for TalkBack's reading controls, as `[start, end)` character indexes. A line is a text
 * line (ended by "\n", which it includes), not a wrapped screen row; a paragraph is a run of text
 * separated by blank lines. Each returns null at the document edge.
 */
private object TextSegments {

    fun find(text: String, granularity: Int, from: Int, forward: Boolean): Pair<Int, Int>? = when (granularity) {
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER -> character(text, from, forward)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD -> word(text, from, forward)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE -> line(text, from, forward)
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH -> paragraph(text, from, forward)
        else -> null
    }

    private fun character(text: String, from: Int, forward: Boolean): Pair<Int, Int>? {
        val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
        return when {
            forward && from < text.length -> from to characters.following(from)
            !forward && from > 0 -> characters.preceding(from) to from
            else -> null
        }
    }

    private fun word(text: String, from: Int, forward: Boolean): Pair<Int, Int>? {
        val words = BreakIterator.getWordInstance().apply { setText(text) }
        if (forward) {
            var start = from
            while (start < text.length && !text[start].isLetterOrDigit()) start++
            return if (start < text.length) start to words.following(start) else null
        }
        var end = from
        while (end > 0 && !text[end - 1].isLetterOrDigit()) end--
        return if (end > 0) words.preceding(end) to end else null
    }

    private fun line(text: String, from: Int, forward: Boolean) =
        if (forward) lineAfter(text, from) else lineBefore(text, from)

    // From the start of a line, that line; from inside one, the next line.
    private fun lineAfter(text: String, from: Int): Pair<Int, Int>? {
        if (from >= text.length) return null
        val atLineStart = from == 0 || text[from - 1] == '\n'
        val start = if (atLineStart) from else text.indexOf('\n', from).let { if (it == -1) text.length else it + 1 }
        if (start >= text.length) return null
        val newline = text.indexOf('\n', start)
        return start to if (newline == -1) text.length else newline + 1
    }

    // From the end of a line, that line; from inside one, the line before it.
    private fun lineBefore(text: String, from: Int): Pair<Int, Int>? {
        if (from <= 0) return null
        val atLineEnd = from == text.length || text[from - 1] == '\n'
        val end = if (atLineEnd) from else text.lastIndexOf('\n', from - 1) + 1
        if (end <= 0) return null
        return text.lastIndexOf('\n', end - 2) + 1 to end
    }

    private fun paragraph(text: String, from: Int, forward: Boolean): Pair<Int, Int>? {
        if (forward) {
            var start = from
            while (start < text.length && text[start] == '\n') start++
            if (start >= text.length) return null
            var end = text.indexOf("\n\n", start).let { if (it == -1) text.length else it }
            while (text[end - 1] == '\n') end--
            return start to end
        }
        var end = from
        while (end > 0 && text[end - 1] == '\n') end--
        if (end == 0) return null
        return text.lastIndexOf("\n\n", end - 1).let { if (it == -1) 0 else it + 2 } to end
    }
}
