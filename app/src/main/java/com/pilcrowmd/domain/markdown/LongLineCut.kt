// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import java.text.BreakIterator

/**
 * Where to cut a run of text too long for one reader TextView (S8-A61). A TextView breaks a
 * paragraph into lines on the main thread, and that work grows with the length of the paragraph,
 * not of the screen: one unbroken line of 200 KB blocked the main thread for about 8 s in minikin's
 * line breaker (the Play Vitals ANR, reproduced in S8-A59). The splitters that already bound a
 * block by counts ([ParagraphSplitter], [PlainTextChunks]) also bound it by [MAX_BLOCK_CHARS],
 * cutting where [pieceEnd] says. Display only: the text in memory and the saved bytes are untouched.
 */
object LongLineCut {
    /**
     * Characters one reader block may lay out. Measured on the S24plus_API36 emulator (S8-A61 report):
     * one unspaced block of 8K, 16K or 32K chars draws within the noise of opening any file, against
     * 7.7 s for 200 KB; this is the conservative end of that range, and the same order as
     * [CodeBlockSplitter.PIECE_CHARS].
     */
    const val MAX_BLOCK_CHARS = 8_000

    /**
     * The end of the piece of [text] that starts at [from] and may run to [end]: [end] itself when
     * the rest fits in [limit] chars; else just after the last whitespace in the window, so the next
     * piece starts on a word (not a no-break space, nor a space a combining mark follows); else the
     * last character boundary in the window, so a surrogate pair, a combining sequence or an emoji
     * sequence is never split. Always past [from].
     *
     * A single character cluster longer than [limit] (a letter with thousands of combining marks)
     * has no boundary in the window; it is cut at the limit anyway, outside a surrogate pair, because
     * a bounded block matters more than one malformed cluster.
     */
    fun pieceEnd(text: String, from: Int, end: Int = text.length, limit: Int = MAX_BLOCK_CHARS): Int {
        val target = from + limit
        if (end <= target) return end
        for (i in target - 1 downTo from) {
            if (isCutPoint(text, i)) return i + 1
        }
        // Only the window and a little after it: [from] is where the last piece ended, a boundary, and
        // whether [target] is one depends on a few characters either side, never on the whole line.
        val window = text.substring(from, minOf(end, target + LOOKAHEAD))
        val characters = BreakIterator.getCharacterInstance().also { it.setText(window) }
        val inWindow = if (characters.isBoundary(limit)) limit else characters.preceding(limit)
        if (inWindow > 0) return from + inWindow
        return if (text[target - 1].isHighSurrogate() && text[target].isLowSurrogate()) target + 1 else target
    }

    /** Whitespace at [i] that a cut may follow: a breaking space with no combining mark after it. */
    private fun isCutPoint(text: String, i: Int): Boolean {
        val c = text[i]
        val breaking = c.isWhitespace() && c != '\u00A0' && c != '\u2007' && c != '\u202F'
        return breaking && !isCombiningMark(text[i + 1])
    }

    private fun isCombiningMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        -> true
        else -> false
    }

    /** Characters past the window the boundary rules may look at: an emoji sequence is a few long. */
    private const val LOOKAHEAD = 32
}
