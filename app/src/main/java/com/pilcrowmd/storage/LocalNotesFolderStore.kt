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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Process-singleton, like the main preferences store: one DataStore per file per process.
private val Context.notesFoldersDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notes_folders",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** [NotesFolderStore] in its own Preferences DataStore file, `notes_folders`. */
class LocalNotesFolderStore(
    context: Context,
    // Injected for tests (an ephemeral store per test); production uses the process singleton.
    private val dataStore: DataStore<Preferences> = context.applicationContext.notesFoldersDataStore,
) : NotesFolderStore {

    private val foldersKey = stringPreferencesKey("folders")
    private val selectedKey = stringPreferencesKey("selected_folder")
    private val expandedSetKey = stringSetPreferencesKey("expanded")

    // Tab separates fields, newline separates records. Each field is percent-encoded, so a name
    // holding a tab or a newline round-trips exactly and can never split a record.
    private val fieldSep = "\t"
    private val recordSep = "\n"

    // URI and name, then the parent hint when there is one.
    private val minFields = 2
    private val maxFields = 3

    override val folders: Flow<List<StoredFolder>> =
        dataStore.data.map { decodeFolders(it[foldersKey]) }.distinctUntilChanged()

    override suspend fun setFolders(folders: List<StoredFolder>) {
        dataStore.edit { prefs ->
            if (folders.isEmpty()) prefs.remove(foldersKey) else prefs[foldersKey] = encodeFolders(folders)
        }
    }

    override val selectedFolder: Flow<Uri?> =
        dataStore.data.map { prefs -> prefs[selectedKey]?.let { Uri.parse(it) } }.distinctUntilChanged()

    override suspend fun setSelectedFolder(uri: Uri?) {
        dataStore.edit { prefs -> if (uri == null) prefs.remove(selectedKey) else prefs[selectedKey] = uri.toString() }
    }

    override val expanded: Flow<Set<String>> =
        dataStore.data.map { it[expandedSetKey].orEmpty() }.distinctUntilChanged()

    override suspend fun setExpanded(keys: Set<String>) {
        dataStore.edit { prefs -> prefs[expandedSetKey] = keys }
    }

    private fun encodeFolders(list: List<StoredFolder>): String = list.joinToString(recordSep) { folder ->
        listOfNotNull(folder.treeUri.toString(), folder.name, folder.parentHint)
            .joinToString(fieldSep) { Uri.encode(it) }
    }

    private fun decodeFolders(raw: String?): List<StoredFolder> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(recordSep).mapNotNull { record ->
            val parts = record.split(fieldSep).map { Uri.decode(it) }
            if (parts.size !in minFields..maxFields || parts[0].isEmpty()) return@mapNotNull null
            StoredFolder(Uri.parse(parts[0]), parts[1], parts.getOrNull(2))
        }
    }
}
