// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/** A place in the reader: a block (adapter position) and a character offset in the text it shows. */
data class TextPoint(val block: Int, val offset: Int) : Comparable<TextPoint> {
    override fun compareTo(other: TextPoint): Int =
        if (block != other.block) block.compareTo(other.block) else offset.compareTo(other.offset)

    companion object {
        /** The offset of a block's end, whatever its length; [ReaderSelectionRange] clamps it. */
        const val END = Int.MAX_VALUE
    }
}

/**
 * A reader selection that may run across blocks, from [anchor] (where it began) to [focus] (the end
 * that moves). Either may come first.
 */
data class ReaderSelectionRange(val anchor: TextPoint, val focus: TextPoint) {
    val start: TextPoint get() = minOf(anchor, focus)
    val end: TextPoint get() = maxOf(anchor, focus)

    /** The selected part `[first, second)` of [block], whose text is [length] long; null when outside. */
    fun rangeIn(block: Int, length: Int): Pair<Int, Int>? {
        if (block < start.block || block > end.block) return null
        val from = if (block == start.block) start.offset.coerceIn(0, length) else 0
        val to = if (block == end.block) end.offset.coerceIn(0, length) else length
        return if (to > from) from to to else null
    }

    /**
     * The selected text: each block's part, one blank line between blocks. A part loses its object
     * placeholders (pictures) and the padding around it: line breaks, and the no-break-space lines
     * Markwon puts above and below a code block. [textOf] gives a block's shown text, null for none.
     */
    fun text(textOf: (Int) -> CharSequence?): String = (start.block..end.block)
        .mapNotNull { block ->
            val text = textOf(block) ?: return@mapNotNull null
            val (from, to) = rangeIn(block, text.length) ?: return@mapNotNull null
            text.subSequence(from, to).toString().replace(OBJECT, "").trim { it == '\n' || it == NO_BREAK_SPACE }
        }
        .filter { it.isNotBlank() }
        .joinToString(BLOCK_SEPARATOR)

    companion object {
        const val BLOCK_SEPARATOR = "\n\n"
        private const val OBJECT = "\uFFFC"
        private const val NO_BREAK_SPACE = '\u00A0'

        /**
         * The word around [offset] in [text], as `[first, second)`: letters, digits, `_`, and an
         * apostrophe between letters. Off a word, the one character there; past the end, the last.
         */
        fun wordAt(text: CharSequence, offset: Int): Pair<Int, Int> {
            if (text.isEmpty()) return 0 to 0
            var at = offset.coerceIn(0, text.length - 1)
            // A press just past a word's last letter means that word.
            if (!isWordChar(text, at) && at > 0 && isWordChar(text, at - 1)) at--
            if (!isWordChar(text, at)) return at to at + 1
            var from = at
            while (from > 0 && isWordChar(text, from - 1)) from--
            var to = at + 1
            while (to < text.length && isWordChar(text, to)) to++
            return from to to
        }

        private fun isWordChar(text: CharSequence, i: Int): Boolean {
            val c = text[i]
            if (c.isLetterOrDigit() || c == '_') return true
            val apostrophe = c == '\'' || c == '’'
            return apostrophe && i > 0 && i < text.length - 1 && text[i - 1].isLetter() && text[i + 1].isLetter()
        }
    }
}
