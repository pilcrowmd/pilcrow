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
 */
fun CodeEditor.applyFormat(action: FormatAction) {
    // isEditable is false while Sora is still measuring the text; an edit then would throw.
    if (!isEditable) return
    val content = text
    val result = MarkdownFormatting.apply(content, cursor.left, cursor.right, action)
    if (result.replacements.isEmpty()) return

    content.beginBatchEdit()
    try {
        // Last to first, so each replacement's offsets are still those of the text it was made for.
        result.replacements.asReversed().forEach { r ->
            when {
                r.text.isEmpty() -> content.delete(r.start, r.end)
                r.start == r.end -> {
                    val at = content.indexer.getCharPosition(r.start)
                    content.insert(at.line, at.column, r.text)
                }
                else -> content.replace(r.start, r.end, r.text)
            }
        }
    } finally {
        content.endBatchEdit()
    }

    val start = content.indexer.getCharPosition(result.selectionStart)
    if (result.selectionStart == result.selectionEnd) {
        setSelection(start.line, start.column)
    } else {
        val end = content.indexer.getCharPosition(result.selectionEnd)
        setSelectionRegion(start.line, start.column, end.line, end.column)
    }
    ensureSelectionVisible()
    notifyIMEExternalCursorChange()
}
