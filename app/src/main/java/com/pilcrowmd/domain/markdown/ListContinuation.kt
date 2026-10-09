// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/**
 * What Enter writes on a Markdown list or quote line in the editor. **M-168.**
 *
 * A line is read as indent, then any quote prefix (`> `, `> > `), then at most one list marker
 * (`- `, `* `, `+ `, `N. `, `N) `, optionally followed by a task box `[ ]`). Enter after that prefix
 * writes the next one: the same indent and quote prefix, the same bullet or the next number with its
 * delimiter and at least its digit count (`007.` -> `008.`), and a task box always unchecked. Enter
 * on an item with nothing after its prefix clears the line instead, which ends the list.
 *
 * Only ever adds a marker or removes one the user is ending: text the user wrote is never rewritten
 * (Safeguard 2). Later ordered items are not renumbered.
 */
object ListContinuation {

    /** What the editor does instead of its plain Enter. */
    sealed interface Action {
        /** Insert [insertText] at the cursor: a newline and the next prefix. */
        data class Continue(val insertText: String) : Action

        /** Delete the cursor line from [deleteStartColumn] to [deleteEndColumn], and insert nothing. */
        data class EndList(val deleteStartColumn: Int, val deleteEndColumn: Int) : Action
    }

    // The part every following line repeats: indent, then any quote prefix with its spaces.
    private val LEAD = Regex("""^([ \t]*)((?:>[ \t]*)*)""")

    // A marker must be followed by a space or tab, so `-foo`, `**bold**` and `1.5` are not markers.
    private val BULLET = Regex("""^[-*+][ \t]+""")
    private val ORDERED = Regex("""^(\d{1,9})([.)][ \t]+)""")
    private val TASK_BOX = Regex("""^\[[ xX]](?:[ \t]+|$)""")

    // A quote prefix as CommonMark reads it: up to three spaces, `>`, one optional space; repeated.
    private val QUOTE_PREFIX = Regex("""^(?: {0,3}> ?)*""")

    // `---`, `- - -`, `* * *`: a thematic break, which only looks like a list item.
    private val THEMATIC_BREAK = Regex("""^([-*_])[ \t]*(?:\1[ \t]*){2,}$""")

    // At most three spaces of indent: four or more (or a tab) is indented code, not a fence.
    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

    /** A list marker: its length as written, and the marker the next item gets. */
    private class Marker(val length: Int, val next: String)

    /**
     * The action for Enter at [column] of [line], or null for a plain Enter. [linesAbove] are the
     * lines before [line], read only when [line] has a prefix, to tell whether it sits inside a fenced
     * code block (where nothing is continued).
     */
    fun onEnter(line: String, column: Int, linesAbove: Sequence<String>): Action? {
        val (indent, quote) = LEAD.find(line)?.destructured ?: return null
        val body = line.substring(indent.length + quote.length)
        val marker = if (THEMATIC_BREAK.matches(body)) null else listMarker(body)
        val prefixEnd = indent.length + quote.length + (marker?.length ?: 0)
        return when {
            quote.isEmpty() && marker == null -> null
            column < prefixEnd -> null
            isInsideFence(linesAbove) -> null
            line.substring(prefixEnd).isBlank() -> Action.EndList(0, line.length)
            else -> Action.Continue("\n$indent$quote${marker?.next.orEmpty()}")
        }
    }

    /**
     * The length of the list marker at the start of [body] (a line with its indent and quote prefix
     * removed), counting any task box and the spaces after it; 0 when it has none. The editor's
     * formatting bar (M-217) replaces or removes exactly this much.
     */
    internal fun listMarkerLength(body: String): Int =
        if (THEMATIC_BREAK.matches(body)) 0 else listMarker(body)?.length ?: 0

    private fun listMarker(body: String): Marker? {
        val bullet = BULLET.find(body)
        val ordered = ORDERED.find(body)
        val (written, next) = when {
            bullet != null -> bullet.value to bullet.value
            ordered != null -> {
                val (number, delimiter) = ordered.destructured
                // The width never shrinks, so `09.` -> `10.` and `007.` -> `008.`.
                ordered.value to "${(number.toLong() + 1).toString().padStart(number.length, '0')}$delimiter"
            }
            else -> return null
        }
        val task = TASK_BOX.find(body.substring(written.length))
        return if (task == null) {
            Marker(written.length, next)
        } else {
            Marker(written.length + task.value.length, "$next[ ] ")
        }
    }

    /**
     * Whether a fenced code block opened in [linesAbove] is still open after the last of them. A fence
     * is three or more backticks or tildes after any quote prefix and at most three spaces; it closes
     * on a line of the same character, at least as long, with nothing after it. A backtick fence's info
     * text cannot contain a backtick.
     */
    fun isInsideFence(linesAbove: Sequence<String>): Boolean {
        var open: String? = null
        // Only a line with a backtick or tilde can be a fence; the rest skip the regex work.
        for (raw in linesAbove.filter { '`' in it || '~' in it }) {
            val (fence, rest) = FENCE.find(raw.replaceFirst(QUOTE_PREFIX, ""))?.destructured ?: continue
            val current = open
            open = when {
                current == null -> fence.takeUnless { it[0] == '`' && '`' in rest }
                fence[0] == current[0] && fence.length >= current.length && rest.isBlank() -> null
                else -> current
            }
        }
        return open != null
    }
}
