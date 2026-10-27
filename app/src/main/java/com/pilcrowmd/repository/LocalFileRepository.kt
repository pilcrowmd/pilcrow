// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.pilcrowmd.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SyncFailedException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val PREFIX_CHUNK = 64 * 1024

/**
 * SAF-based file repository. Handles read/write via ContentResolver.
 *
 * Crash-safe writes (Safeguard 1, no data loss). A SAF "wt" open truncates the target at open time,
 * so the only safe design is a write-ahead log: the full new content is staged durably via
 * [PendingSaveJournal] and fsynced BEFORE the target is touched. If the process dies in the
 * truncation window, [recoverPendingSaves] re-applies the staged content on the next launch. The
 * previous design kept only an in-memory backup, which evaporated on process death — losing the file.
 *
 * Round-trip fidelity (Safeguard 2): bytes are written exactly as given, no normalize/reflow.
 *
 * @param walBaseDir a durable, non-evictable directory (e.g. `Context.noBackupFilesDir`). Cache dirs
 *   MUST NOT be used: Android can wipe them under storage pressure, defeating recovery.
 */
class LocalFileRepository(private val contentResolver: ContentResolver, walBaseDir: File) : FileRepository {

    private val journal = PendingSaveJournal(walBaseDir)

    // Serializes journal-mutating operations so launch-time recovery never races an active save
    // (which would otherwise drop a half-staged slot or overwrite fresh edits with stale content).
    private val journalMutex = Mutex()

    // DEBUG-ONLY one-shot fault flag, armed by the debug-source-set receiver to verify the failed-save
    // UX on a device. `internal` so only same-module (debug) code can arm it; never armed in release
    // (the receiver is absent), and [saveFile]'s check is BuildConfig.DEBUG-gated, so it is inert there.
    @Volatile
    internal var debugFailNextSaveArmed = false

    /**
     * Read a file's content. Acquires [journalMutex] (the lock [saveFile] and [recoverPendingSaves]
     * use) so a read can never observe a SAF target WHILE recovery is streaming into it — the
     * recovery↔read startup race that could otherwise load empty/partial content and lose data.
     *
     * Under the lock, if a save to this target was interrupted mid-commit (a recoverable WAL slot
     * exists), the on-disk target may be truncated; the durable WAL copy is the real content, so it
     * is served directly. Recovery will later re-commit the identical bytes to the target, so the two
     * paths converge with no loss regardless of who wins the lock. Read-side only — no write path,
     * journal write-ordering, or save logic is touched.
     */
    override suspend fun readFile(uri: Uri): Result<FileText> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                journal.recoverableContentFor(uri)?.let { staged ->
                    // A staged copy another app has since written over is not the file's content (G5).
                    if (verdictFor(uri, journal.keyOf(uri), staged) != RecoveryVerdict.CONFLICT) {
                        return@withLock FileInputStream(staged).use {
                            Result.success(decodeUtf8(readCapped(it, staged.length())))
                        }
                    }
                }

                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withLock Result.failure(Exception("Failed to open file descriptor for reading"))
                try {
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        Result.success(decodeUtf8(readCapped(fis, pfd.statSize)))
                    }
                } finally {
                    pfd.close()
                }
            } catch (e: Exception) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                // A read that is within the cap can still lose the race for a heap another thread has
                // filled. Everything it allocated is unreachable once this frame unwinds, so this is a
                // failed open, not a dead process (and not a crash on every cold start).
                Result.failure(FileTooLargeException("Out of memory reading $uri", e))
            }
        }
    }

    /**
     * Read [stream] to its end, but never more than [MAX_FILE_BYTES]. [sizeHint] is the size the
     * provider reports (`statSize`: -1, or 0 on some providers, when it cannot say); a stated size
     * over the cap fails before a byte is read, and a stream that states none is simply stopped one
     * byte past the cap. A pipe or an endless provider stream therefore ends in
     * [FileTooLargeException] instead of filling the heap.
     */
    private fun readCapped(stream: InputStream, sizeHint: Long): ByteArray {
        if (sizeHint > MAX_FILE_BYTES) {
            throw FileTooLargeException("File is $sizeHint bytes; the limit is $MAX_FILE_BYTES")
        }
        val out = ByteArrayOutputStream(if (sizeHint > 0) sizeHint.toInt() else PREFIX_CHUNK)
        val buffer = ByteArray(PREFIX_CHUNK)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > MAX_FILE_BYTES) throw FileTooLargeException("File is over $MAX_FILE_BYTES bytes")
            out.write(buffer, 0, n)
        }
    }

    /**
     * Crash-safe save (Safeguard 1). Order is the whole point:
     *
     * 1. Stage the full new content + target URI durably and mark commit intent — BEFORE the target
     *    is touched ([PendingSaveJournal.stage]).
     * 2. Open the target "wt" (this truncates it) and write + fsync the bytes.
     * 3. On success, discard the staged slot.
     *
     * If the target open itself fails, the target was never truncated, so the slot is discarded (no
     * stale recovery later). If the failure happens after the target is opened (it may now be
     * truncated/partial) or the process dies, the durable slot remains and [recoverPendingSaves]
     * restores it on the next launch.
     */
    override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                val bytes = content.toByteArray(Charsets.UTF_8)
                parkSlotThatCouldNotBeApplied(uri)
                // What the target is now, recorded before it can be truncated (G5). Null if unreadable.
                val key = journal.stage(uri, bytes, baseline = fingerprintOfTarget(uri))

                val pfd = try {
                    // DEBUG-ONLY one-shot fault: throw at the exact point a real target-open I/O
                    // failure throws — BEFORE the "wt" open truncates the file — so the genuine
                    // open-failure abort below runs and the target stays byte-for-byte intact. Inert
                    // in release (BuildConfig.DEBUG-gated; the flag is never armed there).
                    if (BuildConfig.DEBUG && debugFailNextSaveArmed) {
                        debugFailNextSaveArmed = false
                        throw IOException("Injected save failure (debug fault hook)")
                    }
                    contentResolver.openFileDescriptor(uri, "wt")
                } catch (e: Exception) {
                    journal.abort(key) // target untouched → no recovery needed; an older pending copy stays (G6)
                    return@withContext Result.failure(
                        IOException("Failed to open target for writing: ${e.message}", e),
                    )
                }
                if (pfd == null) {
                    journal.abort(key)
                    return@withContext Result.failure(IOException("Failed to open file descriptor for writing"))
                }

                // Past this point the target is being truncated/written: keep the slot on any failure.
                try {
                    withContext(NonCancellable) {
                        writeAndSync(pfd, bytes)
                        journal.discard(key) // durable success → drop the slot (even if cancelled)
                    }
                    Result.success(Unit)
                } catch (e: IOException) {
                    Result.failure(
                        IOException(
                            "Save failed; your content is staged and will be recovered on next launch. ${e.message}",
                            e,
                        ),
                    )
                } finally {
                    pfd.close()
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Re-apply interrupted saves (Safeguard 1). Idempotent; never throws to the caller. Runs under
     * the same lock as [saveFile] so it cannot race active edits. Each recoverable slot is streamed
     * to its target (no payload is read into memory); on success the slot is dropped, on failure it
     * is kept for a later launch. Returns the number of saves recovered.
     */
    override suspend fun recoverPendingSaves(): Result<Int> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                var recovered = 0
                for (entry in journal.recoverableEntries()) {
                    try {
                        when (verdictFor(entry.uri, entry.key, entry.file)) {
                            // Another app changed the file since the save began: keep the slot, so it
                            // is offered as Save a copy / Discard and the other app's text is not overwritten (G5).
                            RecoveryVerdict.CONFLICT -> continue
                            // The write had finished; only the slot's clean-up was missed.
                            RecoveryVerdict.DONE -> journal.discard(entry.key)
                            RecoveryVerdict.APPLY -> {
                                commitFileToTarget(entry.uri, entry.file)
                                journal.discard(entry.key)
                                recovered++
                            }
                        }
                    } catch (_: Exception) {
                        // Leave the slot in place; retry on the next launch.
                    }
                }
                Result.success(recovered)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx)?.let { return@withContext it }
                    }
                }
        } catch (_: Exception) { /* fall through to path-segment fallback */ }
        uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    override fun hasPersistedPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.isReadPermission && covers(it.uri, uri) }

    override fun hasPersistedWritePermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.isWritePermission && covers(it.uri, uri) }

    /**
     * Whether a grant on [granted] covers [uri]: the same URI, or (M-162b) a note opened from a notes
     * folder, whose tree-based URI has no grant of its own but sits under the folder's tree grant
     * (same provider, same tree document ID). The provider itself refuses a document outside the tree.
     */
    private fun covers(granted: Uri, uri: Uri): Boolean = granted == uri ||
        (granted.authority == uri.authority && treeIdOf(granted)?.let { it == treeIdOf(uri) } == true)

    private fun treeIdOf(uri: Uri): String? = if (DocumentsContract.isTreeUri(uri)) {
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    } else {
        null
    }

    /**
     * List stranded WAL slots (Safeguard 1 escape hatch). Acquires [journalMutex] — the same lock
     * [recoverPendingSaves] holds during launch recovery — so this suspends until recovery finishes;
     * whatever the journal still holds is, by definition, uncommittable/stranded. Pure read: it never
     * discards a slot or touches write-ordering. Each slot's display name is best-effort (the target
     * may be inaccessible → falls back to the URI's last path segment).
     */
    override suspend fun strandedSlots(): Result<List<StrandedSlot>> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                Result.success(
                    journal.strandedEntries().map { entry ->
                        StrandedSlot(key = entry.key, uri = entry.uri, displayName = displayName(entry.uri))
                    },
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Rescue a stranded slot to a new [targetUri] (user "Save a copy"). Streams the slot's RAW staged
     * bytes verbatim through the unchanged [commitFileToTarget] (they were already line-ending-
     * processed when staged — never re-processed, Safeguard 2), then discards the slot on success.
     * Never reads/touches the open document. On any failure the slot is kept (never auto-discarded).
     */
    override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            journalMutex.withLock {
                try {
                    val entry = journal.strandedEntries().firstOrNull { it.key == slotKey }
                        ?: return@withLock Result.failure(
                            IllegalStateException("No stranded slot for key $slotKey"),
                        )
                    commitFileToTarget(targetUri, entry.file)
                    journal.discard(slotKey) // durable success → drop the rescued slot
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(IOException("Failed to rescue stranded save: ${e.message}", e))
                }
            }
        }

    /** Explicitly discard a stranded slot (user "Discard"). User-initiated only — never automatic. */
    override suspend fun discardSlot(key: String): Result<Unit> = withContext(Dispatchers.IO) {
        journalMutex.withLock {
            try {
                journal.discard(key)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * DEBUG-ONLY crash-injection hook — reproduces the exact mid-write death window so crash
     * recovery can be demonstrated end-to-end on a device. Stages [content] to the WAL durably,
     * then truncates the target via a "wt" open WITHOUT writing/committing/discarding, leaving the
     * state a process death would: target zeroed, full content recoverable on next launch.
     *
     * Hard-gated by [BuildConfig.DEBUG] — a no-op failure in release — and only ever invoked from
     * the debug-source-set receiver, which is not present in release builds at all.
     */
    suspend fun debugStageWithoutCommit(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(IllegalStateException("debug-only hook is unavailable in release"))
        }
        journalMutex.withLock {
            try {
                // WAL durably committed (content + marker), with what the target held before.
                journal.stage(uri, content.toByteArray(Charsets.UTF_8), baseline = fingerprintOfTarget(uri))
                // Truncate the target to simulate process death DURING the target write — after the
                // WAL is committed but before the target write completes and the slot is discarded.
                contentResolver.openFileDescriptor(uri, "wt")?.close()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Decode [bytes] as UTF-8 STRICTLY, so an invalid sequence is detected instead of silently
     * becoming U+FFFD (NEW-12). Valid UTF-8 — a UTF-8 BOM included, which decodes to U+FEFF — encodes
     * back to exactly the same bytes, so it is safe to save. Anything else is still shown, decoded
     * leniently as before, but reported as not UTF-8 so the ViewModel refuses to save over it.
     */
    private fun decodeUtf8(bytes: ByteArray): FileText = try {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        FileText(strict.decode(ByteBuffer.wrap(bytes)).toString(), isUtf8 = true)
    } catch (_: CharacterCodingException) {
        FileText(bytes.toString(Charsets.UTF_8), isUtf8 = false)
    }

    /**
     * A pending slot another app's change kept from being applied (G5) stays pending when this save
     * succeeds: its text is not what this save writes, and only the user may let it go.
     */
    private fun parkSlotThatCouldNotBeApplied(uri: Uri) {
        val staged = journal.recoverableContentFor(uri) ?: return
        if (verdictFor(uri, journal.keyOf(uri), staged) == RecoveryVerdict.CONFLICT) journal.park(uri)
    }

    /** What recovering a staged slot onto its target would do (G5). */
    private enum class RecoveryVerdict { APPLY, DONE, CONFLICT }

    /** Length and SHA-256 of what [uri] holds now, or null when it cannot be read. */
    private fun fingerprintOfTarget(uri: Uri): String? = withTargetStream(uri) { ByteFingerprint.of(it) }

    /**
     * Decide whether the staged bytes in [staged] may be written over [uri] (G5). The target is
     * recovered onto only if it is still what the save began with, is empty or part-written by that
     * save (a prefix of the staged bytes), or already holds the staged bytes. Anything else is a file
     * another app changed in between. A target that cannot be read is left to the write, which fails
     * and keeps the slot, as before.
     */
    private fun verdictFor(uri: Uri, key: String, staged: File): RecoveryVerdict {
        val targetPrint = fingerprintOfTarget(uri) ?: return RecoveryVerdict.APPLY
        val stagedPrint = FileInputStream(staged).use { ByteFingerprint.of(it) }
        return when {
            targetPrint == stagedPrint -> RecoveryVerdict.DONE
            targetPrint == journal.baselineOf(key) -> RecoveryVerdict.APPLY
            targetPrint.startsWith("0:") -> RecoveryVerdict.APPLY
            isPrefixOfStaged(uri, staged) -> RecoveryVerdict.APPLY
            else -> RecoveryVerdict.CONFLICT
        }
    }

    /** Whether the target's bytes are the first bytes of [staged]: a write of it that was cut short. */
    private fun isPrefixOfStaged(uri: Uri, staged: File): Boolean =
        withTargetStream(uri) { target -> FileInputStream(staged).use { hasSamePrefix(target, it) } } ?: false

    /** Run [block] over a read stream of [uri]; null when it cannot be opened or read. */
    private fun <T> withTargetStream(uri: Uri, block: (java.io.InputStream) -> T): T? = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use(block)
        }
    } catch (_: Exception) {
        null
    }

    private fun hasSamePrefix(target: java.io.InputStream, expected: java.io.InputStream): Boolean {
        val a = ByteArray(PREFIX_CHUNK)
        val b = ByteArray(PREFIX_CHUNK)
        while (true) {
            val n = target.read(a)
            if (n < 0) return true
            var got = 0
            while (got < n) {
                val m = expected.read(b, got, n - got)
                if (m < 0) return false // the target is longer than the staged bytes
                got += m
            }
            if (!a.copyOfRange(0, n).contentEquals(b.copyOfRange(0, n))) return false
        }
    }

    /** Write [bytes] to the SAF target "wt" and fsync, then close. */
    private fun writeAndSync(pfd: ParcelFileDescriptor, bytes: ByteArray) {
        FileOutputStream(pfd.fileDescriptor).use { fos ->
            fos.write(bytes)
            fos.flushAndSync()
        }
    }

    /** Stream [source] (a staged WAL file) to the SAF target "wt" and fsync — flat memory use. */
    private fun commitFileToTarget(targetUri: Uri, source: File) {
        val pfd = contentResolver.openFileDescriptor(targetUri, "wt")
            ?: throw IOException("Failed to open file descriptor for writing")
        try {
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                FileInputStream(source).use { it.copyTo(fos) }
                fos.flushAndSync()
            }
        } finally {
            pfd.close()
        }
    }

    /** Flush and fsync; tolerate virtual providers (e.g. Drive) whose descriptors can't fsync. */
    private fun FileOutputStream.flushAndSync() {
        flush()
        try {
            fd.sync()
        } catch (_: SyncFailedException) {
            // The flush handed the bytes to the provider; it owns durability from here.
        }
    }
}
