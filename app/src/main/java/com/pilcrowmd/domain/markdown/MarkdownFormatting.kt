// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/** A formatting-bar button. **M-217.** */
sealed interface FormatAction {
    /** Wraps the selection in [marker], or takes the marker away when the selection already has it. */
    sealed class Inline(val marker: String) : FormatAction

    /** Changes only the start of every line the cursor or selection touches. */
    sealed interface LinePrefix : FormatAction

    data object Bold : Inline("**")
    data object Italic : Inline("*")
    data object InlineCode : Inline("`")
    data object Strikethrough : Inline("~~")
    data object Link : FormatAction
    data object Heading : LinePrefix
    data object BulletList : LinePrefix
    data object Checkbox : LinePrefix
    data object Quote : LinePrefix
    data object Indent : LinePrefix
    data object Outdent : LinePrefix
    data object Table : FormatAction
    data object Divider : FormatAction
}

/**
 * What a formatting-bar button writes into the editor's text. **M-217.**
 *
 * The answer is a list of [Replacement]s in the coordinates of the text as it was, sorted and never
 * overlapping, plus the selection afterwards in the coordinates of the new text. Every replacement
 * is a marker or a line prefix that the button names: text the user wrote is never rewritten
 * (Safeguard 2).
 *
 * - Inline styles wrap the selection, or remove the markers when they sit at both ends of the
 *   selection or right outside it and the text between them does not hold the marker itself. A
 *   `*` that is half of a `**` run is bold, not italic. With no selection the pair is inserted and
 *   the cursor goes between.
 * - Line buttons apply to every line the cursor or selection touches (a selection that ends at the
 *   very start of a line does not touch that line). When several lines are touched, blank ones are
 *   skipped (all blank: no edit), and a toggle removes only when every line has it; otherwise it
 *   adds it where missing. Heading takes its next level from the first line and gives it to all.
 * - Heading, Bullet and Checkbox work after any quote prefix and replace one another's marker;
 *   Quote toggles one level of the quote prefix only.
 * - Table and Divider go on their own lines after the line the selection ends on, with a blank line
 *   between them and any text on the line above.
 */
object MarkdownFormatting {

    data class Replacement(val start: Int, val end: Int, val text: String)

    data class Result(val replacements: List<Replacement>, val selectionStart: Int, val selectionEnd: Int)

    private const val INDENT = "    "
    private const val MAX_HEADING_CYCLE = 3
    private const val DIVIDER = "---"
    private const val TABLE = "| Column | Column |\n| --- | --- |\n|  |  |\n|  |  |"

    // Between the two spaces of the first body cell.
    private val TABLE_CURSOR = TABLE.indexOf("|  |") + 2

    private val HEADING = Regex("""^(#{1,6})(?:[ \t]+|$)""")

    // A quote prefix as CommonMark reads it (the rule M-168 uses), then any spaces after it.
    private val QUOTE_PREFIX = Regex("""^(?: {0,3}> ?)*[ \t]*""")

    /** The edit [action] makes on [text] with the selection [selectionStart]..[selectionEnd]. */
    fun apply(text: CharSequence, selectionStart: Int, selectionEnd: Int, action: FormatAction): Result {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        return when (action) {
            is FormatAction.Inline -> inline(text, start, end, action)
            is FormatAction.LinePrefix -> prefixLines(text, start, end, action)
            FormatAction.Link -> link(start, end)
            FormatAction.Table -> block(text, end, TABLE, TABLE_CURSOR, blankLineAfter = true)
            FormatAction.Divider -> block(text, end, DIVIDER, DIVIDER.length, blankLineAfter = false)
        }
    }

    private fun inline(text: CharSequence, start: Int, end: Int, action: FormatAction.Inline): Result {
        val marker = action.marker
        val size = marker.length
        val char = marker[0]

        // An odd run of `*` holds an italic marker; `**` alone is bold only.
        fun holds(run: Int) = run >= size && (action != FormatAction.Italic || run % 2 == 1)

        // Wrapped only when the text between the marker runs does not hold the marker: `**a** and
        // **b**` is two bold spans, not one.
        fun plainBetween(from: Int, to: Int) = from <= to && !text.subSequence(from, to).contains(marker)
        val runIn = runForward(text, start, end, char)
        val runInEnd = runBackward(text, end, start, char)

        return when {
            start == end -> Result(listOf(Replacement(start, start, marker + marker)), start + size, start + size)
            holds(runIn) && holds(runInEnd) && plainBetween(start + runIn, end - runInEnd) ->
                Result(
                    listOf(Replacement(start, start + size, ""), Replacement(end - size, end, "")),
                    start,
                    end - 2 * size,
                )
            holds(runBackward(text, start, 0, char)) &&
                holds(runForward(text, end, text.length, char)) &&
                plainBetween(start, end) ->
                Result(
                    listOf(Replacement(start - size, start, ""), Replacement(end, end + size, "")),
                    start - size,
                    end - size,
                )
            else -> Result(
                listOf(Replacement(start, start, marker), Replacement(end, end, marker)),
                start + size,
                end + size,
            )
        }
    }

    private fun link(start: Int, end: Int): Result = if (start == end) {
        Result(listOf(Replacement(start, start, "[]()")), start + 1, start + 1)
    } else {
        // After "[", the selection and "](", the cursor sits inside the parentheses.
        val cursor = end + "[](".length
        Result(listOf(Replacement(start, start, "["), Replacement(end, end, "]()")), cursor, cursor)
    }

    /**
     * One line of the text: [start] to [end], without its newline. [afterIndent] is what follows the
     * leading indent; the body, after the indent and any quote prefix, starts at [bodyStart]. [kind]
     * is the heading or list marker that opens the body, if any.
     */
    private class Line(text: CharSequence, val start: Int, val end: Int) {
        val indentEnd = start + text.subSequence(start, end).takeWhile { it == ' ' || it == '\t' }.length
        val afterIndent = text.subSequence(indentEnd, end).toString()
        val isBlank = afterIndent.isEmpty()
        val bodyStart = indentEnd + QUOTE_PREFIX.find(afterIndent)?.value.orEmpty().length
        private val body = text.subSequence(bodyStart, end).toString()
        private val heading = HEADING.find(body)
        val headingLevel = heading?.groupValues?.get(1)?.length ?: 0
        val kind = heading?.value ?: body.take(ListContinuation.listMarkerLength(body))
        val isTask = heading == null && '[' in kind
        val isBullet = heading == null && kind.startsWith('-') && !isTask

        /** Replaces the heading or list marker that opens the body with [prefix]. */
        fun setKind(prefix: String) = Replacement(bodyStart, bodyStart + kind.length, prefix)
    }

    private fun prefixLines(text: CharSequence, start: Int, end: Int, action: FormatAction.LinePrefix): Result {
        val touched = touchedLines(text, start, end)
        val lines = if (touched.size > 1) touched.filterNot { it.isBlank } else touched
        // Several lines, all blank: nothing to format.
        if (lines.isEmpty()) return Result(emptyList(), start, end)
        val replacements = when (action) {
            FormatAction.Heading -> heading(lines)
            FormatAction.BulletList -> toggleKind(lines, "- ") { it.isBullet }
            FormatAction.Checkbox -> toggleKind(lines, "- [ ] ") { it.isTask }
            FormatAction.Quote -> quote(lines)
            FormatAction.Indent -> lines.map { Replacement(it.start, it.start, INDENT) }
            FormatAction.Outdent -> lines.mapNotNull { outdent(text, it) }
        }
        return Result(replacements, mapOffset(start, replacements), mapOffset(end, replacements))
    }

    /** [lines] is never empty here. */
    private fun heading(lines: List<Line>): List<Replacement> {
        val first = lines.first().headingLevel
        val next = if (first >= MAX_HEADING_CYCLE) 0 else first + 1
        val prefix = if (next == 0) "" else "#".repeat(next) + " "
        return lines.filter { it.kind != prefix }.map { it.setKind(prefix) }
    }

    /** Takes the line kind off every line when all are [has]; else gives it to the rest as [prefix]. */
    private fun toggleKind(lines: List<Line>, prefix: String, has: (Line) -> Boolean): List<Replacement> {
        val allHave = lines.all(has)
        return lines.filter { has(it) == allHave }.map { it.setKind(if (allHave) "" else prefix) }
    }

    private fun quote(lines: List<Line>): List<Replacement> {
        val allQuoted = lines.all { it.afterIndent.startsWith('>') }
        return lines.filter { it.afterIndent.startsWith('>') == allQuoted }.map { line ->
            if (allQuoted) {
                // One level: the `>` and the single space after it, if there is one.
                val level = if (line.afterIndent.startsWith("> ")) 2 else 1
                Replacement(line.indentEnd, line.indentEnd + level, "")
            } else {
                Replacement(line.indentEnd, line.indentEnd, "> ")
            }
        }
    }

    private fun outdent(text: CharSequence, line: Line): Replacement? {
        val removed = if (line.indentEnd > line.start && text[line.start] == '\t') {
            1
        } else {
            text.subSequence(line.start, line.indentEnd).takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
        }
        return if (removed == 0) null else Replacement(line.start, line.start + removed, "")
    }

    /**
     * Inserts [block] on its own lines after the line holding [at] (or on that line when it is
     * empty), with the cursor at [cursor] in it. Text on the line right above the block gets a blank
     * line between: a paragraph line above `---` would make it a setext heading, and one above a
     * table would keep the table from starting.
     */
    private fun block(text: CharSequence, at: Int, block: String, cursor: Int, blankLineAfter: Boolean): Result {
        val line = Line(text, lineStart(text, at), lineEnd(text, at))
        val lineAbove = when {
            line.start < line.end -> line
            line.start > 0 -> Line(text, lineStart(text, line.start - 1), line.start - 1)
            else -> null
        }
        val newline = if (line.start < line.end) "\n" else ""
        val blankLine = if (lineAbove != null && !lineAbove.isBlank) "\n" else ""
        val before = newline + blankLine
        val nextLineHasText = line.end < text.length &&
            !Line(text, line.end + 1, lineEnd(text, line.end + 1)).isBlank
        val after = if (blankLineAfter && nextLineHasText) "\n" else ""
        val offset = line.end + before.length + cursor
        return Result(listOf(Replacement(line.end, line.end, before + block + after)), offset, offset)
    }

    private fun touchedLines(text: CharSequence, start: Int, end: Int): List<Line> {
        // A selection that ends right after a newline does not reach into the next line.
        val last = lineStart(text, if (end > start && text[end - 1] == '\n') end - 1 else end)
        val lines = mutableListOf<Line>()
        var from = lineStart(text, start)
        while (from <= last) {
            val to = lineEnd(text, from)
            lines += Line(text, from, to)
            from = to + 1
        }
        return lines
    }
}

/** [offset] in the text after [replacements]; inside a replaced prefix it moves to the prefix end. */
private fun mapOffset(offset: Int, replacements: List<MarkdownFormatting.Replacement>): Int {
    var shift = 0
    for (r in replacements) {
        when {
            offset < r.start -> break
            offset >= r.end -> shift += r.text.length - (r.end - r.start)
            else -> return r.start + shift + r.text.length
        }
    }
    return offset + shift
}

private fun lineStart(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i > 0 && text[i - 1] != '\n') i--
    return i
}

private fun lineEnd(text: CharSequence, offset: Int): Int {
    var i = offset
    while (i < text.length && text[i] != '\n') i++
    return i
}

private fun runForward(text: CharSequence, from: Int, limit: Int, char: Char): Int {
    var i = from
    while (i < limit && text[i] == char) i++
    return i - from
}

private fun runBackward(text: CharSequence, from: Int, limit: Int, char: Char): Int {
    var i = from
    while (i > limit && text[i - 1] == char) i--
    return from - i
}
