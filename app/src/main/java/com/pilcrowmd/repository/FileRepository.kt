// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri

/**
 * A WAL save that could not be committed and remains stranded after launch recovery — the target URI
 * became permanently inaccessible (deleted / SAF permission lost) so [recoverPendingSaves] can never
 * land it. Surfaced so the user can rescue the bytes elsewhere or discard them (never auto-discarded).
 *
 * @param key   the journal slot key (stable per target).
 * @param uri   the original (now-inaccessible) target URI.
 * @param displayName best-effort name for the original target (for the user to recognise it).
 */
data class StrandedSlot(val key: String, val uri: Uri, val displayName: String)

/**
 * Abstraction for file access. v1 implements single-file via ContentResolver.
 * vNext hook: tree-URI (Obsidian-style vault) can be added later without ViewModel changes.
 */
interface FileRepository {
    /**
     * Read the content of a file by URI. v1: single document.
     * vNext: could extend to folder tree or cloud index.
     */
    suspend fun readFile(uri: Uri): Result<String>

    /**
     * Save content back to a file at the given URI. Crash-safe (Safeguard 1, no data loss):
     * the full new content is staged durably on disk and fsynced BEFORE the target is truncated,
     * so process death in the write window never loses the user's content — it is recovered by
     * [recoverPendingSaves] on the next launch.
     */
    suspend fun saveFile(uri: Uri, content: String): Result<Unit>

    /**
     * Re-apply any save that was interrupted before it could complete (Safeguard 1).
     * Called once per process at launch. Idempotent and never throws to the caller;
     * returns the number of pending saves successfully recovered.
     */
    suspend fun recoverPendingSaves(): Result<Int>

    /**
     * Take persistent read/write permission for a URI.
     * v1: SAF ContentResolver.takePersistableUriPermission().
     * vNext: could handle multi-file permission bookkeeping.
     */
    suspend fun takePersistableUriPermission(uri: Uri): Result<Unit>

    /** Best-effort display name for a URI (OpenableColumns.DISPLAY_NAME, falls back to last path segment). */
    suspend fun displayName(uri: Uri): String

    /** Whether a persisted read permission is still held for [uri] (for greying lost recents). */
    fun hasPersistedPermission(uri: Uri): Boolean

    /**
     * Whether a persisted *write* permission is still held for [uri] — the honest "can this be saved
     * in place?" signal. A file opened read-only via "Open with" holds at most a read grant, so an
     * in-place save would fail; the UI uses this to offer Save-As instead. Read-only query; touches
     * no save path.
     */
    fun hasPersistedWritePermission(uri: Uri): Boolean

    /**
     * The WAL slots still pending after launch recovery — i.e. saves that could not be committed and
     * are stranded (Safeguard 1: their content is preserved but not yet actionable). Acquires the
     * same journal lock as [saveFile]/[recoverPendingSaves], so a call suspends until launch-time
     * recovery finishes; whatever it returns is, by definition, stranded. Read-only — no slot is
     * discarded and no write-ordering is touched.
     */
    suspend fun strandedSlots(): Result<List<StrandedSlot>>

    /**
     * Rescue a stranded slot ([slotKey]) by writing its **raw staged bytes verbatim** to a new
     * user-chosen [targetUri] (the bytes were already line-ending-processed when staged, so they are
     * NOT re-processed — Safeguard 2), then discarding the slot on success. The currently-open
     * document is never read or modified. Reuses the unchanged file-commit primitive; on failure the
     * slot is kept (never auto-discarded).
     */
    suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri): Result<Unit>

    /**
     * Explicitly discard a stranded slot ([key]) at the user's request (the "Discard" action). Only
     * ever user-initiated — recovery/error paths must never auto-discard (Safeguard 1).
     */
    suspend fun discardSlot(key: String): Result<Unit>

    // vNext hooks (not implemented in v1, but interface is ready):
    // suspend fun listFiles(treeUri: Uri): Result<List<FileMetadata>>
    // suspend fun openFile(uri: Uri, mode: OpenMode): Result<Pair<Uri, String>>
}
