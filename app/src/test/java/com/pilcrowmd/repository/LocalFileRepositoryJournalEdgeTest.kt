// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

/**
 * Edges of the read, save and recovery paths that the atomic-save and stranded-slot suites leave
 * open. Each test here was written against a mutant that survived those suites (mutation testing,
 * hardening item (d)), and was seen to fail with the mutant applied.
 *
 * The journal states below are built the way a crash leaves them: a save that dies after the "wt"
 * open (content + uri + marker durable), then — for the incomplete-staging cases — one of the slot's
 * files removed, which is exactly what a death between two of [PendingSaveJournal.stage]'s writes
 * leaves behind.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryJournalEdgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private val uri: Uri = Uri.parse("content://test/document/doc.md")

    @Before
    fun setup() {
        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
    }

    private fun repo() = LocalFileRepository(resolver, walBaseDir)

    private fun pendingFiles(suffix: String): List<File> =
        File(walBaseDir, "pending_saves").listFiles()?.filter { it.name.endsWith(suffix) } ?: emptyList()

    private fun readablePfd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /** A save of [content] that dies after the "wt" open truncated [target]: a recoverable slot. */
    private fun crashMidCommit(target: File, content: String) = runBlocking {
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            FileOutputStream(target).close() // the truncation a real "wt" open performs
            readablePfd(target) // ...then the write fails
        }
        assertTrue("fixture: the save must fail", repo().saveFile(uri, content).isFailure)
        assertEquals("fixture: one staged content file", 1, pendingFiles(".content").size)
        assertEquals("fixture: one commit marker", 1, pendingFiles(".committing").size)
    }

    // --- readFile -------------------------------------------------------------------------------

    @Test
    fun readReturnsTheTargetBytesWhenNothingIsStaged() = runBlocking {
        val target = tempFolder.newFile("plain.md").apply { writeText("ON DISK\r\nsecond line") }
        every { resolver.openFileDescriptor(uri, "r") } answers { readablePfd(target) }

        val read = repo().readFile(uri)

        assertEquals("ON DISK\r\nsecond line", read.getOrNull()?.content)
    }

    @Test
    fun readFailsCleanlyWhenTheProviderReturnsNoDescriptor() = runBlocking {
        every { resolver.openFileDescriptor(uri, "r") } returns null

        val read = repo().readFile(uri)

        assertTrue("a null descriptor is a failed read, not empty content", read.isFailure)
    }

    /**
     * Content staged but no commit marker: the commit never began, so the TARGET is intact and is
     * the truth. Serving the staged bytes would show text the user never saved to that file.
     */
    @Test
    fun readIgnoresAStagedCopyThatHasNoCommitMarker() = runBlocking {
        val target = tempFolder.newFile("doc.md")
        crashMidCommit(target, "STAGED, NEVER COMMITTED")
        pendingFiles(".committing").single().delete()
        target.writeText("THE TARGET")
        every { resolver.openFileDescriptor(uri, "r") } answers { readablePfd(target) }

        assertEquals("THE TARGET", repo().readFile(uri).getOrNull()?.content)
    }

    // --- saveFile -------------------------------------------------------------------------------

    /**
     * A provider may return null from openFileDescriptor instead of throwing. The target was never
     * opened, so the slot must go: a slot left behind would be re-applied over the file on the next
     * launch, overwriting whatever it holds by then.
     */
    @Test
    fun aNullWriteDescriptorFailsTheSaveAndLeavesNoSlot() = runBlocking {
        every { resolver.openFileDescriptor(uri, "wt") } returns null

        val result = repo().saveFile(uri, "NEW")

        assertTrue(result.isFailure)
        assertTrue("no staged content may survive", pendingFiles(".content").isEmpty())
        assertTrue("no commit marker may survive", pendingFiles(".committing").isEmpty())
        assertEquals("nothing to recover on the next launch", 0, repo().recoverPendingSaves().getOrThrow())
    }

    // --- recoverPendingSaves / strandedSlots -----------------------------------------------------

    /**
     * No commit marker = the commit never began (or already finished), so the target was never
     * truncated. Recovery must drop the slot WITHOUT writing: a stale re-apply would overwrite a file
     * that may have been edited since.
     */
    @Test
    fun recoveryDropsASlotWithoutCommitMarkerAndNeverTouchesTheTarget() = runBlocking {
        val target = tempFolder.newFile("doc.md")
        crashMidCommit(target, "STALE STAGED")
        pendingFiles(".committing").single().delete()
        target.writeText("EDITED ELSEWHERE SINCE")

        val recovered = repo().recoverPendingSaves().getOrThrow()

        assertEquals(0, recovered)
        assertEquals("EDITED ELSEWHERE SINCE", target.readText())
        assertTrue("the incomplete slot is dropped", pendingFiles(".content").isEmpty())
        verify(exactly = 1) { resolver.openFileDescriptor(uri, "wt") } // the fixture's, none since
    }

    @Test
    fun recoveryDropsASlotWhoseUriFileIsMissing() = runBlocking {
        val target = tempFolder.newFile("doc.md")
        crashMidCommit(target, "STAGED")
        pendingFiles(".uri").single().delete()

        assertEquals(0, repo().recoverPendingSaves().getOrThrow())
        assertTrue("the incomplete slot is dropped", pendingFiles(".content").isEmpty())
        verify(exactly = 1) { resolver.openFileDescriptor(uri, "wt") }
    }

    @Test
    fun aSlotWithoutCommitMarkerIsNotListedAsStranded() = runBlocking {
        crashMidCommit(tempFolder.newFile("doc.md"), "STAGED")
        pendingFiles(".committing").single().delete()

        assertTrue(repo().strandedSlots().getOrThrow().isEmpty())
    }

    // --- displayName (the load's document name) ---------------------------------------------------

    @Test
    fun displayNameComesFromTheProvider() = runBlocking {
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Notes.md")) }
        every { resolver.query(uri, any(), null, null, null) } returns cursor

        assertEquals("Notes.md", repo().displayName(uri))
    }

    @Test
    fun displayNameFallsBackToTheLastPathSegmentWhenTheProviderHasNone() = runBlocking {
        every { resolver.query(uri, any(), null, null, null) } returns null

        assertEquals("doc.md", repo().displayName(uri))
    }
}
