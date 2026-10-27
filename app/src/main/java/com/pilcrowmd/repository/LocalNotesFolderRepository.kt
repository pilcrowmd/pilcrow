// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.res.Resources
import android.net.Uri
import android.provider.DocumentsContract
import com.pilcrowmd.R
import com.pilcrowmd.domain.folders.ChildSource
import com.pilcrowmd.domain.folders.FolderLabels
import com.pilcrowmd.domain.folders.ListedChild
import com.pilcrowmd.domain.folders.NoteListing
import com.pilcrowmd.domain.folders.ParentHint
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StoredFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [NotesFolderRepository] over the Storage Access Framework, with the list kept in [store].
 *
 * Two picks are the same folder when they have the same provider and the same tree document ID,
 * whatever else differs in the URI (F1/P13). Every provider call runs on [ioDispatcher].
 * [resources] puts a folder's place into words when it is added (P15).
 */
@Suppress("TooManyFunctions") // implements NotesFolderRepository, one function per call
class LocalNotesFolderRepository(
    private val tree: DocumentTree,
    private val store: NotesFolderStore,
    private val resources: Resources,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NotesFolderRepository {

    // Add, remove and Find again each read the list and write it back.
    private val edits = Mutex()

    // Bumped on every listing, so availability is looked at again whenever the list is (on return
    // to the app, on refresh): a grant revoked meanwhile shows at once.
    private val grantChecks = MutableStateFlow(0)

    override val folders: Flow<List<NotesFolder>> = combine(store.folders, grantChecks) { stored, _ ->
        val grants = grants()
        stored.map { it.toNotesFolder(available = it.treeUri in grants) }
    }.flowOn(ioDispatcher)

    override suspend fun add(treeUri: Uri): AddFolderResult = withContext(ioDispatcher) {
        if (!DocumentsContract.isTreeUri(treeUri)) return@withContext AddFolderResult.NotAFolder
        edits.withLock {
            val current = store.folders.first()
            val at = current.indexOfFirst { sameFolder(it.treeUri, treeUri) }
            if (at >= 0) return@withLock AddFolderResult.AlreadyListed(renewIfLost(current, at, treeUri))
            val added = take(treeUri) ?: return@withLock AddFolderResult.PermissionRefused
            store.setFolders(current + added)
            AddFolderResult.Added(added.toNotesFolder(available = true))
        }
    }

    override suspend fun remove(treeUri: Uri) = withContext(ioDispatcher) {
        edits.withLock {
            store.setFolders(store.folders.first().filterNot { it.treeUri == treeUri })
            runCatching { tree.releaseTree(treeUri) }
            Unit
        }
    }

    override suspend fun findAgain(old: Uri, picked: Uri): AddFolderResult = withContext(ioDispatcher) {
        if (!DocumentsContract.isTreeUri(picked)) return@withContext AddFolderResult.NotAFolder
        val result = edits.withLock {
            val current = store.folders.first()
            val at = current.indexOfFirst { it.treeUri == old }
            if (at < 0) return@withLock null
            current.withIndex().firstOrNull { it.index != at && sameFolder(it.value.treeUri, picked) }?.let {
                return@withLock AddFolderResult.AlreadyListed(it.value.toNotesFolder(it.value.treeUri in grants()))
            }
            val found = take(picked) ?: return@withLock AddFolderResult.PermissionRefused
            store.setFolders(current.toMutableList().also { it[at] = found })
            // The same URI picked again holds the grant just taken: giving it back would lose it.
            if (old != picked) runCatching { tree.releaseTree(old) }
            store.setSelectedFolder(picked)
            AddFolderResult.Added(found.toNotesFolder(available = true))
        }
        // The old folder is no longer listed: this is a plain add, and the folder gets the tick.
        result ?: add(picked).also { added ->
            when (added) {
                is AddFolderResult.Added -> store.setSelectedFolder(added.folder.treeUri)
                is AddFolderResult.AlreadyListed -> store.setSelectedFolder(added.folder.treeUri)
                else -> Unit
            }
        }
    }

    override suspend fun list(treeUri: Uri): FolderListing = withContext(ioDispatcher) {
        grantChecks.update { it + 1 }
        if (treeUri !in grants()) return@withContext FolderListing.Unavailable
        val rootId = runCatching { tree.rootId(treeUri) }.getOrNull()
            ?: return@withContext FolderListing.Unavailable
        val name = store.folders.first().firstOrNull { it.treeUri == treeUri }?.name ?: rootId
        val context = coroutineContext
        val root = NoteListing.build(rootId, name, childSource(treeUri)) { context.ensureActive() }
        root?.let { FolderListing.Listed(it) } ?: FolderListing.Unavailable
    }

    override fun changes(treeUri: Uri, folderIds: Set<String>): Flow<Unit> = callbackFlow {
        val handles = CopyOnWriteArrayList<AutoCloseable>()
        try {
            withContext(ioDispatcher) {
                for (id in folderIds) {
                    runCatching { tree.watch(treeUri, id) { trySend(Unit) } }.getOrNull()?.let(handles::add)
                }
            }
            awaitClose()
        } finally {
            // Closing gives the open listings back to the provider; it must happen even when cancelled.
            withContext(NonCancellable + ioDispatcher) { handles.forEach { runCatching { it.close() } } }
        }
    }

    override fun noteUri(treeUri: Uri, documentId: String): Uri = tree.documentUri(treeUri, documentId)

    override fun documentIdOf(documentUri: Uri): String? = runCatching {
        DocumentsContract.getDocumentId(documentUri)
    }.getOrNull()

    override suspend fun noteStats(documentUri: Uri): DocumentStats? = withContext(ioDispatcher) {
        tree.documentStats(documentUri)
    }

    // Provider failures arrive as several exception types; any of them means "could not be read".
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun childSource(treeUri: Uri) = ChildSource { folderId ->
        try {
            tree.childrenOrNull(treeUri, folderId, waitWhileLoading = true)?.map {
                ListedChild(it.documentId, it.name, it.isFolder, it.lastModified, it.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The listed folder at [at], picked again as [picked]. When its grant is gone, the fresh one from
     * this pick is kept, under the picked URI, so the folder is available again in the same place.
     */
    @Suppress("SwallowedException") // a refused grant leaves the entry as it was: unavailable
    private suspend fun renewIfLost(current: List<StoredFolder>, at: Int, picked: Uri): NotesFolder {
        val listed = current[at]
        if (listed.treeUri in grants()) return listed.toNotesFolder(available = true)
        try {
            tree.takeTreeReadWrite(picked)
        } catch (e: SecurityException) {
            return listed.toNotesFolder(available = false)
        }
        val renewed = listed.copy(treeUri = picked)
        store.setFolders(current.toMutableList().also { it[at] = renewed })
        return renewed.toNotesFolder(available = true)
    }

    /** Takes the grant on [treeUri] and describes the folder; null when Android refuses the grant. */
    @Suppress("SwallowedException") // the refusal is the answer, reported as PermissionRefused
    private fun take(treeUri: Uri): StoredFolder? {
        try {
            tree.takeTreeReadWrite(treeUri)
        } catch (e: SecurityException) {
            return null
        }
        val rootId = tree.rootId(treeUri)
        val authority = treeUri.authority
        val name = tree.displayName(treeUri, rootId)
            ?: rootId.substringAfterLast('/').substringAfter(':').ifEmpty { rootId }
        val parentHint = when (val hint = FolderLabels.parentHintFromDocumentId(authority, rootId)) {
            is ParentHint.Named -> hint.name
            ParentHint.InternalStorage -> resources.getString(R.string.folder_source_internal)
            ParentHint.SdCard -> resources.getString(R.string.folder_source_sd)
            // Section 6: only another app's folder is named after the app ("Notes - Drive").
            ParentHint.Unknown ->
                authority?.takeIf { it != DocumentFolders.EXTERNAL_STORAGE }?.let { tree.providerLabel(it) }
        }
        return StoredFolder(treeUri, name, parentHint)
    }

    private fun sameFolder(a: Uri, b: Uri): Boolean = a.authority == b.authority &&
        runCatching { tree.rootId(a) == tree.rootId(b) }.getOrDefault(false)

    private fun grants(): Set<Uri> = runCatching { tree.persistedTrees().toSet() }.getOrDefault(emptySet())
}

private fun StoredFolder.toNotesFolder(available: Boolean) = NotesFolder(treeUri, name, parentHint, available)
