// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

/**
 * What a document IS, recognized from its display-name extension. The v1.0.4 scope
 * fence lives here: ONLY `.txt` (case-insensitive) is [PLAIN_TEXT]; every other name — `.md`,
 * `.markdown`, `.log`, `.csv`, no extension, anything — is [MARKDOWN], i.e. today's behavior.
 */
enum class DocumentKind {
    MARKDOWN,
    PLAIN_TEXT,
    ;

    companion object {
        private const val PLAIN_TEXT_EXTENSION = ".txt"

        fun fromDisplayName(displayName: String): DocumentKind =
            if (displayName.trim().endsWith(PLAIN_TEXT_EXTENSION, ignoreCase = true)) PLAIN_TEXT else MARKDOWN
    }
}

/** How the reader pane renders the current document. Viewing-only — never touches bytes. */
enum class RenderMode {
    MARKDOWN,
    PLAIN,
}
