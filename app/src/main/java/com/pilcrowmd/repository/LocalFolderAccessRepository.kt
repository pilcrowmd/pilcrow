// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M-93: folder access over the Storage Access Framework.
 *
 * **The path rules.** A relative path is resolved by walking the granted folder by name, one
 * segment at a time, from the note's own folder — never by gluing strings into a document ID. A
 * `..` step goes back up the note's own chain of folders and is refused at the granted folder's
 * root, so nothing outside the grant is ever looked up. A rooted path (`/x.png`) is never resolved.
 */
class LocalFolderAccessRepository(private val tree: DocumentTree, private val sdkInt: Int = Build.VERSION.SDK_INT) :
    FolderAccessRepository {

    override suspend fun grantedFolders(): List<Uri> = withContext(Dispatchers.IO) {
        runCatching { tree.persistedTrees() }.getOrDefault(emptyList())
    }

    override suspend fun grantFolder(treeUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { tree.takeTree(treeUri) }
    }

    override suspend fun folderFor(noteUri: Uri): NoteFolder = withContext(Dispatchers.IO) {
        val documentId = documentIdOf(noteUri) ?: return@withContext NoteFolder.Unknown
        coveringGrant(noteUri, documentId)?.let { return@withContext NoteFolder.Covered(it.first) }
        val folder = DocumentFolders.folderOf(noteUri.authority, documentId, sdkInt)
        when {
            folder == null -> NoteFolder.Unknown
            folder.ungrantable -> NoteFolder.Blocked(folder.key)
            else -> NoteFolder.NeedsGrant(folder.key, pickerStart = noteUri)
        }
    }

    // The SecurityException is the answer itself (the grant is gone), not an error to pass on.
    @Suppress("SwallowedException")
    override suspend fun resolve(noteUri: Uri, relativePath: String): ImageLookup = withContext(Dispatchers.IO) {
        val segments = segmentsOf(relativePath) ?: return@withContext ImageLookup.Unavailable
        val documentId = documentIdOf(noteUri) ?: return@withContext ImageLookup.Unavailable
        val (treeUri, chain) = coveringGrant(noteUri, documentId)
            ?: return@withContext if (folderFor(noteUri) is NoteFolder.NeedsGrant) {
                ImageLookup.NeedsFolder
            } else {
                ImageLookup.Unavailable
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

    /** The first granted folder holding the note, with the note's document-ID chain inside it. */
    private fun coveringGrant(noteUri: Uri, documentId: String): Pair<Uri, List<String>>? =
        runCatching { tree.persistedTrees() }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.authority == noteUri.authority }
            .firstNotNullOfOrNull { treeUri ->
                tree.pathFromRoot(treeUri, documentId)?.takeIf { it.size >= 2 }?.let { treeUri to it }
            }

    private fun documentIdOf(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private companion object {
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
