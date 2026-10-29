// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/**
 * What the app remembers of one note (spec section 3b), keyed by its document ID. [lastModified]
 * and [size] are the baseline: the values when the user last saw the note, or when the folder was
 * first scanned (M3). Either is null when the provider did not give it.
 */
data class SeenRecord(
    val lastModified: Long?,
    val size: Long?,
    val isNew: Boolean,
    val newSince: Long?,
    val updatedSince: Long?,
)

enum class Mark { NEW, UPDATED }

/** A note's mark and when it was first seen (M8). */
data class NoteMark(val mark: Mark, val since: Long)

/** How long ago a mark was set, for "added 12 min ago" (M8). Never negative. */
sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val count: Long) : Ago

    data class Hours(val count: Long) : Ago

    data class Days(val count: Long) : Ago
}

/** The New and Updated marks (spec section 3b, M1-M7). Pure: the caller supplies the clock. */
object NoteMarks {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /**
     * The records after a listing of [notes] at [now]. [previous] is null when the folder was never
     * scanned: then every note is a baseline and nothing is marked (M3). A note ID not seen before is
     * New (M1; a renamed or moved note has a new ID, M6) and stays New. Any other note is Updated
     * when its date or its size differs from the baseline, each compared only when known on both
     * sides, and never against the clock (M2). The baseline itself only changes when the note is
     * seen, or when the provider settles one of the app's own saves ([ownSaves], by note ID, S8-A46).
     * Records of notes no longer listed are dropped.
     */
    fun reconcile(
        previous: Map<String, SeenRecord>?,
        notes: List<NoteEntry>,
        now: Long,
        ownSaves: Map<String, OwnSave> = emptyMap(),
    ): Map<String, SeenRecord> {
        if (previous == null) return markAllSeen(notes)
        return notes.associate { note ->
            val old = previous[note.documentId]
            note.documentId to when {
                old == null -> SeenRecord(note.lastModified, note.size, isNew = true, newSince = now, null)
                old.isNew -> old
                settlesOwnSave(ownSaves[note.documentId], old, note, now) -> seen(note.lastModified, note.size)
                differs(old, note) -> old.copy(updatedSince = old.updatedSince ?: now)
                else -> old.copy(updatedSince = null)
            }
        }
    }

    fun markOf(record: SeenRecord): NoteMark? = when {
        record.isNew -> NoteMark(Mark.NEW, record.newSince ?: 0L)
        record.updatedSince != null -> NoteMark(Mark.UPDATED, record.updatedSince)
        else -> null
    }

    /**
     * A note as the user has just seen it (opened, or saved in the app), with its current date and
     * size as the new baseline: no mark (M4, M5).
     */
    fun seen(lastModified: Long?, size: Long?): SeenRecord =
        SeenRecord(lastModified, size, isNew = false, newSince = null, updatedSince = null)

    /** M5: every note of the folder, subfolders included, seen now. */
    fun markAllSeen(notes: List<NoteEntry>): Map<String, SeenRecord> =
        notes.associate { it.documentId to seen(it.lastModified, it.size) }

    /**
     * M7: for [root] and each folder below it, the number of marked notes anywhere inside. The root's
     * count is the total for the added folder's row.
     */
    fun markedCounts(root: FolderEntry, marks: Map<String, NoteMark>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        fun count(folder: FolderEntry): Int {
            val total = folder.notes.count { it.documentId in marks } + folder.folders.sumOf { count(it) }
            counts[folder.documentId] = total
            return total
        }
        count(root)
        return counts
    }

    /** M8: how long before [now] a mark set at [since] was. A clock set back reads as just now. */
    fun ago(since: Long, now: Long): Ago {
        val elapsed = now - since
        return when {
            elapsed < MINUTE_MS -> Ago.JustNow
            elapsed < HOUR_MS -> Ago.Minutes(elapsed / MINUTE_MS)
            elapsed < DAY_MS -> Ago.Hours(elapsed / HOUR_MS)
            else -> Ago.Days(elapsed / DAY_MS)
        }
    }

    private fun settlesOwnSave(save: OwnSave?, old: SeenRecord, note: NoteEntry, now: Long): Boolean =
        save?.settles(old.lastModified, old.size, note.lastModified, note.size, now) == true

    private fun differs(old: SeenRecord, note: NoteEntry): Boolean {
        val dateChanged = old.lastModified != null && note.lastModified != null && old.lastModified != note.lastModified
        val sizeChanged = old.size != null && note.size != null && old.size != note.size
        return dateChanged || sizeChanged
    }
}

/** Every note in this folder and all folders below it. */
fun FolderEntry.allNotes(): List<NoteEntry> = buildList {
    val folders = ArrayDeque(listOf(this@allNotes))
    while (folders.isNotEmpty()) {
        val folder = folders.removeFirst()
        addAll(folder.notes)
        folders.addAll(folder.folders)
    }
}
