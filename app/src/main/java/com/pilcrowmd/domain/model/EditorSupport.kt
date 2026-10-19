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

        fun of(length: Int): EditorSupport = when {
            length > EDIT_CEILING_CHARS -> READ_ONLY
            length > HIGHLIGHT_CEILING_CHARS -> PLAIN
            else -> HIGHLIGHTED
        }
    }
}
