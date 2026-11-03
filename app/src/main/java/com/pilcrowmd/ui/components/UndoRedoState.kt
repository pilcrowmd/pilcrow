// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

/** Whether the editor has a step to undo and a step to redo (GitHub #19). */
data class UndoRedoState(val canUndo: Boolean = false, val canRedo: Boolean = false)

/**
 * Follows [editor]'s undo history, so Undo and Redo can be greyed out while there is no step. Every
 * change to the history comes with a [ContentChangeEvent]: an edit, an undo, a redo, and the `setText`
 * of another file or a reload (a new `Content`, so an empty history).
 *
 * The history is read one main-loop turn after the event, not inside it: Sora's `UndoManager.undo`
 * applies the step (which dispatches the event) and only then moves its stack pointer, so `canUndo()`
 * read inside the event still answers for the step before (measured from Sora 0.24.5's bytecode).
 */
@Composable
fun rememberUndoRedoState(editor: CodeEditor): State<UndoRedoState> {
    val state = remember(editor) { mutableStateOf(UndoRedoState(editor.canUndo(), editor.canRedo())) }
    DisposableEffect(editor) {
        val read = Runnable { state.value = UndoRedoState(editor.canUndo(), editor.canRedo()) }
        read.run()
        val receipt = editor.subscribeAlways<ContentChangeEvent> {
            editor.removeCallbacks(read)
            editor.post(read)
        }
        onDispose {
            receipt.unsubscribe()
            editor.removeCallbacks(read)
        }
    }
    return state
}
