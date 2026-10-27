// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import com.pilcrowmd.domain.folders.FolderEntry
import kotlinx.coroutines.flow.Flow

/**
 * An added notes folder. [available] is false once Android no longer lists a persisted read grant
 * for [treeUri] (revoked, or the source app is gone): the list shows it greyed, with Find again (F4).
 */
data class NotesFolder(val treeUri: Uri, val name: String, val parentHint: String?, val available: Boolean)

sealed interface AddFolderResult {
    data class Added(val folder: NotesFolder) : AddFolderResult

    /** F1/P13: the folder is in the list already; the existing entry gets the tick. */
    data class AlreadyListed(val folder: NotesFolder) : AddFolderResult

    /** F1/P7: the picker returned something that is not a folder. */
    data object NotAFolder : AddFolderResult

    /** Android would not keep access to the folder. */
    data object PermissionRefused : AddFolderResult
}

sealed interface FolderListing {
    data class Listed(val root: FolderEntry) : FolderListing

    /** No grant, or the folder could not be read: never shown as an empty list (F4). */
    data object Unavailable : FolderListing
}

/**
 * M-162b: the notes folders the user added (spec section 2) and their notes (section 3). Read
 * only: nothing here writes, renames or deletes a file.
 */
interface NotesFolderRepository {
    /** The added folders in the order added, each with whether its grant still holds. */
    val folders: Flow<List<NotesFolder>>

    suspend fun add(treeUri: Uri): AddFolderResult

    /** F5: drops the folder from the list and gives back its grant. Never touches a file. */
    suspend fun remove(treeUri: Uri)

    /**
     * F4/P11: [picked] takes the place of [old] in the list, which keeps its position, and gets the
     * tick. Refused when [picked] is another folder already in the list.
     */
    suspend fun findAgain(old: Uri, picked: Uri): AddFolderResult

    /** L1-L5, off the main thread (L7). */
    suspend fun list(treeUri: Uri): FolderListing

    /** L6: emits when any of the folders [folderIds] inside [treeUri] changes, while collected. */
    fun changes(treeUri: Uri, folderIds: Set<String>): Flow<Unit>

    /** The document URI the reader opens for the note [documentId] of the folder [treeUri]. */
    fun noteUri(treeUri: Uri, documentId: String): Uri

    /** The document ID [documentUri] names (tree-based or not), or null when it names no document. */
    fun documentIdOf(documentUri: Uri): String?

    /** The note's current date and size, off the main thread (M4); null when the provider cannot answer. */
    suspend fun noteStats(documentUri: Uri): DocumentStats?
}
