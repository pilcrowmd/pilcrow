// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import com.pilcrowmd.domain.model.EditorSupport
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M-309: the pane title of an open document. A document too large to edit (M-154) stays in the
 * reader when the mode is Editor, and its title must say so: it names what is on screen.
 */
class DocumentPaneTitleTest {

    @Test
    fun readerModeIsTheReader() {
        assertEquals("Reader, a.md", documentPaneTitle(ViewMode.READER, EditorSupport.HIGHLIGHTED, "a.md"))
    }

    @Test
    fun editorModeIsTheEditor() {
        assertEquals("Editor, a.md", documentPaneTitle(ViewMode.EDITOR, EditorSupport.HIGHLIGHTED, "a.md"))
    }

    @Test
    fun aReadOnlyDocumentInEditorModeShowsTheReader() {
        assertEquals("Reader, big.md", documentPaneTitle(ViewMode.EDITOR, EditorSupport.READ_ONLY, "big.md"))
    }
}
