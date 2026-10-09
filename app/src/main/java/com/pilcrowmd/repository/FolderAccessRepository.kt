// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri

/**
 * M-93: the note's folder, as far as showing its pictures goes.
 */
sealed interface NoteFolder {
    /** A folder grant covers the note; its relative pictures resolve inside [treeUri]. */
    data class Covered(val treeUri: Uri) : NoteFolder

    /** No grant covers it yet. The folder picker can be opened at [pickerStart], the note itself. */
    data class NeedsGrant(val folderKey: String, val pickerStart: Uri) : NoteFolder

    /** Android's folder picker refuses this folder (Download/, the top of storage). */
    data class Blocked(val folderKey: String) : NoteFolder

    /**
     * M-272: the note's URI does not say which folder it is in (opened from the picker's Recent
     * view, `msf:` IDs, another app's file share). A granted folder counts once its top level holds
     * a file named [noteName] of the note's size. The folder picker starts at [pickerStart] (the
     * note, when it is a document URI) or, when null, wherever Android opens it.
     */
    data class Unlocated(val noteName: String, val pickerStart: Uri?) : NoteFolder

    /** Nothing to offer: the note's folder is unknown and so is its name. */
    data object Unknown : NoteFolder
}

/** What a relative image path turned out to be. */
sealed interface ImageLookup {
    data class Found(val uri: Uri) : ImageLookup

    /** The picture could be shown once the user grants the note's folder. */
    data object NeedsFolder : ImageLookup

    /** Missing, outside the granted folder, or never resolvable. */
    data object Unavailable : ImageLookup
}

/** Resolves `images/photo.png` written in a note. Read by the reader's image loader. */
fun interface RelativeImageResolver {
    suspend fun resolve(noteUri: Uri, relativePath: String): ImageLookup
}

/**
 * M-93: folder access for the reader's pictures.
 *
 * The grant store is Android's own list of persisted folder (tree) grants, keyed by tree URI —
 * [grantedFolders] — so it cannot drift from what the system will actually honour: a grant Android
 * pruned, or one the user revoked, is simply no longer listed. It is not an image-only list: folder
 * browsing (M-162b) reads the same grants.
 */
interface FolderAccessRepository : RelativeImageResolver {
    /** Every folder the user has granted to the app, as tree URIs. */
    suspend fun grantedFolders(): List<Uri>

    /** Keep read access to the folder the user picked, across restarts. */
    suspend fun grantFolder(treeUri: Uri): Result<Unit>

    /** Give back a folder grant. Best effort: a failure is swallowed. */
    suspend fun releaseFolder(treeUri: Uri)

    /** Where [noteUri] sits, and whether a grant covers it. */
    suspend fun folderFor(noteUri: Uri): NoteFolder
}
