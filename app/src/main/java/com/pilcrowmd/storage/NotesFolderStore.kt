// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/** An added notes folder as remembered: its grant, its name, and where it sits (P15). */
data class StoredFolder(val treeUri: Uri, val name: String, val parentHint: String?)

/**
 * M-162b: what the notes-folder list remembers across restarts (F2, F6, L2). Kept apart from
 * [StorageManager] in its own preferences file.
 */
interface NotesFolderStore {
    /** The added folders, in the order added (P16). */
    val folders: Flow<List<StoredFolder>>

    suspend fun setFolders(folders: List<StoredFolder>)

    /** The ticked folder. */
    val selectedFolder: Flow<Uri?>

    suspend fun setSelectedFolder(uri: Uri?)

    /** L2: the open subfolders, as [expandedKey]s. */
    val expanded: Flow<Set<String>>

    suspend fun setExpanded(keys: Set<String>)
}

/** The [NotesFolderStore.expanded] key of the subfolder [documentId] in the added folder [treeUri]. */
fun expandedKey(treeUri: Uri, documentId: String): String = "$treeUri\u0000$documentId"
