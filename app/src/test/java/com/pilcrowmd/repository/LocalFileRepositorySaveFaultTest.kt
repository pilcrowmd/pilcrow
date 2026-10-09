// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.concurrent.thread

/**
 * Hardening gate (c): save-path fault injection at the repository layer (Safeguard 1, no data loss).
 *
 * The property, for every fault: the save returns a failure (never throws), and the target ends up
 * byte-identical to the ORIGINAL or to the complete NEW content, never anything else — immediately
 * when the fault fires before the "wt" open, and after the next launch's recovery otherwise, with
 * the journal left empty. What each fault leaves on disk is [SaveFault.outcome].
 *
 * Already covered elsewhere, so NOT repeated here ([LocalFileRepositoryAtomicSaveTest]): the plain
 * IOException on the target open, the debug-armed fault, a recovery that is itself interrupted, the
 * read that serves the WAL copy of a truncated target, and the debug stage-without-commit hook.
 *
 * Real files and a real journal; only the ContentResolver is a (strict) mock, driven by [SaveFaultRig].
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositorySaveFaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private lateinit var target: File
    private lateinit var rig: SaveFaultRig
    private val uri: Uri = Uri.parse("content://test/document/faults.md")

    @Before
    fun setup() {
        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
        target = tempFolder.newFile("faults.md").apply { writeBytes(ORIGINAL) }
        rig = SaveFaultRig(resolver, walBaseDir, tempFolder.newFolder("provider"))
        every { resolver.openFileDescriptor(uri, "r") } answers { SaveFaultRig.openR(target) }
    }

    /** A brand-new repository over the same disk: the next launch, with nothing kept in memory. */
    private fun relaunch() = LocalFileRepository(resolver, walBaseDir)

    private fun save(): Result<Unit> = runBlocking { LocalFileRepository(resolver, walBaseDir).saveFile(uri, NEW_TEXT) }

    /** Arm [fault], save, and require a failure result that says something. */
    private fun failSave(fault: SaveFault) {
        rig.arm(fault, uri, target, NEW)
        val result = save()
        assertTrue("$fault: the save must report failure", result.isFailure)
        assertFalse("$fault: the failure must carry a message", result.exceptionOrNull()?.message.isNullOrBlank())
    }

    /** The fault fired before the target was truncated: nothing changed, and nothing is left to recover. */
    private fun assertTargetUntouched(fault: SaveFault) {
        assertArrayEquals("$fault: the original must be byte-for-byte intact", ORIGINAL, target.readBytes())
        assertEquals("$fault: no slot may linger to overwrite the file later", emptyList<String>(), rig.journalFiles())
        rig.disarm(uri, target)
        assertEquals(0, runBlocking { relaunch().recoverPendingSaves() }.getOrThrow())
        assertArrayEquals("$fault: the next launch must not touch it either", ORIGINAL, target.readBytes())
    }

    /** The target may be partial: the complete new bytes are durable, served, and recovered on the next launch. */
    private fun assertSlotKeptAndRecovered(fault: SaveFault) {
        assertTrue("$fault: the WAL slot must survive the failure", rig.slotFile(uri, ".content").exists())
        assertArrayEquals(
            "$fault: the WAL must hold the COMPLETE new bytes",
            NEW,
            rig.slotFile(uri, ".content").readBytes(),
        )
        assertEquals(uri.toString(), rig.slotFile(uri, ".uri").readText())
        assertTrue("$fault: the commit marker must be kept", rig.slotFile(uri, ".committing").exists())
        assertRelaunchLandsOn(NEW, fault.name)
    }

    /**
     * The next launch: what the app reads first, then recovery with the fault gone. Both must be
     * [expected] exactly, and the journal must be empty afterwards.
     */
    private fun assertRelaunchLandsOn(expected: ByteArray, why: String) {
        val read = runBlocking { relaunch().readFile(uri) }.getOrThrow().content
        assertEquals("$why: the next launch must read a complete version", String(expected), read)
        rig.disarm(uri, target)
        assertTrue(runBlocking { relaunch().recoverPendingSaves() }.isSuccess)
        assertArrayEquals("$why: the target after recovery", expected, target.readBytes())
        assertEquals("$why: the journal must be empty after recovery", emptyList<String>(), rig.journalFiles())
    }

    // ── 1. Disk full ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `disk full while staging the journal fails before the target is ever opened`() {
        failSave(SaveFault.JOURNAL_UNWRITABLE)
        verify(exactly = 0) { resolver.openFileDescriptor(uri, "wt") }
        assertTargetUntouched(SaveFault.JOURNAL_UNWRITABLE)
    }

    @Test
    fun `disk full at the target open leaves the original intact and no slot`() {
        failSave(SaveFault.OPEN_ENOSPC)
        assertTargetUntouched(SaveFault.OPEN_ENOSPC)
    }

    @Test
    fun `the target write fails before a byte lands - slot kept and new content recovered`() {
        failSave(SaveFault.WRITE_FAILS_AT_BYTE_0)
        assertEquals("fixture: the real write must have landed nothing", 0L, target.length())
        assertSlotKeptAndRecovered(SaveFault.WRITE_FAILS_AT_BYTE_0)
    }

    @Test
    fun `the target write fails after N bytes - slot kept and new content recovered`() {
        failSave(SaveFault.WRITE_FAILS_AFTER_N_BYTES)
        // These bytes came through production's own write(): proof the fault fired INSIDE it.
        assertArrayEquals(NEW.copyOf(SaveFaultRig.PARTIAL_BYTES), target.readBytes())
        assertSlotKeptAndRecovered(SaveFault.WRITE_FAILS_AFTER_N_BYTES)
    }

    @Test
    fun `a SyncFailedException at the descriptor handover keeps the slot and recovers the new content`() {
        failSave(SaveFault.DESCRIPTOR_SYNC_FAILED)
        assertSlotKeptAndRecovered(SaveFault.DESCRIPTOR_SYNC_FAILED)
    }

    /**
     * M-354. Within the session, a write-phase failure leaves the target truncated or partial on disk
     * until the NEXT LAUNCH runs recovery. The app itself never shows it (readFile serves the WAL copy)
     * but any other reader of the file does: a sync client, another editor, a share.
     */
    @Ignore("M-354: a write-phase failure leaves the target partial on disk until the next launch recovers it")
    @Test
    fun `a mid-write failure never leaves a partial target on disk, even before the next launch`() {
        failSave(SaveFault.WRITE_FAILS_AFTER_N_BYTES)
        val onDisk = target.readBytes()
        assertTrue(
            "the target holds ${onDisk.size} bytes, neither the original (${ORIGINAL.size}) nor the new (${NEW.size})",
            onDisk.contentEquals(ORIGINAL) || onDisk.contentEquals(NEW),
        )
    }

    // ── 2. Permission revoked ──────────────────────────────────────────────────────────────────

    @Test
    fun `a grant revoked between load and save leaves the original intact and no slot`() {
        // loaded with the grant
        assertEquals(ORIGINAL_TEXT, runBlocking { relaunch().readFile(uri) }.getOrThrow().content)
        failSave(SaveFault.OPEN_SECURITY)
        verify { resolver.openFileDescriptor(uri, "wt") } // the one mode production uses was refused
        assertTargetUntouched(SaveFault.OPEN_SECURITY)
    }

    @Test
    fun `a refused persistable grant is a failure result, never a throw`() {
        every { resolver.takePersistableUriPermission(uri, any()) } throws SecurityException("No persistable grant")

        val result = runBlocking { relaunch().takePersistableUriPermission(uri) }

        assertTrue(result.exceptionOrNull() is SecurityException)
        verify {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    // ── 3. Killed mid-write, at every distinct step ───────────────────────────────────────────

    @Test
    fun `killed after the content is staged but before the slot is complete - the original survives`() {
        rig.openWriteWorks(uri, target)
        // Block the `.uri` write: staging dies after `.content` and before `.uri` and `.committing`.
        val blocker = rig.slotFile(uri, ".uri").apply { mkdirs() }
        File(blocker, "occupied").writeText("x")

        assertTrue(save().isFailure)
        assertArrayEquals(NEW, rig.slotFile(uri, ".content").readBytes())
        assertFalse("fixture: the commit marker must not exist yet", rig.slotFile(uri, ".committing").exists())
        verify(exactly = 0) { resolver.openFileDescriptor(uri, "wt") }
        blocker.deleteRecursively() // leave exactly what a kill would: `.content` (+ its temp), no marker

        assertRelaunchLandsOn(ORIGINAL, "partial stage")
    }

    @Test
    fun `killed after staging, before the target is opened - the next launch lands the new content`() {
        every { resolver.openFileDescriptor(uri, "wt") } throws ProcessDeath("after stage")

        assertThrows(ProcessDeath::class.java) { save() }

        assertArrayEquals(ORIGINAL, target.readBytes())
        assertRelaunchLandsOn(NEW, "after stage")
    }

    @Test
    fun `killed after the target is truncated, before a byte is written - recovered`() {
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            SaveFaultRig.truncate(target)
            throw ProcessDeath("after truncate")
        }

        assertThrows(ProcessDeath::class.java) { save() }

        assertEquals(0, target.length().toInt())
        assertRelaunchLandsOn(NEW, "after truncate")
    }

    /**
     * The kill lands while production is blocked INSIDE `fos.write()`: the provider has taken N bytes
     * and stopped reading. The disk is photographed at that instant — exactly what a kill leaves —
     * then the write is let go, and the photograph is put back before the "next launch" so nothing
     * the dying process might have done afterwards can influence the outcome.
     */
    @Test
    fun `killed partway through the target write - recovered`() {
        val pipes = rig.failingWrite(uri, target, NEW, acceptBytes = SaveFaultRig.PARTIAL_BYTES, hold = true)
        val saving = thread { save() }
        awaitTrue("production never reached the write") { pipes.isNotEmpty() && pipes.single().awaitAccepted() }

        val journalAtKill = rig.journalFiles().associateWith { rig.journalFile(it).readBytes() }
        val targetAtKill = target.readBytes()
        pipes.single().release()
        saving.join()
        rig.journalFiles().forEach { rig.journalFile(it).delete() }
        journalAtKill.forEach { (name, bytes) -> rig.journalFile(name).writeBytes(bytes) }
        target.writeBytes(targetAtKill)

        assertArrayEquals("fixture: killed mid-write", NEW.copyOf(SaveFaultRig.PARTIAL_BYTES), targetAtKill)
        assertTrue("fixture: the slot was complete at the kill", rig.slotFile(uri, ".committing").exists())
        assertRelaunchLandsOn(NEW, "mid-write")
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(POLL_MS)
        assertTrue(message, condition())
    }

    /**
     * The target write finished but the slot was never discarded. There is no seam between the
     * write and `journal.discard`, so the state is built from production's own staging (the debug
     * stage-without-commit hook) plus the completed write it would have been followed by.
     */
    @Test
    fun `killed after the write completes, before the journal is cleared - recovery rewrites the same bytes`() {
        stageThenWriteTarget()
        assertRelaunchLandsOn(NEW, "before journal clear")
    }

    /** `journal.discard` deletes `.content` first; a kill right after leaves `.uri` + `.committing` orphans. */
    @Test
    fun `killed partway through clearing the journal - the new content stays and the orphans are swept`() {
        stageThenWriteTarget()
        assertTrue(rig.slotFile(uri, ".content").delete())
        assertRelaunchLandsOn(NEW, "mid journal clear")
    }

    private fun stageThenWriteTarget() {
        rig.openWriteWorks(uri, target)
        assertTrue(runBlocking { relaunch().debugStageWithoutCommit(uri, NEW_TEXT) }.isSuccess)
        target.writeBytes(NEW)
    }

    // ── 4. Failing file provider ──────────────────────────────────────────────────────────────

    @Test
    fun `a provider returning a null descriptor leaves the original intact and no slot`() {
        failSave(SaveFault.OPEN_NULL)
        assertTargetUntouched(SaveFault.OPEN_NULL)
    }

    @Test
    fun `a provider throwing FileNotFoundException leaves the original intact and no slot`() {
        failSave(SaveFault.OPEN_FILE_NOT_FOUND)
        assertTargetUntouched(SaveFault.OPEN_FILE_NOT_FOUND)
    }

    @Test
    fun `a provider throwing IllegalStateException on open leaves the original intact and no slot`() {
        failSave(SaveFault.OPEN_ILLEGAL_STATE)
        assertTargetUntouched(SaveFault.OPEN_ILLEGAL_STATE)
    }

    @Test
    fun `a provider throwing a RuntimeException on open leaves the original intact and no slot`() {
        failSave(SaveFault.OPEN_RUNTIME)
        assertTargetUntouched(SaveFault.OPEN_RUNTIME)
    }

    @Test
    fun `a provider throwing a non-IO exception at the descriptor handover keeps the slot and recovers`() {
        failSave(SaveFault.DESCRIPTOR_ILLEGAL_STATE)
        assertSlotKeptAndRecovered(SaveFault.DESCRIPTOR_ILLEGAL_STATE)
    }

    @Test
    fun `a descriptor whose close throws after a full write reports failure over complete new bytes`() {
        failSave(SaveFault.CLOSE_THROWS_AFTER_FULL_WRITE)
        assertArrayEquals(NEW, target.readBytes())
        assertRelaunchLandsOn(NEW, "close throws after a full write")
    }

    /**
     * M-355. `saveFile` discards the slot BEFORE `pfd.close()`. A provider that buffers the bytes and
     * reports its commit failure at close leaves the target as the "wt" open left it (empty) with no
     * slot to recover from. Latent on stock Android (AOSP's `ParcelFileDescriptor.close()` swallows
     * close errors), live for any provider or wrapper whose close() reports failure.
     */
    @Ignore("M-355: the WAL slot is discarded before pfd.close(), so a failure reported at close is unrecoverable")
    @Test
    fun `a provider that fails to commit at close keeps the slot for recovery`() {
        failSave(SaveFault.CLOSE_THROWS_PROVIDER_UNCOMMITTED)
        assertSlotKeptAndRecovered(SaveFault.CLOSE_THROWS_PROVIDER_UNCOMMITTED)
    }

    private companion object {
        const val ORIGINAL_TEXT = "# Original\n\nThe file as it was before the save.\n"
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L

        // Larger than any pipe buffer, so a pipe-backed write really has to fail (FailingPipe).
        val NEW_TEXT = "# Edited\n\n" + (1..10_000).joinToString("") { "Line $it of the new content.\n" }
        val ORIGINAL = ORIGINAL_TEXT.toByteArray(Charsets.UTF_8)
        val NEW = NEW_TEXT.toByteArray(Charsets.UTF_8)
    }
}
