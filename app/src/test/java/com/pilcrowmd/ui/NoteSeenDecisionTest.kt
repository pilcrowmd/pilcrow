// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import com.pilcrowmd.ui.screen.noteToMarkSeen
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

    @Test
    fun `a document opened is seen`() {
        assertEquals(index, noteToMarkSeen(lastShown = null, shown = index))
    }

    @Test
    fun `the same document still shown, with no save, is not seen again`() {
        // Also what a rotation looks like: the last shown document is remembered across it.
        assertNull(noteToMarkSeen(lastShown = index, shown = index))
    }

    @Test
    fun `opening another document marks only the new one`() {
        assertEquals(other, noteToMarkSeen(lastShown = index, shown = other))
    }

    @Test
    fun `a document closed or discarded is not seen`() {
        assertNull(noteToMarkSeen(lastShown = index, shown = null))
    }
}
