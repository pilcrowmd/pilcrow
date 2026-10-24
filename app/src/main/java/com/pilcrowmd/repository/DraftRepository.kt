// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri

/**
 * A copy of unsaved text, kept so a kill, a crash or a reboot does not lose it.
 *
 * @param text the editor's text exactly as it was (LF form): restoring it gives back the same string.
 * @param lineEnding the file's line ending, `"LF"` or `"CRLF"`, to apply if the draft is saved as a copy.
 * @param baseline the [ByteFingerprint] of the text the edits were typed on top of (what the file held
 *   when it was opened), to tell later whether the file has changed since.
 * @param savedAt when the draft was written, epoch milliseconds.
 */
data class Draft(val uri: Uri, val text: String, val lineEnding: String, val baseline: String, val savedAt: Long)

/** What a stored draft says about itself, read without loading its text. */
data class DraftInfo(val uri: Uri, val baseline: String, val savedAt: Long, val chars: Int)

/**
 * Where unsaved text is kept between keystrokes and the next save. One draft per file.
 *
 * App-private storage that is not backed up and not evictable, no network. A draft is never written
 * over the file it belongs to: the file is only ever written by the user's own save.
 */
interface DraftRepository {
    /** Store [draft] for its file, replacing the previous one. A reader sees the old draft or the new, never half. */
    suspend fun write(draft: Draft): Result<Unit>

    /** Same as [write], but not cancelled with the caller: for the last write before a screen goes away. */
    fun writeDetached(draft: Draft)

    /** The draft kept for [uri], or null when there is none or it cannot be read. */
    suspend fun read(uri: Uri): Draft?

    /** Remove the draft kept for [uri], if any. */
    suspend fun delete(uri: Uri)

    /**
     * Keep at most [limit] drafts: remove the oldest first, but never [keep] and never a draft for
     * which [isProtected] answers true. A draft whose file changed is the user's to discard, so the
     * store may end up holding more than [limit] of them.
     */
    suspend fun trim(limit: Int, keep: Uri, isProtected: suspend (DraftInfo) -> Boolean)

    /** What is stored, newest first. */
    suspend fun list(): List<DraftInfo>

    /** A repository that keeps nothing: for a ViewModel built without drafts. */
    object None : DraftRepository {
        override suspend fun write(draft: Draft): Result<Unit> = Result.success(Unit)

        override fun writeDetached(draft: Draft) = Unit

        override suspend fun read(uri: Uri): Draft? = null

        override suspend fun delete(uri: Uri) = Unit

        override suspend fun trim(limit: Int, keep: Uri, isProtected: suspend (DraftInfo) -> Boolean) = Unit

        override suspend fun list(): List<DraftInfo> = emptyList()
    }
}
