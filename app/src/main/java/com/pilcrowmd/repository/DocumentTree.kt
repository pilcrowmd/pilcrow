// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** One entry of a folder, as the documents provider lists it. */
data class TreeChild(val documentId: String, val name: String, val isFolder: Boolean)

/**
 * The few Storage Access Framework calls folder access needs, kept apart so the path rules in
 * [LocalFolderAccessRepository] can be tested without a documents provider.
 */
interface DocumentTree {
    /** Persisted folder grants with read access. */
    fun persistedTrees(): List<Uri>

    fun takeTree(treeUri: Uri)

    /** Document IDs from the root of [treeUri] down to [documentId], or null when it is not inside. */
    fun pathFromRoot(treeUri: Uri, documentId: String): List<String>?

    fun children(treeUri: Uri, folderId: String): List<TreeChild>

    fun documentUri(treeUri: Uri, documentId: String): Uri
}

/** [DocumentTree] over the real content resolver. */
class ProviderDocumentTree(private val resolver: ContentResolver) : DocumentTree {

    override fun persistedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    override fun takeTree(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * `findDocumentPath` on a tree-based URI: the provider itself checks that the document is under
     * the tree, and throws when it is not, so a sibling folder can never pass.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // "not inside" arrives as any of several
    override fun pathFromRoot(treeUri: Uri, documentId: String): List<String>? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        DocumentsContract.findDocumentPath(resolver, uri)?.path
    } catch (e: Exception) {
        null
    }

    override fun children(treeUri: Uri, folderId: String): List<TreeChild> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TreeChild(
                            documentId = cursor.getString(0),
                            name = cursor.getString(1).orEmpty(),
                            isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}
