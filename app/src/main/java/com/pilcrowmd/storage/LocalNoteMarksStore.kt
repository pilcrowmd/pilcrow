// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pilcrowmd.domain.folders.SeenRecord
import kotlinx.coroutines.flow.first

// Process-singleton, like the other preferences stores: one DataStore per file per process.
private val Context.noteMarksDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notes_marks",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** [NoteMarksStore] in its own Preferences DataStore file, `notes_marks`, one key per added folder. */
class LocalNoteMarksStore(
    context: Context,
    // Injected for tests (an ephemeral store per test); production uses the process singleton.
    private val dataStore: DataStore<Preferences> = context.applicationContext.noteMarksDataStore,
) : NoteMarksStore {

    override suspend fun get(treeUri: Uri): Map<String, SeenRecord>? =
        dataStore.data.first()[keyOf(treeUri)]?.let { decode(it) }

    override suspend fun put(treeUri: Uri, records: Map<String, SeenRecord>) {
        // An empty value still says "scanned": a folder with no notes yet is not first-scanned again.
        dataStore.edit { it[keyOf(treeUri)] = encode(records) }
    }

    override suspend fun drop(treeUri: Uri) {
        dataStore.edit { it.remove(keyOf(treeUri)) }
    }

    // The URI is percent-encoded, so the key is plain text whatever the provider put in it.
    private fun keyOf(treeUri: Uri) = stringPreferencesKey("marks:" + Uri.encode(treeUri.toString()))

    // One note per line: ID, date, size, new flag, new since, updated since, tab-separated. The ID is
    // percent-encoded, so a hostile ID can never split a field or a line; unknown values are empty.
    private fun encode(records: Map<String, SeenRecord>): String = records.entries.joinToString(RECORD_SEP) { (id, r) ->
        listOf(
            Uri.encode(id),
            r.lastModified?.toString().orEmpty(),
            r.size?.toString().orEmpty(),
            if (r.isNew) "1" else "0",
            r.newSince?.toString().orEmpty(),
            r.updatedSince?.toString().orEmpty(),
        ).joinToString(FIELD_SEP)
    }

    /** A line that does not read back cleanly is dropped: that note is then treated as New, not lost silently. */
    private fun decode(raw: String): Map<String, SeenRecord> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split(RECORD_SEP).mapNotNull { line ->
            val f = line.split(FIELD_SEP)
            val numbers = listOf(MODIFIED, SIZE, NEW_SINCE, UPDATED_SINCE).map { f.getOrNull(it) }
            val numbersRead = numbers.all { it != null && (it.isEmpty() || it.toLongOrNull() != null) }
            val readable = numbersRead && f.size == FIELDS && f[ID].isNotEmpty() && f[NEW] in setOf("0", "1")
            if (!readable) return@mapNotNull null
            val n = numbers.map { it?.toLongOrNull() }
            Uri.decode(f[ID]) to SeenRecord(n[0], n[1], f[NEW] == "1", n[2], n.last())
        }.toMap()
    }

    private companion object {
        const val FIELD_SEP = "\t"
        const val RECORD_SEP = "\n"
        const val FIELDS = 6

        // Field positions.
        const val ID = 0
        const val MODIFIED = 1
        const val SIZE = 2
        const val NEW = 3
        const val NEW_SINCE = 4
        const val UPDATED_SINCE = 5
    }
}
