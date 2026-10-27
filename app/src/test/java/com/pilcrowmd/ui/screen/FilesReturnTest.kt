// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A38: closing a note that was opened from My folders returns to My folders; a note opened any
 * other way closes to Welcome as before. [filesReturnStep] is fed the way the screen feeds it: once
 * per change of the open document, with the note the user picked in My folders.
 *
 * Each case replays a whole sequence, so the guard that answers is the one the sequence reaches: the
 * "other way" cases open a DIFFERENT note than the one picked, so only the identity check can tell.
 */
@RunWith(RobolectricTestRunner::class)
class FilesReturnTest {

    private val a = Uri.parse("content://t/a.md")
    private val b = Uri.parse("content://t/b.md")
    private val c = Uri.parse("content://t/c.md")

    /** The screen's state across a run of document changes: what is open, what My folders was asked for. */
    private class Screen(var openedFromFiles: Uri? = null, var pending: Uri? = null, var shown: Uri? = null) {
        var reopenedFiles = false

        fun pickInFiles(uri: Uri) {
            if (uri == shown) openedFromFiles = uri else pending = uri
        }

        /** The pick's open was cancelled (the prompt dismissed) or failed to load. */
        fun forgetPick() {
            pending = null
        }

        /** The open document becomes [uri] (null: closed), as the effect sees it. */
        fun documentBecomes(uri: Uri?) {
            val step = filesReturnStep(openedFromFiles, pending, shown, uri)
            openedFromFiles = step.openedFromFiles
            pending = step.pending
            reopenedFiles = step.reopenFiles
            shown = uri
        }
    }

    @Test
    fun closingANoteOpenedFromMyFoldersReopensMyFolders() {
        val screen = Screen()
        screen.pickInFiles(b)
        screen.documentBecomes(b)
        assertFalse("opening it must not send the user back to the list", screen.reopenedFiles)
        screen.documentBecomes(null)
        assertTrue(screen.reopenedFiles)
        assertNull("one return per open", screen.openedFromFiles)
    }

    @Test
    fun aNoteOpenedAnyOtherWayClosesToWelcome() {
        val screen = Screen()
        screen.documentBecomes(a)
        screen.documentBecomes(null)
        assertFalse(screen.reopenedFiles)
    }

    @Test
    fun theNoteReturnsOnlyOnceItHasActuallyOpened() {
        // The open waits on the unsaved-changes prompt: the dirty note stays shown, then b replaces it.
        val screen = Screen()
        screen.documentBecomes(a)
        screen.pickInFiles(b)
        assertNull("not counted until it lands", screen.openedFromFiles)
        assertEquals(b, screen.pending)
        screen.documentBecomes(b)
        assertEquals(b, screen.openedFromFiles)
        screen.documentBecomes(null)
        assertTrue(screen.reopenedFiles)
    }

    @Test
    fun theDirtyNoteClosingWhileTheOpenWaitsDoesNotReopenMyFolders() {
        val screen = Screen()
        screen.documentBecomes(a)
        screen.pickInFiles(b)
        screen.documentBecomes(null) // a closed, b has not opened: a was not opened from My folders
        assertFalse(screen.reopenedFiles)
        assertEquals("b is still awaited", b, screen.pending)
    }

    @Test
    fun aNoteReachedFromTheOneOpenedFromMyFoldersGoesBackToWelcome() {
        val screen = Screen()
        screen.pickInFiles(b)
        screen.documentBecomes(b)
        screen.documentBecomes(c) // a followed link, or a Recent
        assertNull(screen.openedFromFiles)
        screen.documentBecomes(null)
        assertFalse(screen.reopenedFiles)
    }

    @Test
    fun theSameNoteOpenedLaterByAnotherWayIsNotRemembered() {
        val screen = Screen()
        screen.pickInFiles(b)
        screen.documentBecomes(b)
        screen.documentBecomes(null) // back to My folders; the user leaves it
        assertTrue(screen.reopenedFiles)
        screen.documentBecomes(b) // from Recent
        screen.documentBecomes(null)
        assertFalse(screen.reopenedFiles)
    }

    @Test
    fun noChangeOfDocumentChangesNothing() {
        val step = filesReturnStep(openedFromFiles = b, pending = c, shown = a, current = a)
        assertEquals(FilesReturnStep(b, c, reopenFiles = false), step)
    }

    @Test
    fun aPickCancelledWhileTheNoteOpenedFromMyFoldersIsStillOpenKeepsItsReturn() {
        // a is open from My folders, b is picked over it, and the unsaved-changes prompt is dismissed.
        val screen = Screen()
        screen.pickInFiles(a)
        screen.documentBecomes(a)
        screen.pickInFiles(b)
        assertEquals("the pick must not take a's flag", a, screen.openedFromFiles)
        screen.forgetPick()
        screen.documentBecomes(null)
        assertTrue(screen.reopenedFiles)
    }

    @Test
    fun aPickCancelledThenOpenedAnotherWayClosesToWelcome() {
        val screen = Screen()
        screen.pickInFiles(a)
        screen.documentBecomes(a)
        screen.pickInFiles(b)
        screen.forgetPick()
        screen.documentBecomes(b) // from Recent: a is replaced, b was never opened from My folders
        screen.documentBecomes(null)
        assertFalse(screen.reopenedFiles)
    }

    @Test
    fun aPickOverTheOpenNoteThatThenOpensTakesOverTheReturn() {
        val screen = Screen()
        screen.pickInFiles(a)
        screen.documentBecomes(a)
        screen.pickInFiles(b) // Save or Discard: b replaces a
        screen.documentBecomes(b)
        assertEquals(b, screen.openedFromFiles)
        assertNull(screen.pending)
        screen.documentBecomes(null)
        assertTrue(screen.reopenedFiles)
    }

    @Test
    fun theFirstCompositionAfterProcessDeathIsNotACloseOfTheNote() {
        // The ViewModel is empty and nothing has been shown yet: no change, so no return.
        val step = filesReturnStep(openedFromFiles = a, pending = null, shown = null, current = null)
        assertEquals(FilesReturnStep(a, null, reopenFiles = false), step)
        // The restore then lands a, which the saved flag still names: it keeps counting.
        val landed = filesReturnStep(openedFromFiles = a, pending = null, shown = null, current = a)
        assertEquals(FilesReturnStep(a, null, reopenFiles = false), landed)
    }
}
