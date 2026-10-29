// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import com.pilcrowmd.ui.screen.noteToMarkSeen
import com.pilcrowmd.viewmodel.DocumentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M-162b (M5): which document the reader reports as seen, and when. Saves: SaveReportingFileRepositoryTest. */
@RunWith(RobolectricTestRunner::class)
class NoteSeenDecisionTest {

    private val index = Uri.parse("content://docs/tree/A/document/index.md")
    private val other = Uri.parse("content://docs/tree/A/document/other.md")
    private val first = DocumentId.mint()
    private val reloaded = DocumentId.mint()

    @Test
    fun `a document opened is seen`() {
        assertEquals(index, noteToMarkSeen(lastShown = null, lastId = null, shown = index, shownId = first))
    }

    @Test
    fun `the same document still shown, with no save, is not seen again`() {
        assertNull(noteToMarkSeen(lastShown = index, lastId = first, shown = index, shownId = first))
    }

    @Test
    fun `S8-A52 - the same document read again, as by Reload, is seen`() {
        assertEquals(index, noteToMarkSeen(lastShown = index, lastId = first, shown = index, shownId = reloaded))
    }

    @Test
    fun `after a rotation the same document is not seen again`() {
        // The last shown URI survives a rotation; its identity, which is never saved, does not.
        assertNull(noteToMarkSeen(lastShown = index, lastId = null, shown = index, shownId = first))
    }

    @Test
    fun `opening another document marks only the new one`() {
        assertEquals(other, noteToMarkSeen(lastShown = index, lastId = first, shown = other, shownId = reloaded))
    }

    @Test
    fun `a document closed or discarded is not seen`() {
        assertNull(noteToMarkSeen(lastShown = index, lastId = first, shown = null, shownId = null))
    }
}
