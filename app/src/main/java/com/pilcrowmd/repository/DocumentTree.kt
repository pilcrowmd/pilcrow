// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** One entry of a folder, as the documents provider lists it. [size] is null when not reported. */
data class TreeChild(val documentId: String, val name: String, val isFolder: Boolean, val size: Long? = null)

/** A note's name and, when its provider reports it, its size in bytes. */
data class NoteFile(val name: String, val size: Long?)

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

    /** The name and size of any openable [uri], or null when it has no readable name. */
    fun describe(uri: Uri): NoteFile?

    /** The document ID of [treeUri]'s top folder. */
    fun rootId(treeUri: Uri): String

    fun releaseTree(treeUri: Uri)
}

/** [DocumentTree] over the real content resolver. */
class ProviderDocumentTree(private val resolver: ContentResolver) : DocumentTree {

    override fun persistedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    override fun takeTree(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseTree(treeUri: Uri) {
        resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    /** Columns are found by name: a provider may leave out the ones it does not know (SIZE). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any provider failure means "no name"
    override fun describe(uri: Uri): NoteFile? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (!cursor.moveToFirst() || name < 0 || cursor.isNull(name)) return@use null
                NoteFile(
                    name = cursor.getString(name),
                    size = if (size < 0 || cursor.isNull(size)) null else cursor.getLong(size),
                )
            }?.takeIf { it.name.isNotEmpty() }
    } catch (e: Exception) {
        null
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
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TreeChild(
                            documentId = cursor.getString(0),
                            name = cursor.getString(1).orEmpty(),
                            isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(SIZE_COLUMN)) null else cursor.getLong(SIZE_COLUMN),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    override fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private companion object {
        /** COLUMN_SIZE's place in the [children] projection. */
        const val SIZE_COLUMN = 3
    }
}
