// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The scope fence, pinned: ONLY `.txt` (case-insensitive) is plain text. */
class DocumentKindTest {

    private fun kind(name: String) = DocumentKind.fromDisplayName(name)

    @Test fun txtLower() = assertEquals(DocumentKind.PLAIN_TEXT, kind("notes.txt"))

    @Test fun txtUpper() = assertEquals(DocumentKind.PLAIN_TEXT, kind("NOTES.TXT"))

    @Test fun txtMixed() = assertEquals(DocumentKind.PLAIN_TEXT, kind("Notes.Txt"))

    @Test fun mdThenTxt() = assertEquals(DocumentKind.PLAIN_TEXT, kind("a.md.txt"))

    @Test fun md() = assertEquals(DocumentKind.MARKDOWN, kind("readme.md"))

    @Test fun markdown() = assertEquals(DocumentKind.MARKDOWN, kind("readme.markdown"))

    @Test fun mdown() = assertEquals(DocumentKind.MARKDOWN, kind("readme.mdown"))

    @Test fun txtThenMd() = assertEquals(DocumentKind.MARKDOWN, kind("a.txt.md"))

    // The fence: adjacent plain-ish extensions stay MARKDOWN (today's behavior) until widened.
    @Test fun log() = assertEquals(DocumentKind.MARKDOWN, kind("app.log"))

    @Test fun csv() = assertEquals(DocumentKind.MARKDOWN, kind("data.csv"))

    @Test fun textExtension() = assertEquals(DocumentKind.MARKDOWN, kind("notes.text"))

    @Test fun noExtension() = assertEquals(DocumentKind.MARKDOWN, kind("README"))

    @Test fun trailingDot() = assertEquals(DocumentKind.MARKDOWN, kind("weird."))

    @Test fun emptyName() = assertEquals(DocumentKind.MARKDOWN, kind(""))

    // "txt" without the dot is not an extension match.
    @Test fun bareTxtWord() = assertEquals(DocumentKind.MARKDOWN, kind("txt"))

    @Test fun surroundingWhitespace() = assertEquals(DocumentKind.PLAIN_TEXT, kind(" notes.txt "))
}
