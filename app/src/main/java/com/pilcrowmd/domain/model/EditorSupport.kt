// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

/**
 * How much editor a document gets, by its length when it was opened (M-154). The editor costs far
 * more heap per character than the reader: Sora's buffer, a second copy of it for the highlighter,
 * and TextMate's per-line tokens and state (measured ~27 bytes per char, against the reader's ~9).
 * So above a size the editor opens without highlighting, and above a larger one not at all.
 */
enum class EditorSupport {
    /** The editor with Markdown syntax highlighting. */
    HIGHLIGHTED,

    /** The editor without highlighting: no TextMate analyzer, so no second buffer and no tokens. */
    PLAIN,

    /** Too large to edit on a phone: editor mode shows the reader, and says why. */
    READ_ONLY,
    ;

    companion object {
        /** PROPOSED, to be calibrated on a release build of the phone. */
        const val HIGHLIGHT_CEILING_CHARS = 2_000_000

        /** PROPOSED, to be calibrated on a release build of the phone. */
        const val EDIT_CEILING_CHARS = 10_000_000

        /**
         * S8-A64: the longest line the editor is given. Sora shapes a whole line on the main thread
         * to draw or edit it (one 5 MB CJK line was an ANR in Edit), so a longer file line reaches
         * the editor cut into display lines of at most this many chars (`VirtualLineBreaks`), and
         * soft wrap stays on for every document. Owner pick, 31 Oct 2026 (S8-A63 design).
         */
        const val MAX_EDITOR_LINE_CHARS = 2_000

        /** Whether any line of [text] is longer than [limit] chars, so the editor needs it cut. */
        fun needsLineBreaks(text: CharSequence, limit: Int = MAX_EDITOR_LINE_CHARS): Boolean {
            var lineStart = 0
            for (i in text.indices) {
                if (text[i] == '\n') {
                    if (i - lineStart > limit) return true
                    lineStart = i + 1
                }
            }
            return text.length - lineStart > limit
        }

        fun of(length: Int): EditorSupport = when {
            length > EDIT_CEILING_CHARS -> READ_ONLY
            length > HIGHLIGHT_CEILING_CHARS -> PLAIN
            else -> HIGHLIGHTED
        }
    }
}
