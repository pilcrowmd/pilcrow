// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One entry of a folder, as the documents provider lists it. [size] and [lastModified] are null
 * when the provider does not know them.
 */
data class TreeChild(
    val documentId: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long? = null,
    val lastModified: Long? = null,
)

/** A note's name and, when its provider reports it, its size in bytes. */
data class NoteFile(val name: String, val size: Long?)

/**
 * The few Storage Access Framework calls folder access needs, kept apart so the path rules in
 * [LocalFolderAccessRepository] can be tested without a documents provider.
 */
@Suppress("TooManyFunctions") // one call per SAF operation; the notes folders (M-162b) need eight more
interface DocumentTree {
    /** Persisted folder grants with read access. */
    fun persistedTrees(): List<Uri>

    fun takeTree(treeUri: Uri)

    /** Document IDs from the root of [treeUri] down to [documentId], or null when it is not inside. */
    fun pathFromRoot(treeUri: Uri, documentId: String): List<String>?

    fun children(treeUri: Uri, folderId: String): List<TreeChild>

    /**
     * The folder's entries, or null when the provider could not list it. With [waitWhileLoading], a
     * provider still loading the folder is given time to finish (the notes list); without it, its
     * first answer is used (picture lookup, unchanged).
     */
    fun childrenOrNull(treeUri: Uri, folderId: String, waitWhileLoading: Boolean = false): List<TreeChild>?

    fun documentUri(treeUri: Uri, documentId: String): Uri

    /** The name and size of any openable [uri], or null when it has no readable name. */
    fun describe(uri: Uri): NoteFile?

    /** The document ID of [treeUri]'s top folder. */
    fun rootId(treeUri: Uri): String

    /**
     * M-162b: keep read AND write access to a notes folder across restarts, so a note opened from it
     * can be saved by the existing save path; read only when the provider will not grant write.
     */
    fun takeTreeReadWrite(treeUri: Uri)

    /** Give back the persisted grant on [treeUri], with exactly the access Android holds for it. */
    fun releaseTree(treeUri: Uri)

    /** The document's display name, or null when the provider does not give one. */
    fun displayName(treeUri: Uri, documentId: String): String?

    /** The name of the app behind [authority] ("Drive"), or null when it is not known. */
    fun providerLabel(authority: String): String?

    /**
     * Calls [onChange] when the provider reports a change in the folder [folderId], until closed.
     * Android's FileSystemProvider watches a directory only while a listing of it is open, so the
     * listing stays open until then. A provider that cannot be watched gives a handle that does nothing.
     */
    fun watch(treeUri: Uri, folderId: String, onChange: () -> Unit): AutoCloseable

    /** One document's date and size, in one query (M4); null when the provider does not answer. */
    fun documentStats(documentUri: Uri): DocumentStats?
}

/** A document's last-modified time and size; each null when the provider does not give it. */
data class DocumentStats(val lastModified: Long?, val size: Long?)

/** [DocumentTree] over the real content resolver. */
@Suppress("TooManyFunctions") // implements DocumentTree, one function per call
class ProviderDocumentTree(
    private val resolver: ContentResolver,
    private val packageManager: PackageManager,
    // How long to wait for a provider still loading a folder (see childrenOrNull); short in tests.
    private val loadingWaitMs: Long = LOADING_WAIT_MS,
) : DocumentTree {

    override fun persistedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    override fun takeTree(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    override fun children(treeUri: Uri, folderId: String): List<TreeChild> = childrenOrNull(treeUri, folderId).orEmpty()

    /**
     * A cloud provider can answer before it has every entry, flagging the cursor with
     * [DocumentsContract.EXTRA_LOADING]. With waitWhileLoading, the cursor then stays open until the provider signals a
     * change (or [loadingWaitMs] passes), and the folder is asked again; after [MAX_LOADING_WAITS]
     * waits, what the provider has is used. Callers are on an IO thread, so blocking here is fine.
     */
    override fun childrenOrNull(treeUri: Uri, folderId: String, waitWhileLoading: Boolean): List<TreeChild>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        var waitsLeft = if (waitWhileLoading) MAX_LOADING_WAITS else 0
        var children: List<TreeChild>? = null
        var loading = true
        while (loading) {
            val cursor = resolver.query(uri, childProjection, null, null, null) ?: return null
            cursor.use {
                loading = waitsLeft > 0 && it.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
                if (loading) {
                    it.awaitChange(loadingWaitMs)
                    waitsLeft--
                } else {
                    children = it.toChildren()
                }
            }
        }
        return children
    }

    override fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    @Suppress("SwallowedException") // the refusal of write is the answer: fall back to read
    override fun takeTreeReadWrite(treeUri: Uri) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(treeUri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // A read-only provider: keep what it does grant. If read is refused too, that throws.
            resolver.takePersistableUriPermission(treeUri, read)
        }
    }

    override fun releaseTree(treeUri: Uri) {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri } ?: return
        var flags = 0
        if (grant.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (grant.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags != 0) resolver.releasePersistableUriPermission(treeUri, flags)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider fails in several ways
    override fun displayName(treeUri: Uri, documentId: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider fails in several ways
    override fun providerLabel(authority: String): String? = try {
        packageManager.resolveContentProvider(authority, 0)?.loadLabel(packageManager)?.toString()
    } catch (e: Exception) {
        null
    }

    override fun watch(treeUri: Uri, folderId: String, onChange: () -> Unit): AutoCloseable {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = resolver.query(uri, projection, null, null, null) ?: return AutoCloseable {}
        val notify = onChange
        // No handler: the change arrives on a binder thread, and the caller only signals from it.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) = notify()
        }
        cursor.registerContentObserver(observer)
        return AutoCloseable {
            cursor.unregisterContentObserver(observer)
            cursor.close()
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider fails in several ways
    override fun documentStats(documentUri: Uri): DocumentStats? = try {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(documentUri, projection, null, null, null)?.use {
            if (it.moveToFirst()) DocumentStats(it.longOrNull(0), it.longOrNull(1)) else null
        }
    } catch (e: Exception) {
        null
    }
}

private fun Cursor.longOrNull(column: Int): Long? = if (column < 0 || isNull(column)) null else getLong(column)

private val childProjection = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE,
)

/** The rows of a cursor queried with [childProjection]. */
private fun Cursor.toChildren(): List<TreeChild> {
    val modified = getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    val size = getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
    return buildList {
        while (moveToNext()) {
            add(
                TreeChild(
                    documentId = getString(0),
                    name = getString(1).orEmpty(),
                    isFolder = getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = longOrNull(modified),
                    size = longOrNull(size),
                ),
            )
        }
    }
}

/** Blocks until the provider reports a change on this cursor, or [timeoutMs] passes. */
private fun Cursor.awaitChange(timeoutMs: Long) {
    val changed = CountDownLatch(1)
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) = changed.countDown()
    }
    registerContentObserver(observer)
    try {
        changed.await(timeoutMs, TimeUnit.MILLISECONDS)
    } finally {
        unregisterContentObserver(observer)
    }
}

private const val LOADING_WAIT_MS = 10_000L
private const val MAX_LOADING_WAITS = 2
