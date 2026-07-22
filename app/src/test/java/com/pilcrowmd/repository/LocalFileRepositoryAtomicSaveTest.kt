// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safety tests for [LocalFileRepository] save integrity (Safeguard 1: no data loss).
 *
 * The save is a write-ahead log: the full new content is written to a durable file under the
 * app's (non-evictable) storage and fsynced BEFORE the SAF target is opened with "wt" (which
 * truncates it). A `.committing` marker, fsynced before the target is touched, records that a
 * commit began. [LocalFileRepository.recoverPendingSaves] re-applies any pending entry on the
 * next launch, so the user's content survives process death in the truncation window — the
 * exact failure the old in-memory-backup design could not survive.
 *
 * Tests use a real on-disk WAL directory (so durability across "process death" is real) and mock
 * only the ContentResolver to drive the SAF "wt" open into the relevant failure states.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryAtomicSaveTest {

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

    private fun pendingDir() = File(walBaseDir, "pending_saves")
    private fun pendingFiles(suffix: String): List<File> =
        pendingDir().listFiles()?.filter { it.name.endsWith(suffix) } ?: emptyList()

    /** A real writable, truncating pfd over [file] (models a normal SAF "wt" open). */
    private fun writablePfd(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(
        file,
        ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
    )

    /** A real read-only pfd over [file] (models a SAF "r" open). */
    private fun readablePfd(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    /**
     * Models the danger window: the SAF "wt" open SUCCEEDS and truncates the target to 0 bytes,
     * then the write is interrupted (process death / I/O error before the bytes land). Returns a
     * read-only descriptor over the now-empty file so the repo's write() throws after truncation.
     */
    private fun truncatedThenInterruptedPfd(file: File): ParcelFileDescriptor {
        FileOutputStream(file).close() // truncate to 0, exactly as a real "wt" open does
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun successfulSaveWritesContentAndLeavesNoPendingEntries() = runBlocking {
        val target = tempFolder.newFile("success.md").apply { writeText("ORIGINAL") }
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(target) }

        val result = LocalFileRepository(resolver, walBaseDir).saveFile(uri, "NEW_CONTENT")

        assertTrue("Save should succeed with a writable target", result.isSuccess)
        assertEquals("New content must be persisted", "NEW_CONTENT", target.readText())
        assertTrue("WAL must be cleaned up after success", pendingFiles(".content").isEmpty())
        assertTrue(pendingFiles(".committing").isEmpty())
        assertTrue(pendingFiles(".uri").isEmpty())
    }

    /**
     * THE core safeguard: the process dies in the truncation window (target zeroed, sync not done).
     * No in-process recovery runs — a FRESH repository (next launch) must recover the full content
     * from the durable WAL. Under the old in-memory-backup design this content was unrecoverable.
     */
    @Test
    fun processDeathMidWriteIsRecoverableFromDurableWalOnNextLaunch() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL CONTENT") }

        var wtOpens = 0
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            wtOpens++
            if (wtOpens == 1) truncatedThenInterruptedPfd(target) else writablePfd(target)
        }

        val saveResult = LocalFileRepository(resolver, walBaseDir).saveFile(uri, "BRAND NEW CONTENT")
        assertTrue("Save must report failure when the write dies", saveResult.isFailure)

        // The danger is real: the SAF target is truncated on disk.
        assertEquals("Target is truncated in the death window", "", target.readText())
        // ...but the full new content is durable on disk, and a commit marker is present.
        assertEquals(
            "Durable WAL must hold the complete new content",
            "BRAND NEW CONTENT",
            pendingFiles(".content").single().readText(),
        )
        assertTrue("Commit marker must be present", pendingFiles(".committing").isNotEmpty())

        // Next launch: a brand-new repository recovers purely from disk state.
        val recovery = LocalFileRepository(resolver, walBaseDir).recoverPendingSaves()
        assertTrue(recovery.isSuccess)
        assertEquals("Content must be recovered to the target", "BRAND NEW CONTENT", target.readText())
        assertTrue("WAL must be cleaned after recovery", pendingFiles(".content").isEmpty())
        assertTrue(pendingFiles(".committing").isEmpty())
    }

    /**
     * If the target "wt" open fails outright (e.g. permission revoked), the target was never
     * truncated and stays intact. The save must leave NO pending entry, so a later launch never
     * overwrites the (intact, possibly externally-edited) file with stale staged content.
     */
    @Test
    fun cleanOpenFailureLeavesTargetIntactAndNoPendingEntry() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }
        every { resolver.openFileDescriptor(uri, "wt") } throws IOException("permission denied on open")

        val result = LocalFileRepository(resolver, walBaseDir).saveFile(uri, "NEW_CONTENT")

        assertTrue("Save should fail", result.isFailure)
        assertEquals("Untouched target must keep original content", "ORIGINAL", target.readText())
        assertTrue("No stale WAL entry may linger after a clean open failure", pendingFiles(".content").isEmpty())
        assertTrue(pendingFiles(".committing").isEmpty())
    }

    /**
     * The DEBUG-only one-shot fault flag (armed on a device by `DebugFailSaveReceiver`) must drive
     * the save into the *real* open-failure path: it aborts BEFORE the "wt" open, so the target is
     * byte-for-byte intact and no stale WAL entry lingers — and it is one-shot, so the next save
     * succeeds. This is the deterministic trigger for verifying the "Save failed" UX on a device.
     */
    @Test
    fun debugArmedFaultFailsNextSaveOnlyAndLeavesTargetIntact() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }
        // The "wt" open would SUCCEED — proving the armed fault aborts before it is ever called.
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(target) }
        val repo = LocalFileRepository(resolver, walBaseDir)

        repo.debugFailNextSaveArmed = true
        val failed = repo.saveFile(uri, "NEW_CONTENT")

        assertTrue("Armed fault must fail the save", failed.isFailure)
        assertEquals("Target must stay byte-for-byte intact (never opened wt)", "ORIGINAL", target.readText())
        assertTrue("No stale WAL entry after the aborted save", pendingFiles(".content").isEmpty())
        assertTrue(pendingFiles(".committing").isEmpty())
        verify(exactly = 0) { resolver.openFileDescriptor(uri, "wt") } // aborted before the open

        // One-shot: the very next save proceeds normally.
        val ok = repo.saveFile(uri, "NEW_CONTENT")
        assertTrue("Fault is one-shot — the next save succeeds", ok.isSuccess)
        assertEquals("NEW_CONTENT", target.readText())
    }

    /**
     * Recovery must be safe to interrupt and idempotent: if the target write fails during
     * recovery, the WAL entry is kept (not lost) and a later recovery completes it.
     */
    @Test
    fun recoveryInterruptionKeepsEntryThenSucceedsOnRetry() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }

        var wtOpens = 0
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            wtOpens++
            when (wtOpens) {
                1 -> truncatedThenInterruptedPfd(target) // save: truncate + interrupted → recoverable entry
                2 -> truncatedThenInterruptedPfd(target) // first recovery also interrupted
                else -> writablePfd(target) // second recovery succeeds
            }
        }

        assertTrue(LocalFileRepository(resolver, walBaseDir).saveFile(uri, "RECOVER_ME").isFailure)

        val firstRecovery = LocalFileRepository(resolver, walBaseDir).recoverPendingSaves()
        assertTrue("Interrupted recovery must not throw", firstRecovery.isSuccess)
        assertEquals("Nothing recovered yet", 0, firstRecovery.getOrNull())
        assertFalse("WAL entry must survive an interrupted recovery", pendingFiles(".content").isEmpty())

        val secondRecovery = LocalFileRepository(resolver, walBaseDir).recoverPendingSaves()
        assertEquals("Second recovery completes the save", 1, secondRecovery.getOrNull())
        assertEquals("RECOVER_ME", target.readText())
        assertTrue(pendingFiles(".content").isEmpty())
    }

    /**
     * A1 (recovery↔read startup race). After a crash mid-commit the SAF target on disk is truncated
     * while the full content is durable in the WAL with a commit marker. On the next launch a read
     * can win [journalMutex] before recovery streams the bytes back — so [LocalFileRepository.readFile]
     * must serve the durable WAL content, never the truncated target. Without the fix readFile opens
     * the target "r" unsynchronized and returns "" (the editor would load empty content).
     */
    @Test
    fun readServesDurableWalContentWhenTargetWasTruncatedMidCommit() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(target) }
        every { resolver.openFileDescriptor(uri, "r") } answers { readablePfd(target) }

        // Crash mid-commit: target truncated, full content durable in the WAL with a marker.
        LocalFileRepository(resolver, walBaseDir).debugStageWithoutCommit(uri, "FULL CRASHED CONTENT")
        assertEquals("Precondition: target is truncated on disk", "", target.readText())

        // Next launch reads the file BEFORE recovery runs.
        val read = LocalFileRepository(resolver, walBaseDir).readFile(uri)

        assertTrue(read.isSuccess)
        assertEquals(
            "readFile must serve the durable WAL content, never the truncated target",
            "FULL CRASHED CONTENT",
            read.getOrNull(),
        )
    }

    /**
     * A1 end-to-end no-loss: the worst case is the user editing and saving IMMEDIATELY on launch,
     * before recovery streams. The read must surface the full (durable) content, and an immediate
     * edit+save must persist the edited full content — the original is never lost to a truncated read.
     */
    @Test
    fun editAndSaveImmediatelyAfterCrashLaunchNeverLosesContent() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(target) }
        every { resolver.openFileDescriptor(uri, "r") } answers { readablePfd(target) }

        // Crash mid-commit leaves the recoverable truncated state.
        LocalFileRepository(resolver, walBaseDir).debugStageWithoutCommit(uri, "RECOVERABLE FULL")
        assertEquals("", target.readText())

        // Next launch: one shared repository instance (real shared journalMutex).
        val repo = LocalFileRepository(resolver, walBaseDir)

        // 1. Editor loads the file → full content, not the truncated "".
        val loaded = repo.readFile(uri).getOrThrow()
        assertEquals("RECOVERABLE FULL", loaded)

        // 2. User edits and saves immediately, before recovery streams.
        assertTrue(repo.saveFile(uri, "$loaded + edit").isSuccess)

        // 3. Late recovery: the successful save already discarded the slot → nothing stale re-applied.
        assertEquals("Nothing left to recover after the user's save", 0, repo.recoverPendingSaves().getOrThrow())

        // 4. No loss: the target holds the edited full content, never the truncated "".
        assertEquals("RECOVERABLE FULL + edit", target.readText())
        assertTrue("WAL must be clean", pendingFiles(".content").isEmpty())
    }

    /**
     * The debug-only crash-injection hook (used to demonstrate recovery end-to-end on device) must
     * leave exactly the mid-write-death state: target truncated, full content durable in the WAL
     * with a commit marker — and a fresh launch must recover it. (BuildConfig.DEBUG is true under
     * the debug unit-test variant.)
     */
    @Test
    fun debugStageWithoutCommitLeavesRecoverableTruncatedState() = runBlocking {
        val target = tempFolder.newFile("doc.md").apply { writeText("ORIGINAL") }
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(target) }

        val staged = LocalFileRepository(resolver, walBaseDir).debugStageWithoutCommit(uri, "STAGED NEW")
        assertTrue("Debug hook should stage successfully in a debug build", staged.isSuccess)

        // Mid-write-death state: target truncated, but full content durable in the WAL with marker.
        assertEquals("Target must be left truncated by the hook", "", target.readText())
        assertEquals("STAGED NEW", pendingFiles(".content").single().readText())
        assertTrue(pendingFiles(".committing").isNotEmpty())

        // Next launch recovers the staged content to the (truncated) target.
        val recovery = LocalFileRepository(resolver, walBaseDir).recoverPendingSaves()
        assertEquals(1, recovery.getOrNull())
        assertEquals("STAGED NEW", target.readText())
        assertTrue(pendingFiles(".content").isEmpty())
    }
}
