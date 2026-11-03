// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.editor

import com.pilcrowmd.domain.markdown.LongLineCut
import com.pilcrowmd.domain.model.EditorSupport

/**
 * Display line breaks the editor shows but the file does not have (S8-A64). Sora shapes a whole line
 * on the main thread when it draws or edits it, so one line of megabytes froze Edit mode (b3, a
 * 5 MB CJK line). The editor is therefore given the file with every line longer than
 * [EditorSupport.MAX_EDITOR_LINE_CHARS] cut into display lines of at most that length, each cut one
 * extra `'\n'`, and this class records exactly those characters by their editor offset.
 *
 * **Invariant: file text = editor text minus the characters at [breaks].** Save, the dirty check and
 * the line-ending restore see only [strip]'s output; offsets cross the boundary through [toFile] and
 * [toEditor].
 *
 * Edits keep the invariant: [onInsert] and [onDelete] shift the breaks, a deleted break was never in
 * the file, so dropping it is exact, and a typed Enter is file text. Sora's undo puts deleted text
 * back as plain characters, so this class mirrors the edits it saw, one op per insert or delete, and
 * re-marks the breaks a replayed insert brings back (see [onInsert]).
 *
 * A document with no line over the limit gets no breaks, and then every call is a no-op or a copy.
 */
@Suppress("TooManyFunctions") // the maps, the edit hooks and the undo mirror share one sorted array
class VirtualLineBreaks {

    /**
     * Who made an edit: the user (null), or Sora's undo manager going back or forward. [UNKNOWN] is
     * the undo manager on a path that did not say which; the mirror cannot follow it.
     */
    enum class Replay { UNDO, REDO, UNKNOWN }

    /** A file cut for the editor: [text] and the editor offsets of its virtual breaks, ascending. */
    class Cut(val text: String, val breaks: IntArray)

    private var offsets = IntArray(0)
    private var size = 0

    // The user's edits in order, as Sora's undo manager records them, and how many are applied: the
    // ops above [applied] are the ones Sora can redo. Recorded only while the document had breaks.
    private val history = ArrayDeque<Op>()
    private var applied = 0
    private var tracking = false

    /** One user insert or delete; [marks] are a delete's virtual breaks, relative to [start]. */
    private class Op(val insert: Boolean, val start: Int, val length: Int, val marks: IntArray)

    /** How many virtual breaks the editor text has. */
    val count: Int get() = size

    /** Whether edits must be followed: the text had breaks when it was set. False: every edit is a no-op. */
    val isActive: Boolean get() = tracking

    /** The breaks' editor offsets, ascending (a copy). */
    fun breaks(): IntArray = offsets.copyOf(size)

    /** Starts over for a new editor text whose virtual breaks are [breaks], ascending. */
    fun reset(breaks: IntArray) {
        offsets = breaks.copyOf()
        size = breaks.size
        history.clear()
        applied = 0
        tracking = size > 0
    }

    /** The file text of [editorText]: [editorText] without its virtual breaks. */
    fun strip(editorText: CharSequence): String = strip(editorText, 0, editorText.length)

    /** The file text of the editor range [[start], [end]). */
    fun strip(editorText: CharSequence, start: Int, end: Int): String {
        if (size == 0) {
            val whole = start == 0 && end == editorText.length
            return if (whole) editorText.toString() else editorText.substring(start, end)
        }
        val out = StringBuilder(end - start)
        var from = start
        for (i in countBefore(start) until size) {
            val b = offsets[i]
            if (b >= end) break
            out.append(editorText, from, b)
            from = b + 1
        }
        out.append(editorText, from, end)
        return out.toString()
    }

    /** The file offset of editor offset [editorOffset]. */
    fun toFile(editorOffset: Int): Int = editorOffset - countBefore(editorOffset)

    /**
     * The editor offset of file offset [fileOffset]. At a cut that is the start of the next display
     * line, after the break. `toFile(toEditor(f)) == f` for every f.
     */
    fun toEditor(fileOffset: Int): Int {
        // Break i sits just before file offset offsets[i] - i, which never decreases with i.
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] - mid <= fileOffset) lo = mid + 1 else hi = mid
        }
        return fileOffset + lo
    }

    /** Whether the editor character at [editorOffset] is a virtual break. */
    fun isBreak(editorOffset: Int): Boolean {
        val i = countBefore(editorOffset)
        return i < size && offsets[i] == editorOffset
    }

    /** How many virtual breaks lie before editor offset [editorOffset]. */
    fun countBefore(editorOffset: Int): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] < editorOffset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * [text] was inserted at editor offset [start], by the user (`replay == null`) or by Sora's undo
     * manager. Text the user inserts is file text, Enter included. An undo inserts back what a delete
     * took, and the breaks that delete held are breaks again: Sora undoes one action at a time, the
     * last first, and an action is one op or several merged ones of the same kind, so the ops on top
     * of the mirror add up to exactly this insert.
     */
    fun onInsert(start: Int, text: CharSequence, replay: Replay? = null) {
        val length = text.length
        if (length == 0) return
        shift(start, length)
        if (!tracking) return
        when (replay) {
            null -> record(Op(insert = true, start = start, length = length, marks = NO_MARKS))
            Replay.REDO -> if (!redo(insert = true, length)) forgetHistory()
            Replay.UNKNOWN -> forgetHistory()
            Replay.UNDO -> {
                val marks = undoneMarks(length) ?: return forgetHistory()
                // A mark that is not a newline would mean the mirror and Sora disagree: keep the text.
                if (marks.any { it < start || it >= start + length || text[it - start] != '\n' }) {
                    return forgetHistory()
                }
                marks.forEach { add(it) }
            }
        }
    }

    /** [deleted] was removed from editor offset [start]; see [onInsert] for [replay]. */
    fun onDelete(start: Int, deleted: CharSequence, replay: Replay? = null) {
        val length = deleted.length
        if (length == 0) return
        val first = countBefore(start)
        val last = countBefore(start + length)
        val marks = IntArray(last - first) { offsets[first + it] - start }
        offsets.copyInto(offsets, first, last, size)
        size -= last - first
        shift(start, -length)
        if (!tracking) return
        when (replay) {
            null -> record(Op(insert = false, start = start, length = length, marks = marks))
            Replay.REDO -> if (!redo(insert = false, length)) forgetHistory()
            Replay.UNKNOWN -> forgetHistory()
            Replay.UNDO -> if (!undoInserts(length)) forgetHistory()
        }
    }

    private fun shift(from: Int, by: Int) {
        for (i in countBefore(from) until size) offsets[i] += by
    }

    private fun add(offset: Int) {
        val i = countBefore(offset)
        if (i < size && offsets[i] == offset) return
        if (size == offsets.size) offsets = offsets.copyOf(maxOf(GROWTH, size * 2))
        offsets.copyInto(offsets, i + 1, i, size)
        offsets[i] = offset
        size++
    }

    private fun record(op: Op) {
        while (history.size > applied) history.removeLast()
        history.addLast(op)
        if (history.size > MAX_HISTORY) history.removeFirst()
        applied = history.size
    }

    /**
     * The breaks an undo inserting [length] chars brings back, as editor offsets after the insert,
     * or null if the deletes on top of the mirror do not add up to it. Merged deletes are put back
     * newest first, each where it was taken from, as one insert of all of them.
     */
    private fun undoneMarks(length: Int): IntArray? {
        var marks = IntArray(0)
        var total = 0
        while (total < length) {
            val op = history.getOrNull(applied - 1) ?: return null
            if (op.insert) return null
            marks = IntArray(marks.size) { if (marks[it] >= op.start) marks[it] + op.length else marks[it] } +
                IntArray(op.marks.size) { op.start + op.marks[it] }
            total += op.length
            applied--
        }
        return if (total == length) marks else null
    }

    /** An undo deleting [length] chars takes back the inserts on top of the mirror. */
    private fun undoInserts(length: Int): Boolean {
        var total = 0
        while (total < length) {
            val op = history.getOrNull(applied - 1) ?: return false
            if (!op.insert) return false
            total += op.length
            applied--
        }
        return total == length
    }

    /** A redo of [length] chars applies the next ops above the mirror's applied ones again. */
    private fun redo(insert: Boolean, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val op = history.getOrNull(applied) ?: return false
            if (op.insert != insert) return false
            total += op.length
            applied++
        }
        return total == length
    }

    // The mirror lost step with Sora (an edit path this class does not know): the breaks stay as
    // they are, and what an undo brings back from here on is plain text.
    private fun forgetHistory() {
        history.clear()
        applied = 0
    }

    companion object {
        private val NO_MARKS = IntArray(0)
        private const val GROWTH = 16

        /** Ops kept for undo, oldest dropped first; Sora's own stack is shorter. */
        private const val MAX_HISTORY = 100_000

        /**
         * [fileText] cut for the editor: every line longer than [limit] split into display lines of at
         * most [limit] chars, never inside a surrogate pair, a combining sequence or an emoji sequence
         * ([LongLineCut.pieceEnd]). A text with no such line comes back as it is, with no breaks.
         */
        fun cut(fileText: String, limit: Int = EditorSupport.MAX_EDITOR_LINE_CHARS): Cut {
            if (!EditorSupport.needsLineBreaks(fileText, limit)) return Cut(fileText, NO_MARKS)
            val out = StringBuilder(fileText.length + fileText.length / limit + 1)
            val breaks = ArrayList<Int>()
            var lineStart = 0
            while (lineStart <= fileText.length) {
                val newline = fileText.indexOf('\n', lineStart)
                val lineEnd = if (newline < 0) fileText.length else newline
                var from = lineStart
                while (lineEnd - from > limit) {
                    val end = LongLineCut.pieceEnd(fileText, from, lineEnd, limit)
                    out.append(fileText, from, end)
                    breaks += out.length
                    out.append('\n')
                    from = end
                }
                out.append(fileText, from, lineEnd)
                if (newline < 0) break
                out.append('\n')
                lineStart = newline + 1
            }
            return Cut(out.toString(), breaks.toIntArray())
        }
    }
}
