// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Layout
import android.util.AttributeSet
import android.widget.TextView

/**
 * Reader text that breaks a hyphenated word after one of its hyphens: `well-known` too wide for the
 * line becomes `well-` / `known`.
 *
 * A plain TextView does not, since Android 10. The platform's line breaker leaves the break after a
 * word's own hyphen to hyphenation, and a TextView's hyphenation is off by default from API 29 (it
 * was on, NORMAL, on 26 to 28). With it off a hyphenated word is one unbreakable word, and one too
 * wide for the line is cut wherever the line runs out, inside one of its parts, with nothing drawn
 * at the cut: `Reader-edito` / `r-writer-publisher`.
 *
 * Hyphenation also splits ordinary words by dictionary and draws a hyphen there (`head-` / `ing`),
 * and the platform has no setting for the text's own hyphens alone. So it stays off, and is turned
 * on for one text only when its lines, as measured, cut a hyphenated word away from a hyphen. That
 * text is measured a second time; every other text is measured once and looks as it did.
 *
 * The platform does not hyphenate a word longer than 45 characters at all: such a word is still cut
 * where the line runs out.
 */
@Suppress("AppCompatCustomView") // The reader's text views are plain TextViews, as its layouts inflate them.
class ReaderTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : TextView(context, attrs, defStyleAttr) {

    // Hyphenation this view turned on for the text it holds; another text starts with it off again.
    private var hyphenating = false

    override fun setText(text: CharSequence?, type: BufferType?) {
        // False too while the superclass constructor sets the first text, before the field exists.
        if (hyphenating) {
            hyphenating = false
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (hyphenationFrequency == Layout.HYPHENATION_FREQUENCY_NONE && cutsHyphenatedWord()) {
            hyphenating = true
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Whether a line ends inside a word that has a hyphen, at a place that is not after a hyphen. */
    private fun cutsHyphenatedWord(): Boolean {
        val layout = layout ?: return false
        val text = layout.text
        for (line in 0 until layout.lineCount - 1) {
            val cut = layout.getLineEnd(line)
            if (cut <= 0 || cut >= text.length) continue
            val insideWord = !text[cut - 1].isWhitespace() && !text[cut].isWhitespace()
            if (insideWord && text[cut - 1] != '-' && wordAround(text, cut).contains('-')) return true
        }
        return false
    }

    /** The run of non-blank characters holding [at], as far as the platform would ever hyphenate. */
    private fun wordAround(text: CharSequence, at: Int): CharSequence {
        var start = at
        while (start > 0 && at - start < LONGEST_HYPHENATED_WORD && !text[start - 1].isWhitespace()) start--
        var end = at
        while (end < text.length && end - at < LONGEST_HYPHENATED_WORD && !text[end].isWhitespace()) end++
        return text.subSequence(start, end)
    }

    private companion object {
        /** Minikin's LONGEST_HYPHENATED_WORD: a longer word is never hyphenated, so looking further is no use. */
        const val LONGEST_HYPHENATED_WORD = 45
    }
}
