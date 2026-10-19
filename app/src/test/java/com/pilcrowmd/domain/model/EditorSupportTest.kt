// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import com.pilcrowmd.domain.model.EditorSupport.Companion.EDIT_CEILING_CHARS
import com.pilcrowmd.domain.model.EditorSupport.Companion.HIGHLIGHT_CEILING_CHARS
import com.pilcrowmd.viewmodel.Document
import org.junit.Assert.assertEquals
import org.junit.Test

/** The editor's size tiers (M-154), pinned at both edges of each ceiling. */
class EditorSupportTest {

    @Test fun emptyIsHighlighted() = assertEquals(EditorSupport.HIGHLIGHTED, EditorSupport.of(0))

    @Test fun atTheHighlightCeilingIsHighlighted() =
        assertEquals(EditorSupport.HIGHLIGHTED, EditorSupport.of(HIGHLIGHT_CEILING_CHARS))

    @Test fun aboveTheHighlightCeilingIsPlain() =
        assertEquals(EditorSupport.PLAIN, EditorSupport.of(HIGHLIGHT_CEILING_CHARS + 1))

    @Test fun atTheEditCeilingIsPlain() = assertEquals(EditorSupport.PLAIN, EditorSupport.of(EDIT_CEILING_CHARS))

    @Test fun aboveTheEditCeilingIsReadOnly() =
        assertEquals(EditorSupport.READ_ONLY, EditorSupport.of(EDIT_CEILING_CHARS + 1))

    /** Typing a document past a ceiling must not switch its editor under the user (copy carries it). */
    @Test fun aDocumentKeepsItsTierAcrossEdits() {
        val doc = Document(uri = null, content = "x".repeat(HIGHLIGHT_CEILING_CHARS))
        val grown = doc.copy(content = doc.content + "more")
        assertEquals(EditorSupport.HIGHLIGHTED, grown.editorSupport)
    }

    @Test fun aNewDocumentTakesItsTierFromItsLength() {
        val doc = Document(uri = null, content = "x".repeat(HIGHLIGHT_CEILING_CHARS + 1))
        assertEquals(EditorSupport.PLAIN, doc.editorSupport)
    }
}
