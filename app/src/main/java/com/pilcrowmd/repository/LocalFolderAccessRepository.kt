// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * M-93: folder access over the Storage Access Framework.
 *
 * **The path rules.** A relative path is resolved by walking the granted folder by name, one
 * segment at a time, from the note's own folder — never by gluing strings into a document ID. A
 * `..` step goes back up the note's own chain of folders and is refused at the granted folder's
 * root, so nothing outside the grant is ever looked up. A rooted path (`/x.png`) is never resolved.
 *
 * **A note whose URI names no folder (M-272)**, opened from the picker's Recent view, an `msf:`
 * download or another app's share, is placed by name instead: the first granted folder whose top
 * level holds a file with the note's name and size is taken as the note's folder.
 */
class LocalFolderAccessRepository(private val tree: DocumentTree, private val sdkInt: Int = Build.VERSION.SDK_INT) :
    FolderAccessRepository {

    override suspend fun grantedFolders(): List<Uri> = withContext(Dispatchers.IO) {
        runCatching { tree.persistedTrees() }.getOrDefault(emptyList())
    }

    override suspend fun grantFolder(treeUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { tree.takeTree(treeUri) }.also {
            // Every pick looks again, even of a folder already granted: the note may have moved in.
            nameMatchLock.withLock { lastNameMatch = null }
        }
    }

    override suspend fun releaseFolder(treeUri: Uri) {
        withContext(Dispatchers.IO) { runCatching { tree.releaseTree(treeUri) } }
    }

    override suspend fun folderFor(noteUri: Uri): NoteFolder = withContext(Dispatchers.IO) {
        locate(noteUri)?.let { return@withContext NoteFolder.Covered(it.first) }
        val documentId = documentIdOf(noteUri)
        val folder = documentId?.let { DocumentFolders.folderOf(noteUri.authority, it, sdkInt) }
        when {
            folder != null && folder.ungrantable -> NoteFolder.Blocked(folder.key)
            folder != null -> NoteFolder.NeedsGrant(folder.key, pickerStart = noteUri)
            else -> tree.describe(noteUri)?.let {
                NoteFolder.Unlocated(it.name, pickerStart = noteUri.takeIf { documentId != null })
            } ?: NoteFolder.Unknown
        }
    }

    // The SecurityException is the answer itself (the grant is gone), not an error to pass on.
    @Suppress("SwallowedException")
    override suspend fun resolve(noteUri: Uri, relativePath: String): ImageLookup = withContext(Dispatchers.IO) {
        val segments = segmentsOf(relativePath) ?: return@withContext ImageLookup.Unavailable
        val (treeUri, chain) = locate(noteUri)
            ?: return@withContext when (folderFor(noteUri)) {
                is NoteFolder.NeedsGrant, is NoteFolder.Unlocated -> ImageLookup.NeedsFolder
                else -> ImageLookup.Unavailable
            }
        try {
            walk(treeUri, chain.dropLast(1).toMutableList(), segments)
        } catch (e: SecurityException) {
            // The grant went away between the check and the walk (revoked, or pruned by Android).
            ImageLookup.NeedsFolder
        }
    }

    /** From the note's folder down [segments]; [folders] runs from the tree root to that folder. */
    private fun walk(treeUri: Uri, folders: MutableList<String>, segments: List<String>): ImageLookup {
        for (segment in segments.dropLast(1)) {
            if (segment == "..") {
                // The tree root is the first entry: a step above it would leave the grant.
                if (folders.size <= 1) return ImageLookup.Unavailable
                folders.removeAt(folders.lastIndex)
                continue
            }
            folders += tree.children(treeUri, folders.last()).firstOrNull { it.isFolder && it.name == segment }
                ?.documentId ?: return ImageLookup.Unavailable
        }
        val file = tree.children(treeUri, folders.last()).firstOrNull { !it.isFolder && it.name == segments.last() }
        return file?.let { ImageLookup.Found(tree.documentUri(treeUri, it.documentId)) } ?: ImageLookup.Unavailable
    }

    /**
     * The granted folder holding the note, with the note's document-ID chain inside it (tree root
     * first, note last). Matched by name only when the note's URI cannot say which folder it is in.
     */
    private suspend fun locate(noteUri: Uri): Pair<Uri, List<String>>? {
        val grants = runCatching { tree.persistedTrees() }.getOrDefault(emptyList())
        val documentId = documentIdOf(noteUri)
        if (documentId != null) {
            coveringGrant(noteUri, documentId, grants)?.let { return it }
            if (DocumentFolders.folderOf(noteUri.authority, documentId, sdkInt) != null) return null
        }
        // One lookup at a time: a note's pictures resolve together, and all but the first find the answer.
        return nameMatchLock.withLock {
            val cached = lastNameMatch?.takeIf { it.noteUri == noteUri && it.grants == grants }
            if (cached != null) {
                cached.found
            } else {
                matchedByName(noteUri, grants).also { lastNameMatch = NameMatch(noteUri, grants, it) }
            }
        }
    }

    /** The first grant, of any provider, whose top level holds a file of the note's name and size. */
    private fun matchedByName(noteUri: Uri, grants: List<Uri>): Pair<Uri, List<String>>? {
        val note = tree.describe(noteUri) ?: return null
        return grants.firstNotNullOfOrNull { treeUri ->
            // One grant whose provider fails (gone, revoked) must not hide the others.
            runCatching {
                val root = tree.rootId(treeUri)
                tree.children(treeUri, root)
                    .firstOrNull { !it.isFolder && it.name == note.name && sameSize(it.size, note.size) }
                    ?.let { treeUri to listOf(root, it.documentId) }
            }.getOrNull()
        }
    }

    /**
     * The last [matchedByName] answer: a note's pictures all resolve against the same grants. Read
     * and written only under [nameMatchLock]; a pick ([grantFolder]) clears it.
     */
    private class NameMatch(val noteUri: Uri, val grants: List<Uri>, val found: Pair<Uri, List<String>>?)

    private val nameMatchLock = Mutex()
    private var lastNameMatch: NameMatch? = null

    private fun coveringGrant(noteUri: Uri, documentId: String, grants: List<Uri>): Pair<Uri, List<String>>? = grants
        .asSequence()
        .filter { it.authority == noteUri.authority }
        .firstNotNullOfOrNull { treeUri ->
            tree.pathFromRoot(treeUri, documentId)?.takeIf { it.size >= 2 }?.let { treeUri to it }
        }

    private fun documentIdOf(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** An unreported size never rules a file out; two reported sizes must agree. */
        fun sameSize(a: Long?, b: Long?): Boolean = a == null || b == null || a == b

        /** `images/a%20b.png` → [images, a b.png]. Null for a rooted or empty path. */
        fun segmentsOf(path: String): List<String>? {
            val bare = path.substringBefore('#').substringBefore('?').trim()
            if (bare.isEmpty() || bare.startsWith("/")) return null
            return bare.split('/')
                .filter { it.isNotEmpty() && it != "." }
                .map { Uri.decode(it) }
                .takeIf { it.isNotEmpty() && it.last() != ".." }
        }
    }
}
