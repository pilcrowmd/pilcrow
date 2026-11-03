// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.FormatAction
import com.pilcrowmd.domain.model.EditorSupport.Companion.MAX_EDITOR_LINE_CHARS
import com.pilcrowmd.testing.withRealSize
import io.github.rosemoe.sora.text.UndoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.random.Random

/**
 * S8-A64: a file line too long for the editor is shown cut into display lines, and **the file text is
 * the editor text without those breaks after any sequence of edits, undos and redos.** Driven through
 * the real [PilcrowCodeEditor] and Sora's own `Content` and undo manager, against a model that keeps
 * its own flag per character and takes undo and redo from its own snapshots: nothing in the model
 * calls the code under test.
 */
@RunWith(RobolectricTestRunner::class)
class PilcrowCodeEditorBreaksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun editorShowing(file: String): PilcrowCodeEditor = PilcrowCodeEditor(context).withRealSize().also {
        it.setFileText(file)
        awaitEditable(it)
    }

    // Sora measures the text on its own thread pool and is not editable until it is done.
    private fun awaitEditable(editor: PilcrowCodeEditor) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!editor.isEditable && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(2)
        }
        assertTrue("precondition: the editor must accept input", editor.isEditable)
    }

    // --- the model: editor text plus one "virtual" flag per character -------------------------------

    private class State(val text: String, val virtual: List<Boolean>) {
        val file: String get() = text.filterIndexed { i, _ -> !virtual[i] }
        val breaks: List<Int> get() = virtual.indices.filter { virtual[it] }
    }

    private fun initialState(file: String): State {
        // Flags set by walking the file against the editor text, not read from the code under test:
        // a character the file does not have next is a virtual newline. A cut never touches a real
        // newline, so the walk cannot take a real one for a virtual one.
        val editorText = PilcrowCodeEditor(context).run {
            setFileText(file)
            text.toString().also { release() }
        }
        val virtual = ArrayList<Boolean>(editorText.length)
        var f = 0
        for (c in editorText) {
            val fromFile = f < file.length && file[f] == c
            if (!fromFile) assertEquals("only newlines are added", '\n', c)
            virtual += !fromFile
            if (fromFile) f++
        }
        assertEquals("precondition: the model rebuilt the file", file, State(editorText, virtual).file)
        return State(editorText, virtual)
    }

    private fun State.insert(at: Int, s: String) = State(
        text.substring(0, at) + s + text.substring(at),
        virtual.subList(0, at) + List(s.length) { false } + virtual.subList(at, virtual.size),
    )

    private fun State.delete(from: Int, to: Int) =
        State(text.removeRange(from, to), virtual.subList(0, from) + virtual.subList(to, virtual.size))

    // --- random documents and edits ------------------------------------------------------------------

    private fun Random.unit(): String = when (nextInt(6)) {
        0 -> "word "
        1 -> "漢字かな"
        2 -> "😀"
        3 -> "👩‍👩‍👧‍👦"
        4 -> "é"
        else -> "x"
    }

    private fun Random.document(): String {
        val lines = List(nextInt(1, 5)) {
            val length = if (nextInt(3) == 0) nextInt(0, 60) else nextInt(MAX_EDITOR_LINE_CHARS + 1, 6_500)
            val sb = StringBuilder()
            val unit = unit()
            while (sb.length < length) sb.append(if (nextInt(10) == 0) unit() else unit)
            sb.toString()
        }
        return lines.joinToString("\n", postfix = if (nextBoolean()) "\n" else "")
    }

    private fun Random.typed(): String = List(nextInt(1, 6)) {
        when (nextInt(5)) {
            0 -> "\n"
            1 -> "漢"
            2 -> "😀"
            else -> ('a' + nextInt(26)).toString()
        }
    }.joinToString("")

    /** An offset that does not split a surrogate pair. */
    private fun Random.offsetIn(text: String): Int {
        var at = nextInt(text.length + 1)
        if (at in 1 until text.length && text[at - 1].isHighSurrogate() && text[at].isLowSurrogate()) at--
        return at
    }

    private fun PilcrowCodeEditor.insertAt(at: Int, s: String) {
        val p = text.indexer.getCharPosition(at)
        text.insert(p.line, p.column, s)
    }

    private fun PilcrowCodeEditor.deleteRange(from: Int, to: Int) {
        val a = text.indexer.getCharPosition(from)
        val b = text.indexer.getCharPosition(to)
        text.delete(a.line, a.column, b.line, b.column)
    }

    /**
     * Runs [sequences] seeded sequences of [steps] random steps each, checking the invariant after every
     * step. [exact]: Sora's merging is off, so every edit is its own undo step, except that Sora joins a
     * batch edit to the batch before it after an undo (any push clears its `forceNewMultiAction`).
     * Otherwise Sora also merges typing and backspaces. Either way an undo or redo must land on SOME
     * earlier (later) state of the editor text, and the file text must be that state's.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one branch per kind of random step, read side by side
    private fun runSequences(seed: Long, sequences: Int, steps: Int, exact: Boolean) {
        val mergeLimit = UndoManager.getMergeTimeLimit()
        if (exact) UndoManager.setMergeTimeLimit(0)
        val editor = PilcrowCodeEditor(context)
        try {
            val random = Random(seed)
            repeat(sequences) { sequence ->
                val file = random.document()
                editor.setFileText(file)
                awaitEditable(editor)
                val history = mutableListOf(initialState(file))
                var at = 0
                val log = mutableListOf<String>()

                fun check(step: String) {
                    log += step
                    val state = history[at]
                    val where = "seed $seed, sequence $sequence, after ${log.joinToString(" | ")}"
                    assertEquals("the editor text: $where", state.text, editor.text.toString())
                    assertEquals("THE FILE TEXT: $where", state.file, editor.fileText())
                }
                check("open")

                repeat(steps) {
                    val state = history[at]
                    val roll = random.nextInt(100)
                    when {
                        roll < 13 && editor.canUndo() -> {
                            editor.undo()
                            at = history.indexOfLast(at) { it.text == editor.text.toString() }
                            check("undo")
                        }
                        roll < 25 && editor.canRedo() -> {
                            editor.redo()
                            at = history.indexOfFirst(at) { it.text == editor.text.toString() }
                            check("redo")
                        }
                        roll < 40 && state.breaks.isNotEmpty() -> {
                            // A backspace at the start of a continuation piece, or a range across a break.
                            val b = state.breaks[random.nextInt(state.breaks.size)]
                            val from = if (random.nextBoolean()) b else (b - random.nextInt(1, 5)).coerceAtLeast(0)
                            val after = if (random.nextBoolean()) 0 else random.nextInt(1, 5)
                            val to = (b + 1 + after).coerceAtMost(state.text.length)
                            val (f, t) = surrogateSafe(state.text, from, to)
                            editor.deleteRange(f, t)
                            push(history, at++, state.delete(f, t))
                            check("delete break [$f,$t)")
                        }
                        roll < 47 && state.breaks.isNotEmpty() -> {
                            // Sora's replace, one undo step: a range across a break becomes typed text.
                            val b = state.breaks[random.nextInt(state.breaks.size)]
                            val (f, t) = surrogateSafe(
                                state.text,
                                (b - random.nextInt(0, 5)).coerceAtLeast(0),
                                (b + 1 + random.nextInt(0, 5)).coerceAtMost(state.text.length),
                            )
                            val s = random.typed()
                            editor.text.replace(f, t, s)
                            push(history, at++, state.delete(f, t).insert(f, s))
                            check("replace [$f,$t) with ${s.length}")
                        }
                        roll < 52 && state.text.isNotEmpty() -> {
                            // A batch edit, as the formatting bar makes: an insert, then a delete before it.
                            val p = random.offsetIn(state.text)
                            val s = random.typed()
                            val (f, t) = surrogateSafe(state.text, (p - random.nextInt(1, 8)).coerceAtLeast(0), p)
                            editor.text.beginBatchEdit()
                            editor.insertAt(p, s)
                            editor.deleteRange(f, t)
                            editor.text.endBatchEdit()
                            push(history, at++, state.insert(p, s).delete(f, t))
                            check("batch: type ${s.length} at $p, delete [$f,$t)")
                        }
                        roll < 60 && state.text.isNotEmpty() -> {
                            val from = random.offsetIn(state.text)
                            val to = (from + random.nextInt(1, 20)).coerceAtMost(state.text.length)
                            val (f, t) = surrogateSafe(state.text, from, to)
                            if (t > f) {
                                editor.deleteRange(f, t)
                                push(history, at++, state.delete(f, t))
                                check("delete [$f,$t)")
                            }
                        }
                        roll < 70 -> {
                            val p = random.offsetIn(state.text)
                            editor.insertAt(p, "\n")
                            push(history, at++, state.insert(p, "\n"))
                            check("enter at $p")
                        }
                        else -> {
                            val p = random.offsetIn(state.text)
                            val s = random.typed()
                            editor.insertAt(p, s)
                            push(history, at++, state.insert(p, s))
                            check("type ${s.length} at $p")
                        }
                    }
                }
                // All the way back: the file is the one that was opened.
                while (editor.canUndo()) editor.undo()
                val where = "seed $seed, sequence $sequence"
                assertEquals("$where: undo to the start gives the file back", file, editor.fileText())
            }
        } finally {
            UndoManager.setMergeTimeLimit(mergeLimit)
            editor.release()
        }
    }

    private fun push(history: MutableList<State>, at: Int, next: State) {
        while (history.size > at + 1) history.removeAt(history.size - 1)
        history += next
    }

    private fun surrogateSafe(text: String, from: Int, to: Int): Pair<Int, Int> {
        var f = from
        var t = to
        if (f in 1 until text.length && text[f - 1].isHighSurrogate() && text[f].isLowSurrogate()) f--
        if (t in 1 until text.length && text[t - 1].isHighSurrogate() && text[t].isLowSurrogate()) t++
        return f to t
    }

    private fun List<State>.indexOfLast(before: Int, match: (State) -> Boolean): Int {
        for (i in before - 1 downTo 0) if (match(this[i])) return i
        fail("an undo went to an editor text no earlier state had")
        return -1
    }

    private fun List<State>.indexOfFirst(after: Int, match: (State) -> Boolean): Int {
        for (i in after + 1 until size) if (match(this[i])) return i
        fail("a redo went to an editor text no later state had")
        return -1
    }

    @Test
    fun undoAndRedoKeepTheFileTextExactStepForStep() =
        runSequences(seed = 20261031L, sequences = 1_000, steps = 30, exact = true)

    @Test
    fun undoAndRedoKeepTheFileTextWithSorasMergedSteps() =
        runSequences(seed = 64L, sequences = 300, steps = 30, exact = false)

    // --- the rest of what crosses the editor boundary ------------------------------------------------

    private val longLine = "abc ".repeat(1_500) // 6,000 chars: three display pieces

    private companion object {
        const val GUTTER_PX = 60f
    }

    @Test
    fun theGutterNumbersFileLinesOnTheirFirstPieceOnly() {
        val editor = editorShowing("first\n$longLine\nlast")
        assertEquals("precondition: 5 display lines", 5, editor.text.lineCount)
        assertEquals(listOf(0, 1, null, null, 2), (0 until 5).map { editor.fileLineOf(it) })
    }

    @Test
    fun theGutterDrawsOnlyFileLineNumbers() {
        val editor = editorShowing("first\n$longLine\nlast")
        val drawn = mutableListOf<Pair<Int, Int>>()
        // Sora's row loop asks for each display line's number on its first row. Sora draws nothing under
        // Robolectric (and a Canvas subclass sees no calls), so the record is taken where our renderer
        // hands a line to Sora's own drawing, which writes that line + 1.
        val renderer = object : FileLineNumberRenderer(editor) {
            fun askFor(line: Int) = drawLineNumber(Canvas(), line, line, 0f, GUTTER_PX, 0)
            override fun drawSorasLineNumber(
                canvas: Canvas,
                line: Int,
                row: Int,
                offsetY: Float,
                width: Float,
                color: Int,
            ) {
                drawn += row to line + 1
            }
        }
        (0 until editor.text.lineCount).forEach { renderer.askFor(it) }
        assertEquals(
            "display row to the number drawn: the file's own numbers, the long line's only on its first piece",
            listOf(0 to 1, 1 to 2, 4 to 3),
            drawn,
        )
    }

    @Test
    fun theTopRowsNumberIsTheFileLineOrNone() {
        // Sora draws the number of the line holding the screen's top row on its own (as text, not
        // through drawLineNumber), from a value drawRows fills in; the renderer maps that value.
        val editor = editorShowing("first\n$longLine\nlast")
        val renderer = FileLineNumberRenderer(editor)
        assertEquals(listOf(0, 1, -1, -1, 2, -1), listOf(0, 1, 2, 3, 4, -1).map { renderer.topRowNumber(it) })
    }

    @Test
    fun withNoCutLineTheGutterIsSoras() {
        val editor = editorShowing("a\nb\nc")
        assertEquals(listOf(0, 1, 2), (0 until 3).map { editor.fileLineOf(it) })
    }

    @Test
    fun aCopyAcrossABreakCopiesFileText() {
        val file = "first\n$longLine\nlast"
        val editor = editorShowing(file)
        // From inside the first piece to inside the second: one virtual break in between.
        editor.setSelectionRegion(1, 1_990, 2, 10)
        editor.copyText()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        val from = file.indexOf(longLine) + 1_990
        assertEquals(file.substring(from, from + 20), clip)
    }

    @Test
    fun anotherWholeTextReplaceBringsNoBreaks() {
        val editor = editorShowing("first\n$longLine")
        // Long enough that the old breaks (about 2,000, 4,000 and 6,000) would fall inside it.
        val replacement = "plain\n" + "q\n".repeat(3_500)
        editor.setText(replacement)
        assertEquals(replacement, editor.fileText())
        val lines = (0 until 3_501).toList()
        assertEquals("no line is a continuation", lines, lines.map { editor.fileLineOf(it) })
    }

    @Test
    fun aLinePrefixOnACutLineGoesAtTheFileLinesStart() {
        val file = "first\n$longLine\nlast"
        val editor = editorShowing(file)
        editor.setSelection(3, 10) // inside the long line's third display piece
        editor.applyFormat(FormatAction.Quote)
        assertEquals("first\n> $longLine\nlast", editor.fileText())
    }

    @Test
    fun boldAcrossABreakWrapsTheFileRange() {
        val file = "first\n$longLine\nlast"
        val editor = editorShowing(file)
        editor.setSelectionRegion(1, 1_990, 2, 10) // one virtual break inside the selection
        editor.applyFormat(FormatAction.Bold)
        val from = file.indexOf(longLine) + 1_990
        val bold = "**" + file.substring(from, from + 20) + "**"
        val expected = file.substring(0, from) + bold + file.substring(from + 20)
        assertEquals(expected, editor.fileText())
    }

    @Test
    fun theEditorDrawsItsGutterThroughTheFileLineRenderer() {
        assertTrue(PilcrowCodeEditor(context).renderer is FileLineNumberRenderer)
    }

    @Test
    fun aShortLineIsGivenAsItIs() {
        val file = "x".repeat(MAX_EDITOR_LINE_CHARS)
        val editor = editorShowing(file)
        assertEquals(file, editor.text.toString())
    }
}
