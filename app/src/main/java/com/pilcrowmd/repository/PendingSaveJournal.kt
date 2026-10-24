// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable write-ahead log backing crash-safe saves (Safeguard 1, no data loss).
 *
 * Each pending save is one slot keyed by `sha256(uri)` — so repeated/failed saves to the same file
 * reuse the slot and a newer attempt can't leave a stale one. A slot is three files under
 * [walBaseDir]`/pending_saves/`:
 *  - `<key>.content`   — the full new bytes (written temp + fsync + atomic rename, so never partial);
 *  - `<key>.uri`       — the target SAF URI;
 *  - `<key>.committing`— an empty marker meaning "a commit has begun; the target may be truncated".
 *
 * Two more files serve the two ways a recovery can go wrong:
 *  - `<key>.base`      — the fingerprint of the target as it was BEFORE the save began (G5). Recovery
 *    re-applies the staged bytes only to a target that is still that file, a prefix of the staged
 *    bytes (our own interrupted write) or the staged bytes themselves; anything else was changed by
 *    another app meanwhile and is never overwritten.
 *  - `<key>.content.prev` — the older pending copy a newer save replaced (G6). Kept until the newer
 *    save is known to have opened its target, so a save that fails to open cannot delete it.
 *
 * The marker is what lets recovery tell "interrupted mid-commit (restore)" from "commit never began
 * / already finished (leave the target alone)".
 *
 * @param walBaseDir a durable, non-evictable dir (e.g. `Context.noBackupFilesDir`) — never a cache dir.
 */
internal class PendingSaveJournal(walBaseDir: File) {

    private val dir = File(walBaseDir, "pending_saves")

    /** Recoverable slot: target [uri] and the staged content [file] (streamed, never read into memory). */
    data class Entry(val key: String, val uri: Uri, val file: File)

    /**
     * Durably stage [bytes] for [uri] and mark commit intent — all before the caller touches the
     * target. Returns the slot key.
     */
    fun stage(uri: Uri, bytes: ByteArray, baseline: String? = null): String {
        dir.mkdirs()
        val key = keyOf(uri)
        keepOlderCopy(dir, key)
        atomicWrite(File(dir, "$key.content"), bytes)
        atomicWrite(File(dir, "$key.uri"), uri.toString().toByteArray(Charsets.UTF_8))
        if (baseline != null) {
            atomicWrite(File(dir, "$key.base"), baseline.toByteArray(Charsets.UTF_8))
        } else {
            File(dir, "$key.base").delete()
        }
        atomicWrite(File(dir, "$key.committing"), ByteArray(0))
        return key
    }

    /**
     * The save [stage] began for [key] never touched its target (G6): drop the new slot and, if an
     * older pending copy was replaced, put it back. A plain [discard] here deleted the older copy.
     */
    fun abort(key: String) {
        val prev = File(dir, "$key.content.prev")
        if (!prev.exists()) return discard(key)
        atomicMove(prev, File(dir, "$key.content"))
        // `.uri` is the same target; `.base` belonged to the new save, so the older copy has none.
        File(dir, "$key.base").delete()
        File(dir, "$key.committing").let { if (!it.exists()) atomicWrite(it, ByteArray(0)) }
    }

    /**
     * Keep the pending slot of [uri] as a slot of its own, listed beside whatever is staged for [uri]
     * next. For a slot recovery found the file had been changed under (G5): a later successful save
     * must not take it away, since its text did not come from the text that save is writing.
     */
    fun park(uri: Uri) {
        val key = keyOf(uri)
        val parked = "$key-kept-${System.nanoTime()}"
        val content = File(dir, "$key.content")
        if (!content.exists()) return
        val tmp = File(dir, "$parked.content.tmp")
        content.copyTo(tmp, overwrite = true)
        atomicMove(tmp, File(dir, "$parked.content"))
        atomicWrite(File(dir, "$parked.uri"), uri.toString().toByteArray(Charsets.UTF_8))
        // The committing marker last: a slot without it is dropped, so a kill half-way keeps the original only.
        atomicWrite(File(dir, "$parked.committing"), ByteArray(0))
    }

    /** The fingerprint [stage] recorded for [key]'s target, or null if none was (older slots). */
    fun baselineOf(key: String): String? = File(dir, "$key.base").takeIf { it.exists() }?.readText(Charsets.UTF_8)

    /**
     * The staged content file for [uri] IFF a commit was interrupted — i.e. both `<key>.content`
     * and the `<key>.committing` marker are present, so the on-disk target may be truncated and this
     * durable WAL copy is the real content. Returns null when there is nothing recoverable for [uri].
     *
     * Pure read: unlike [recoverableEntries] it never discards slots or sweeps orphans, so a reader
     * may call it without mutating journal state or touching write-ordering.
     */
    fun recoverableContentFor(uri: Uri): File? {
        val key = keyOf(uri)
        val content = File(dir, "$key.content")
        return if (content.exists() && File(dir, "$key.committing").exists()) content else null
    }

    /** Remove all files for [key] (call on a confirmed success or a target-untouched failure). */
    fun discard(key: String) {
        listOf(
            "$key.content", "$key.uri", "$key.committing", "$key.base", "$key.content.prev",
            "$key.content.tmp", "$key.uri.tmp", "$key.base.tmp", "$key.content.prev.tmp",
        )
            .forEach { File(dir, it).delete() }
    }

    /**
     * Slots that began committing but were never confirmed — the ones to re-apply on launch.
     * Side effects: drops slots whose commit never began or already finished (no marker) without
     * touching their target, and sweeps temp/orphan stragglers.
     */
    fun recoverableEntries(): List<Entry> {
        if (!dir.isDirectory) return emptyList()
        val keys = dir.listFiles()
            ?.filter { it.name.endsWith(".content") }
            ?.map { it.name.removeSuffix(".content") }
            ?: emptyList()

        val entries = ArrayList<Entry>()
        for (key in keys) {
            val uriFile = File(dir, "$key.uri")
            if (!uriFile.exists() || !File(dir, "$key.committing").exists()) {
                discard(key) // staging incomplete, or commit never began / already finished
                continue
            }
            entries += Entry(key, Uri.parse(uriFile.readText()), File(dir, "$key.content"))
        }
        sweepOrphans()
        return entries
    }

    /**
     * Fully-staged slots with a commit marker — the same set [recoverableEntries] returns, but as a
     * **pure read**: no slot is discarded, no orphans are swept, no write-ordering is touched. Used to
     * LIST stranded slots for the user (called after launch recovery, so what remains is stranded).
     */
    fun strandedEntries(): List<Entry> {
        if (!dir.isDirectory) return emptyList()
        val keys = dir.listFiles()
            ?.filter { it.name.endsWith(".content") }
            ?.map { it.name.removeSuffix(".content") }
            ?: emptyList()

        val entries = ArrayList<Entry>()
        for (key in keys) {
            val uriFile = File(dir, "$key.uri")
            if (uriFile.exists() && File(dir, "$key.committing").exists()) {
                entries += Entry(key, Uri.parse(uriFile.readText()), File(dir, "$key.content"))
            }
        }
        return entries
    }

    /** Per-URI key so a file's slot is stable across repeated save attempts. */
    fun keyOf(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun sweepOrphans() {
        dir.listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".tmp") -> f.delete()
                f.name.endsWith(".content.prev") -> {
                    if (!File(dir, f.name.removeSuffix(".prev")).exists()) f.delete()
                }
                f.name.endsWith(".uri") || f.name.endsWith(".committing") || f.name.endsWith(".base") -> {
                    val base = f.name.substringBeforeLast('.')
                    if (!File(dir, "$base.content").exists()) f.delete()
                }
            }
        }
    }
}

/** Durably write [bytes] to [finalFile] via temp file + fsync + atomic move (replace). */
private fun atomicWrite(finalFile: File, bytes: ByteArray) {
    val tmp = File(finalFile.parentFile, "${finalFile.name}.tmp")
    FileOutputStream(tmp).use { fos ->
        fos.write(bytes)
        fos.flush()
        fos.fd.sync()
    }
    atomicMove(tmp, finalFile)
}

// ATOMIC_MOVE replaces atomically on the same mount (minSdk 26) — no delete+rename window.
private fun atomicMove(from: File, to: File) {
    Files.move(
        from.toPath(),
        to.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

/** Before [stage] overwrites an older pending copy, keep it (G6). A copy, so a crash leaves both whole. */
private fun keepOlderCopy(dir: File, key: String) {
    val content = File(dir, "$key.content")
    if (!content.exists() || !File(dir, "$key.committing").exists()) {
        File(dir, "$key.content.prev").delete() // a leftover from a crashed save is not an older copy
        return
    }
    val tmp = File(dir, "$key.content.prev.tmp")
    content.copyTo(tmp, overwrite = true)
    atomicMove(tmp, File(dir, "$key.content.prev"))
}
