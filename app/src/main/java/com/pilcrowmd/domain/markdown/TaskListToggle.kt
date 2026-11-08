// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/**
 * Ticks a task-list box on or off in the Markdown source: `- [ ] a` becomes `- [x] a` and back.
 *
 * The reader counts boxes per block, in the order they are drawn, which is the order their lines
 * appear in the block's source. So a tap on the [ordinal]-th box of a block is the [ordinal]-th task
 * line between the block's start and the next block's start. Lines inside a fenced code block are
 * not task lines, as the reader does not draw boxes for them.
 *
 * Only the one character between the brackets changes (Safeguard 2). Null when the line is not
 * there, or when [expectedCount] boxes were drawn and the source does not hold that many: then the
 * reader and this count disagree, and the text is left alone rather than the wrong line changed.
 */
object TaskListToggle {

    // Indent, any quote prefix, a list marker, then the box: the shape Markwon's TaskListPlugin reads.
    private val TASK_LINE = Regex("""^[ \t]*(?:>[ \t]*)*(?:[-*+]|\d{1,9}[.)])[ \t]+\[([ xX])\](?=[ \t]|$)""")
    private val FENCE = Regex("""^[ \t]*(?:>[ \t]*)*(`{3,}|~{3,})""")

    /** The source with the box of the [ordinal]-th (0-based) task line in `[from, to)` flipped, or null. */
    fun toggle(content: String, from: Int, to: Int, ordinal: Int, expectedCount: Int? = null): String? {
        val boxes = taskBoxOffsets(content, from, to)
        if (expectedCount != null && boxes.size != expectedCount) return null
        val at = boxes.getOrNull(ordinal) ?: return null
        val checked = content[at] != ' '
        return content.substring(0, at) + (if (checked) ' ' else 'x') + content.substring(at + 1)
    }

    /** The offset of the character inside each task box's brackets in `[from, to)`, in order. */
    fun taskBoxOffsets(content: String, from: Int, to: Int): List<Int> {
        val out = ArrayList<Int>()
        var lineStart = from.coerceIn(0, content.length)
        val end = to.coerceIn(lineStart, content.length)
        var fence: String? = null
        while (lineStart < end) {
            var lineEnd = content.indexOf('\n', lineStart)
            if (lineEnd < 0 || lineEnd > end) lineEnd = end
            val line = content.substring(lineStart, lineEnd)
            val fenceHere = FENCE.find(line)?.groupValues?.get(1)
            when {
                fence != null -> {
                    val closes = fenceHere != null && fenceHere[0] == fence[0] && fenceHere.length >= fence.length
                    if (closes) fence = null
                }
                fenceHere != null -> fence = fenceHere
                else -> TASK_LINE.find(line)?.let { m -> out += lineStart + m.groups[1]!!.range.first }
            }
            lineStart = lineEnd + 1
        }
        return out
    }
}
