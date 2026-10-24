// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Drafts as files, one per document, in `<baseDir>/drafts/<sha256(uri)>.draft` (Safeguard 1).
 *
 * [baseDir] must be durable and not backed up (`Context.noBackupFilesDir`): not `filesDir`, which
 * Auto Backup uploads, and not a cache dir, which Android may wipe. Plain `java.io`/`java.nio` only.
 *
 * A file holds a small header (the URI, line ending, baseline, time, length) and then the text as
 * UTF-8, and is written temp + fsync + atomic rename, so a kill part-way through a write leaves the
 * previous draft or none, never a torn one.
 *
 * **Order.** Each call takes a number the moment it is made; a write that finds a newer call for its
 * file has been made since is dropped. So a delete issued after a write is never undone by that write
 * landing late, however the two are scheduled.
 */
class LocalDraftRepository(
    baseDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DraftRepository {
    private val dir = File(baseDir, "drafts")
    private val lock = Mutex()
    private val calls = AtomicLong()
    private val latestCall = ConcurrentHashMap<String, Long>()

    override suspend fun write(draft: Draft): Result<Unit> = writeAs(takeCall(draft.uri), draft)

    override fun writeDetached(draft: Draft) {
        val call = takeCall(draft.uri)
        scope.launch { writeAs(call, draft) }
    }

    override suspend fun read(uri: Uri): Draft? = withContext(io) {
        lock.withLock { DraftFile.read(File(dir, "${DraftFile.keyOf(uri)}$SUFFIX"), withText = true)?.first }
    }

    override suspend fun delete(uri: Uri) {
        val call = takeCall(uri)
        withContext(io) {
            lock.withLock {
                if (latestCall[DraftFile.keyOf(uri)] == call) File(dir, "${DraftFile.keyOf(uri)}$SUFFIX").delete()
            }
        }
    }

    override suspend fun list(): List<DraftInfo> = withContext(io) {
        lock.withLock { infos() }
    }

    override suspend fun trim(limit: Int, keep: Uri, isProtected: suspend (DraftInfo) -> Boolean) {
        val all = list()
        if (all.size <= limit) return
        // Oldest first. A protected draft is skipped and does not count as removed.
        var remaining = all.size
        for (info in all.sortedBy { it.savedAt }.filter { it.uri != keep }) {
            if (remaining <= limit) break
            if (!isProtected(info)) {
                delete(info.uri)
                remaining--
            }
        }
    }

    private fun takeCall(uri: Uri): Long = calls.incrementAndGet().also { latestCall[DraftFile.keyOf(uri)] = it }

    private suspend fun writeAs(call: Long, draft: Draft): Result<Unit> = withContext(io) {
        lock.withLock {
            if (latestCall[DraftFile.keyOf(draft.uri)] != call) return@withLock Result.success(Unit)
            try {
                dir.mkdirs()
                DraftFile.write(File(dir, "${DraftFile.keyOf(draft.uri)}$SUFFIX"), draft)
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                // A draft is a convenience: running out of memory building one must not end the app.
                Result.failure(IOException("out of memory writing a draft", e))
            }
        }
    }

    private fun infos(): List<DraftInfo> = (dir.listFiles() ?: emptyArray())
        // Under the lock no write is running, so a temp file is the leftover of one a kill cut short.
        .onEach { if (it.name.endsWith("$SUFFIX.tmp")) it.delete() }
        .filter { it.name.endsWith(SUFFIX) }
        .mapNotNull { DraftFile.read(it, withText = false)?.second }
        .sortedByDescending { it.savedAt }

    private companion object {
        const val SUFFIX = ".draft"
    }
}
