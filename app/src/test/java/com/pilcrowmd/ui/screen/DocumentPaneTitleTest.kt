// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.EditorSupport
import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-309: the pane title of an open document. A document too large to edit (M-154) stays in the
 * reader when the mode is Editor, and its title must say so: it names what is on screen.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPaneTitleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readerModeIsTheReader() {
        assertEquals("Reader, a.md", documentPaneTitle(context, ViewMode.READER, EditorSupport.HIGHLIGHTED, "a.md"))
    }

    @Test
    fun editorModeIsTheEditor() {
        assertEquals("Editor, a.md", documentPaneTitle(context, ViewMode.EDITOR, EditorSupport.HIGHLIGHTED, "a.md"))
    }

    @Test
    fun aReadOnlyDocumentInEditorModeShowsTheReader() {
        assertEquals("Reader, big.md", documentPaneTitle(context, ViewMode.EDITOR, EditorSupport.READ_ONLY, "big.md"))
    }
}
