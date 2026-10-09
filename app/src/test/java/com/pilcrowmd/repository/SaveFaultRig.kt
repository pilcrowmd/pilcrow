// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.UriPermission
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Thrown from a fault seam to model the process being killed at that instant. It is an [Error], so no
 * `catch (e: Exception)` on the save path can intercept it: nothing after the throw point runs, and the
 * disk is left exactly as a kill would leave it. The next "launch" is a fresh [LocalFileRepository].
 */
class ProcessDeath(step: String) : Error("process killed $step")

/**
 * A SAF descriptor whose data path ([onFd]) or [close] is under test control. The production save
 * reads [getFileDescriptor] once, AFTER the "wt" open has truncated the target, and then writes to
 * whatever descriptor [onFd] hands back; [onClose] runs after the production code has finished.
 */
class FaultPfd(
    private val inner: ParcelFileDescriptor,
    private val onFd: (ParcelFileDescriptor) -> FileDescriptor = { it.fileDescriptor },
    private val onClose: () -> Unit = {},
) : ParcelFileDescriptor(inner) {
    override fun getFileDescriptor(): FileDescriptor = onFd(inner)

    override fun close() {
        inner.close()
        super.close()
        onClose()
    }
}

/**
 * A REAL operating-system pipe (a `mkfifo` FIFO) standing in for the provider's descriptor, so the
 * production `FileOutputStream.write()` runs for real and the fault happens INSIDE it.
 *
 * Why not `ParcelFileDescriptor.createPipe()`: under Robolectric it is shadowed by a temporary FILE
 * (`ShadowParcelFileDescriptor.PIPE_TMP_DIR` / `PIPE_FILE_NAME`), which never refuses a write.
 *
 * The test-side reader copies the first [acceptBytes] bytes into [target] (what "reached the disk"),
 * then closes its end — unless [hold] is set, in which case it keeps the pipe open and stops reading,
 * so the production write blocks mid-call until [release]. Once the reader is gone the kernel fails
 * the next write with EPIPE, which the JVM surfaces as `IOException: Broken pipe`: a real failing
 * write. (ENOSPC itself cannot be produced on a developer disk; the call that fails, where it fails,
 * and what it leaves behind are the same.) The written content must overflow the pipe buffer
 * ([MIN_CONTENT_BYTES]), or the whole write would fit in the kernel buffer and succeed.
 */
class FailingPipe(dir: File, private val target: File, private val acceptBytes: Int, private val hold: Boolean) {
    private val fifo = File(dir, "pipe-${SEQ.incrementAndGet()}")
    private val accepted = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private var writer: FileOutputStream? = null

    init {
        check(ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0) { "mkfifo failed for $fifo" }
        thread(isDaemon = true, name = "provider-reader") { drain() }
    }

    /** The writer end, opened as production asks for the descriptor. Blocks until the reader is attached. */
    fun openWriter(): FileDescriptor = FileOutputStream(fifo).also { writer = it }.fd

    /** True once the reader has taken [acceptBytes] into the target (production is now inside write()). */
    fun awaitAccepted(): Boolean = accepted.await(AWAIT_SECONDS, TimeUnit.SECONDS)

    /** Let a [hold]ing reader go: its end closes and the blocked production write fails. */
    fun release() = released.countDown()

    fun closeWriterQuietly() = runCatching { writer?.close() }

    private fun drain() {
        FileInputStream(fifo).use { input ->
            val buffer = ByteArray(acceptBytes)
            var read = 0
            // FileInputStream.readNBytes seeks, which a FIFO refuses ("Illegal seek"), so read by hand.
            while (read < acceptBytes) {
                val n = input.read(buffer, read, acceptBytes - read)
                if (n < 0) break
                read += n
            }
            target.appendBytes(buffer.copyOf(read))
            accepted.countDown()
            if (hold) released.await()
        }
    }

    companion object {
        private val SEQ = AtomicInteger()
        private const val AWAIT_SECONDS = 10L

        /** Comfortably above any default pipe buffer (64 KiB on Linux and macOS). */
        const val MIN_CONTENT_BYTES = 256 * 1024
    }
}

/** What a fault leaves on disk — and therefore what a correct save path has to guarantee. */
enum class FaultOutcome {
    /** The fault fires before the "wt" open truncates: the target must be byte-for-byte intact. */
    TARGET_UNTOUCHED,

    /** The target may be truncated/partial: the complete new bytes must stay in the WAL for recovery. */
    SLOT_KEPT,

    /** Every new byte reached the target; only the report failed. */
    NEW_BYTES_LANDED,
}

/**
 * Every fault the save path can meet, grouped by the item of the hardening gate it covers.
 * [outcome] is what the fault leaves on disk. [SaveFaultRig.arm] wires each one into the resolver.
 */
enum class SaveFault(val outcome: FaultOutcome) {
    // 1. Disk full
    JOURNAL_UNWRITABLE(FaultOutcome.TARGET_UNTOUCHED),
    OPEN_ENOSPC(FaultOutcome.TARGET_UNTOUCHED),

    /** The production write() itself fails before a byte lands (real pipe, reader gone: EPIPE). */
    WRITE_FAILS_AT_BYTE_0(FaultOutcome.SLOT_KEPT),

    /** The production write() lands [SaveFaultRig.PARTIAL_BYTES] bytes, then fails inside the same call. */
    WRITE_FAILS_AFTER_N_BYTES(FaultOutcome.SLOT_KEPT),

    // 2. Permission revoked between load and save
    OPEN_SECURITY(FaultOutcome.TARGET_UNTOUCHED),

    // 4. Failing file provider
    OPEN_NULL(FaultOutcome.TARGET_UNTOUCHED),
    OPEN_FILE_NOT_FOUND(FaultOutcome.TARGET_UNTOUCHED),
    OPEN_ILLEGAL_STATE(FaultOutcome.TARGET_UNTOUCHED),
    OPEN_RUNTIME(FaultOutcome.TARGET_UNTOUCHED),

    /**
     * Handover faults: the provider's descriptor throws when production asks for it, AFTER the "wt"
     * open truncated the target and BEFORE write() runs. These do not exercise write()/flushAndSync()
     * — the pipe faults above do. A SyncFailedException from the real `fd.sync()` cannot be produced
     * here: `FileDescriptor` is final, `sync()` is native, and fsync on a FIFO or /dev/null SUCCEEDS on
     * macOS (probed), so there is no portable descriptor whose sync fails; production swallows that
     * exception by design anyway (flushAndSync). This row checks the SyncFailedException TYPE reaching
     * the save path's IOException handling, at the only point it can be raised.
     */
    DESCRIPTOR_SYNC_FAILED(FaultOutcome.SLOT_KEPT),
    DESCRIPTOR_ILLEGAL_STATE(FaultOutcome.SLOT_KEPT),

    CLOSE_THROWS_AFTER_FULL_WRITE(FaultOutcome.NEW_BYTES_LANDED),

    /**
     * The provider accepted every byte into its own buffer and failed to commit at close, leaving the
     * target as the "wt" open left it (empty). Only the @Ignored M-355 finding uses it: the outcome
     * the save path SHOULD guarantee is [FaultOutcome.SLOT_KEPT], and it does not.
     */
    CLOSE_THROWS_PROVIDER_UNCOMMITTED(FaultOutcome.SLOT_KEPT),
}

/**
 * Wires [SaveFault]s into a strict mockk [ContentResolver] over real files, and inspects the real
 * on-disk journal under [walBaseDir]. [providerDir] holds a provider's private buffer files.
 */
class SaveFaultRig(
    private val resolver: ContentResolver,
    private val walBaseDir: File,
    private val providerDir: File,
) {
    private val journalDir get() = File(walBaseDir, "pending_saves")

    /** Arm [fault] for saves to [uri] (backed by [target]); [newBytes] is what the save will write. */
    fun arm(fault: SaveFault, uri: Uri, target: File, newBytes: ByteArray) {
        openWriteWorks(uri, target)
        openFailure(fault)?.let { error ->
            every { resolver.openFileDescriptor(uri, "wt") } throws error
            return
        }
        handoverFailure(fault)?.let { error ->
            every { resolver.openFileDescriptor(uri, "wt") } answers {
                FaultPfd(openWt(target), onFd = { fail(error) })
            }
            return
        }
        when (fault) {
            SaveFault.JOURNAL_UNWRITABLE -> {
                // A plain file where the journal directory must go: every staging write fails, exactly
                // where an ENOSPC on the app's own storage would. (ENOSPC itself cannot be produced on
                // a developer disk; the failing call and its position in the sequence are the same.)
                journalDir.deleteRecursively()
                journalDir.writeText("not a directory")
            }
            SaveFault.WRITE_FAILS_AT_BYTE_0 -> failingWrite(uri, target, newBytes, acceptBytes = 0)
            SaveFault.WRITE_FAILS_AFTER_N_BYTES -> failingWrite(uri, target, newBytes, acceptBytes = PARTIAL_BYTES)
            SaveFault.OPEN_SECURITY -> {
                // The grant is gone: every write mode is refused and the persisted list no longer has it.
                val denial = SecurityException("Permission Denial: writing $uri requires a grant")
                WRITE_MODES.forEach { mode -> every { resolver.openFileDescriptor(uri, mode) } throws denial }
                every { resolver.persistedUriPermissions } returns emptyList()
            }
            SaveFault.OPEN_NULL -> every { resolver.openFileDescriptor(uri, "wt") } returns null
            SaveFault.CLOSE_THROWS_AFTER_FULL_WRITE -> every { resolver.openFileDescriptor(uri, "wt") } answers {
                val closeFailed = IOException("close failed: EIO")
                FaultPfd(openWt(target), onClose = { fail(closeFailed) })
            }
            SaveFault.CLOSE_THROWS_PROVIDER_UNCOMMITTED -> every { resolver.openFileDescriptor(uri, "wt") } answers {
                truncate(target)
                val buffer = File(providerDir, "upload-buffer")
                val uncommitted = IOException("provider failed to commit the upload")
                FaultPfd(openWt(buffer), onClose = { fail(uncommitted) })
            }
            else -> error("$fault is armed above")
        }
    }

    /**
     * "wt" truncates [target] and hands production the writer end of a [FailingPipe]; production's own
     * write() then fails after [acceptBytes] bytes. With [hold], the write instead blocks mid-call
     * until the returned pipe is released — the instant a kill test photographs.
     */
    fun failingWrite(
        uri: Uri,
        target: File,
        newBytes: ByteArray,
        acceptBytes: Int,
        hold: Boolean = false,
    ): List<FailingPipe> {
        require(newBytes.size >= FailingPipe.MIN_CONTENT_BYTES) {
            "fixture: ${newBytes.size} bytes would fit the pipe buffer and the write would never fail"
        }
        val pipes = CopyOnWriteArrayList<FailingPipe>() // appended on the IO thread, read by the test
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            val pipe = FailingPipe(providerDir, target, acceptBytes, hold).also { pipes += it }
            FaultPfd(openWt(target), onFd = { pipe.openWriter() }, onClose = { pipe.closeWriterQuietly() })
        }
        return pipes
    }

    private fun openFailure(fault: SaveFault): Throwable? = when (fault) {
        SaveFault.OPEN_ENOSPC -> IOException("ENOSPC (No space left on device)")
        SaveFault.OPEN_FILE_NOT_FOUND -> FileNotFoundException("provider has no such document")
        SaveFault.OPEN_ILLEGAL_STATE -> IllegalStateException("provider not attached")
        SaveFault.OPEN_RUNTIME -> ProviderCrash()
        else -> null
    }

    private fun handoverFailure(fault: SaveFault): Throwable? = when (fault) {
        SaveFault.DESCRIPTOR_SYNC_FAILED -> SyncFailedException("sync failed: EIO")
        SaveFault.DESCRIPTOR_ILLEGAL_STATE -> IllegalStateException("provider revoked the descriptor")
        else -> null
    }

    private fun fail(error: Throwable): Nothing = throw error

    /** The fault has passed (space freed, grant restored, provider back): "wt" works again. */
    fun disarm(uri: Uri, target: File) {
        if (journalDir.isFile) journalDir.delete()
        openWriteWorks(uri, target)
    }

    /** A persisted read+write grant for each of [uris] — a normally opened, writable document. */
    fun grantReadWrite(vararg uris: Uri) {
        val grants = uris.map { granted ->
            mockk<UriPermission> {
                every { uri } returns granted
                every { isReadPermission } returns true
                every { isWritePermission } returns true
            }
        }
        every { resolver.persistedUriPermissions } returns grants
    }

    fun openWriteWorks(uri: Uri, target: File) {
        every { resolver.openFileDescriptor(uri, "wt") } answers { openWt(target) }
    }

    /** Every file currently in the journal directory (empty when there is no journal at all). */
    fun journalFiles(): List<String> = if (journalDir.isDirectory) {
        journalDir.listFiles()!!.map { it.name }.sorted()
    } else {
        emptyList()
    }

    /** A file in the journal directory, by name. */
    fun journalFile(name: String): File = File(journalDir, name)

    /** The journal file `<sha256(uri)><suffix>` — the slot layout documented in PendingSaveJournal. */
    fun slotFile(uri: Uri, suffix: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(journalDir, key + suffix)
    }

    /** A provider crash surfacing as a bare RuntimeException subclass, not an I/O error. */
    private class ProviderCrash : RuntimeException("provider process died")

    companion object {
        /** How much of the new content the production write lands before it fails. */
        const val PARTIAL_BYTES = 100

        /** Every SAF write mode; production asks for "wt" only, the rest guard a future mode change. */
        val WRITE_MODES = listOf("w", "wt", "rwt")

        fun truncate(file: File) = file.writeBytes(ByteArray(0))

        /** A real "wt" open: the file is truncated at open time, exactly the danger window. */
        fun openWt(file: File): ParcelFileDescriptor {
            truncate(file)
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        fun openR(file: File): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
