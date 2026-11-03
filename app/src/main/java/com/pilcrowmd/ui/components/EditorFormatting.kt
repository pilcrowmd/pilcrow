// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import com.pilcrowmd.domain.markdown.FormatAction
import com.pilcrowmd.domain.markdown.MarkdownFormatting
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Applies a formatting-bar button to this editor's text and selection (**M-217**; the rules live in
 * [MarkdownFormatting]).
 *
 * The edits run inside one batch edit, so a tap is ONE undo step: without the batch, the two
 * markers of a wrap would be two undo steps, and Sora would merge an insert that starts where the
 * previous one ended into that earlier action (typing included). They go through [CodeEditor.getText]
 * like typing does, so Sora's undo history and change events see them.
 *
 * The rules read the file's text, not the editor's (S8-A64): a line cut into display pieces is still
 * one line, so a heading or a list marker goes at its start, never at a piece's. Offsets cross with
 * [fileOffsetOf] and [editorOffsetOf], each mapped just before it is used.
 */
fun CodeEditor.applyFormat(action: FormatAction) {
    // isEditable is false while Sora is still measuring the text; an edit then would throw.
    if (!isEditable) return
    val content = text
    val result = MarkdownFormatting.apply(
        currentFileText(),
        fileOffsetOf(cursor.left),
        fileOffsetOf(cursor.right),
        action,
    )
    if (result.replacements.isEmpty()) return

    content.beginBatchEdit()
    try {
        // Last to first, so each replacement's offsets are still those of the text it was made for.
        result.replacements.asReversed().forEach { r ->
            // Mapped now: the replacements after this one have moved nothing before it.
            val start = editorOffsetOf(r.start)
            val end = editorOffsetOf(r.end)
            when {
                r.text.isEmpty() -> content.delete(start, end)
                start == end -> {
                    val at = content.indexer.getCharPosition(start)
                    content.insert(at.line, at.column, r.text)
                }
                else -> content.replace(start, end, r.text)
            }
        }
    } finally {
        content.endBatchEdit()
    }

    val start = content.indexer.getCharPosition(editorOffsetOf(result.selectionStart))
    if (result.selectionStart == result.selectionEnd) {
        setSelection(start.line, start.column)
    } else {
        val end = content.indexer.getCharPosition(editorOffsetOf(result.selectionEnd))
        setSelectionRegion(start.line, start.column, end.line, end.column)
    }
    ensureSelectionVisible()
    notifyIMEExternalCursorChange()
}
