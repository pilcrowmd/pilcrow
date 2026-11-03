// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.collection.MutableIntList
import com.pilcrowmd.domain.editor.VirtualLineBreaks
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.util.LongArrayList
import io.github.rosemoe.sora.util.MutableInt
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorRenderer

/**
 * The app's [CodeEditor]: Sora's editor, plus two rules on the way text enters it.
 *
 * A CRLF file is held in memory with LF only and written back with "\r\n" by the ViewModel
 * (Safeguard 2), but Sora keeps a separator per line, so a pasted "\r\n" would stay in the text and
 * be saved as "\r\r\n". Every user insertion reaches the document through [commitText] with
 * three arguments: the editor's own Paste (`pasteText` goes through the input connection) and an
 * input method's commit alike. Folding there is one insert, so one undo still takes the whole paste
 * back. A lone "\r" is user content (M-353) and is left alone.
 *
 * A file line longer than the editor takes is shown cut into display lines (S8-A64,
 * [VirtualLineBreaks]): the file goes in through [setFileText] and comes out through [fileText], and
 * offsets cross with [toFileOffset] and [toEditorOffset]. The breaks follow every edit, undo and
 * redo, are left out of a copy, and get no line number: the gutter shows the file's own line number
 * on a line's first display piece only. All of it through Sora's public and protected API, so Sora
 * stays an unmodified library (LGPL-2.1 section 6).
 */
@Suppress("TooManyFunctions") // Sora's overrides plus the file/editor maps; one class keeps the invariant in one place
open class PilcrowCodeEditor(context: Context) : CodeEditor(context) {
    // Sora's constructor already calls setText and onCreateRenderer, before this class's own fields
    // are initialised; lateinit has no initialiser to overwrite what was created by then.
    private lateinit var breaksField: VirtualLineBreaks
    private val breaks: VirtualLineBreaks
        get() {
            if (!::breaksField.isInitialized) breaksField = VirtualLineBreaks()
            return breaksField
        }
    private var settingFileText = false
    private var replay: VirtualLineBreaks.Replay? = null

    /** Shows [fileText], with each line too long for the editor cut into display lines. */
    fun setFileText(fileText: String) {
        val cut = VirtualLineBreaks.cut(fileText)
        // The breaks before the text: setText reports the new text, and its listeners read fileText.
        breaks.reset(cut.breaks)
        settingFileText = true
        try {
            setText(cut.text)
        } finally {
            settingFileText = false
        }
    }

    /**
     * The file's text: the editor's text without its display breaks. From one `toString` of Sora's text:
     * reading it char by char takes Sora's lock and allocates for every char.
     */
    fun fileText(): String = breaks.strip(text.toString())

    /** Whether the editor's file text equals [fileText], without building it when the lengths differ. */
    fun holdsFileText(fileText: String): Boolean =
        text.length - breaks.count == fileText.length && fileText() == fileText

    fun toFileOffset(editorOffset: Int): Int = breaks.toFile(editorOffset)

    fun toEditorOffset(fileOffset: Int): Int = breaks.toEditor(fileOffset)

    /**
     * The 0-based file line of display line [line], or null when that display line continues a file
     * line cut for the editor.
     */
    fun fileLineOf(line: Int): Int? {
        if (breaks.count == 0) return line
        val start = text.getCharIndex(line, 0)
        if (start > 0 && breaks.isBreak(start - 1)) return null
        return line - breaks.countBefore(start)
    }

    /** Whether display line [line] is one piece of a file line cut for the editor. */
    fun isPieceOfCutLine(line: Int): Boolean {
        if (breaks.count == 0) return false
        val start = text.getCharIndex(line, 0)
        val end = start + text.getColumnCount(line)
        return (start > 0 && breaks.isBreak(start - 1)) || breaks.isBreak(end)
    }

    // Any other replace of the whole text (an accessibility service's ACTION_SET_TEXT) brings plain
    // file text: no breaks.
    override fun setText(text: CharSequence?, reuseContentObject: Boolean, extraArguments: Bundle?) {
        if (!settingFileText) breaks.reset(NO_BREAKS)
        super.setText(text, reuseContentObject, extraArguments)
    }

    // The breaks move before Sora reports the change, so its listeners already see them in place.
    override fun afterInsert(
        content: Content,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        insertedContent: CharSequence,
    ) {
        if (breaks.isActive) {
            breaks.onInsert(content.getCharIndex(startLine, startColumn), insertedContent, replayOf(content))
        }
        super.afterInsert(content, startLine, startColumn, endLine, endColumn, insertedContent)
    }

    override fun afterDelete(
        content: Content,
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        deletedContent: CharSequence,
    ) {
        if (breaks.isActive) {
            breaks.onDelete(content.getCharIndex(startLine, startColumn), deletedContent, replayOf(content))
        }
        super.afterDelete(content, startLine, startColumn, endLine, endColumn, deletedContent)
    }

    private fun replayOf(content: Content): VirtualLineBreaks.Replay? =
        if (content.isUndoManagerWorking) replay ?: VirtualLineBreaks.Replay.UNKNOWN else null

    override fun undo() {
        replay = VirtualLineBreaks.Replay.UNDO
        try {
            super.undo()
        } finally {
            replay = null
        }
    }

    override fun redo() {
        replay = VirtualLineBreaks.Replay.REDO
        try {
            super.redo()
        } finally {
            replay = null
        }
    }

    // Copy, cut and the copy of a whole line all come here with the editor's range.
    override fun copyTextToClipboard(text: CharSequence, start: Int, end: Int) {
        // Sora also copies a bare line separator through here; only a range of the editor's text has breaks.
        if (breaks.count == 0 || text !== this.text) return super.copyTextToClipboard(text, start, end)
        val fileText = breaks.strip(text.toString(), start, end)
        super.copyTextToClipboard(fileText, 0, fileText.length)
    }

    override fun onCreateRenderer(): EditorRenderer = FileLineNumberRenderer(this)

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        super.commitText(foldCrlf(text), applyAutoIndent, applySymbolCompletion)
    }

    /**
     * An accessibility service's ACTION_SET_TEXT reaches Sora's `setText`, never [commitText] (M-353),
     * so its text is folded here, on a copy of the caller's [arguments].
     */
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
        if (arguments != null && text != null && action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
            val folded = Bundle(arguments).apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, foldCrlf(text))
            }
            return super.performAccessibilityAction(action, folded)
        }
        return super.performAccessibilityAction(action, arguments)
    }
}

/**
 * Draws the file's own line number on a line's first display piece, and none on the others. Sora draws
 * a line's number in two ways: through [drawLineNumber], and, for the top row of the screen, as the
 * number held in `firstLn`, which [drawRows] fills in; both are mapped here.
 */
internal open class FileLineNumberRenderer(private val editor: PilcrowCodeEditor) : EditorRenderer(editor) {
    override fun drawLineNumber(canvas: Canvas, line: Int, row: Int, offsetY: Float, width: Float, color: Int) {
        val fileLine = editor.fileLineOf(line) ?: return
        drawSorasLineNumber(canvas, fileLine, row, offsetY, width, color)
    }

    @Suppress("LongParameterList") // Sora's drawRows signature
    override fun drawRows(
        canvas: Canvas,
        offset: Float,
        postDrawLineNumbers: LongArrayList,
        postDrawCursor: MutableList<DrawCursorTask>,
        postDrawCurrentLines: MutableIntList,
        firstLn: MutableInt?,
        stuckLines: MutableList<CodeBlock>?,
    ) {
        super.drawRows(canvas, offset, postDrawLineNumbers, postDrawCursor, postDrawCurrentLines, firstLn, stuckLines)
        if (firstLn != null) firstLn.value = topRowNumber(firstLn.value)
    }

    /** The top row's line number as Sora will draw it (-1: none), from the display line it holds. */
    fun topRowNumber(displayLine: Int): Int = if (displayLine == -1) -1 else editor.fileLineOf(displayLine) ?: -1

    /** Sora's own drawing: the number [line] + 1 on display row [row]. The gutter test's seam. */
    @Suppress("LongParameterList") // Sora's drawLineNumber signature
    protected open fun drawSorasLineNumber(
        canvas: Canvas,
        line: Int,
        row: Int,
        offsetY: Float,
        width: Float,
        color: Int,
    ) = super.drawLineNumber(canvas, line, row, offsetY, width, color)
}

private val NO_BREAKS = IntArray(0)

// The same calls on any CodeEditor: a plain one (tests, previews) has no display breaks.

/** [PilcrowCodeEditor.setFileText], or Sora's setText. */
internal fun CodeEditor.showFileText(fileText: String) =
    if (this is PilcrowCodeEditor) setFileText(fileText) else setText(fileText)

/** [PilcrowCodeEditor.fileText], or the whole text. */
internal fun CodeEditor.currentFileText(): String = (this as? PilcrowCodeEditor)?.fileText() ?: text.toString()

/** [PilcrowCodeEditor.holdsFileText]. */
internal fun CodeEditor.holdsFile(fileText: String): Boolean = (this as? PilcrowCodeEditor)?.holdsFileText(fileText)
    ?: (text.length == fileText.length && text.toString() == fileText)

/** [PilcrowCodeEditor.toFileOffset]. */
internal fun CodeEditor.fileOffsetOf(editorOffset: Int): Int =
    (this as? PilcrowCodeEditor)?.toFileOffset(editorOffset) ?: editorOffset

/** [PilcrowCodeEditor.toEditorOffset]. */
internal fun CodeEditor.editorOffsetOf(fileOffset: Int): Int =
    (this as? PilcrowCodeEditor)?.toEditorOffset(fileOffset) ?: fileOffset

private fun foldCrlf(text: CharSequence): CharSequence =
    if (text.contains('\r')) text.toString().replace("\r\n", "\n") else text
