// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.view.KeyEvent
import com.pilcrowmd.domain.markdown.ListContinuation
import io.github.rosemoe.sora.event.EditorKeyEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

/**
 * Enter on a Markdown list or quote line writes the next marker, or ends the list on an empty item
 * (**M-168**; the rules live in [ListContinuation]). Unsubscribe the returned receipt to remove it.
 *
 * One key event covers both keyboards: Sora turns a soft keyboard's committed "\n" into an Enter key
 * click. The numeric keypad's Enter counts as Enter. Every case [ListContinuation] declines is left
 * to Sora's own Enter, untouched.
 *
 * The edit runs inside one batch edit, so it is ONE undo step of its own: without the batch, Sora
 * merges an insert that starts where the previous one ended into that earlier action, and a single
 * undo would also take back the text typed just before Enter.
 */
fun CodeEditor.continueListsOnEnter(): SubscriptionReceipt<EditorKeyEvent> = subscribeAlways<EditorKeyEvent> { event ->
    val enter = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    if (event.eventType != EditorKeyEvent.Type.DOWN || !enter) return@subscribeAlways
    if (event.isShiftPressed || event.isCtrlPressed || event.isAltPressed) return@subscribeAlways
    // isEditable is also false while Sora is still measuring the text; its own Enter waits for that too.
    if (!isEditable || cursor.isSelected) return@subscribeAlways

    val content = text
    val line = cursor.leftLine
    val linesAbove = (0 until line).asSequence().map { content.getLineString(it) }
    val action = ListContinuation.onEnter(content.getLineString(line), cursor.leftColumn, linesAbove)
        ?: return@subscribeAlways

    content.beginBatchEdit()
    try {
        when (action) {
            is ListContinuation.Action.Continue -> content.insert(line, cursor.leftColumn, action.insertText)
            is ListContinuation.Action.EndList ->
                content.delete(line, action.deleteStartColumn, line, action.deleteEndColumn)
        }
    } finally {
        content.endBatchEdit()
    }
    event.markAsConsumed()
    ensureSelectionVisible()
    notifyIMEExternalCursorChange()
}
