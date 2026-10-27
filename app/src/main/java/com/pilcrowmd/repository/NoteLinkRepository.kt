// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.pilcrowmd.domain.folders.BareNoteLinks
import com.pilcrowmd.domain.folders.DirEntry
import com.pilcrowmd.domain.folders.DirLister
import com.pilcrowmd.domain.folders.LinkTarget
import com.pilcrowmd.domain.folders.NoteLinkResolver
import com.pilcrowmd.domain.folders.outermostFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The added folder a note is read in: [rootId] is the folder's own document ID, [currentFolderChain]
 * the folder IDs from [rootId] down to the folder holding the note.
 */
data class NoteFolderContext(val treeUri: Uri, val rootId: String, val currentFolderChain: List<String>)

/** What a tapped link in a note does (notes-folder spec, section 4). */
sealed interface ResolvedLink {
    /** K1-K6: open this note; [heading] is the raw fragment, null when none. */
    data class OpenNote(val uri: Uri, val heading: String?) : ResolvedLink

    /** K8: hand this file to the system. */
    data class OpenFile(val uri: Uri, val mimeType: String?) : ResolvedLink

    /** K9: unchanged from today. */
    data class Web(val url: String) : ResolvedLink

    /** K7: "Note not found: [name]". */
    data class NotFound(val name: String) : ResolvedLink

    /** Today's behaviour: same-note links, other schemes, a note outside every added folder. */
    data object Unhandled : ResolvedLink
}

/** M-160: links between notes in an added notes folder. Read only; every provider call off the main thread. */
interface NoteLinkRepository {
    /** The added folder that applies to [noteUri] (the outermost, K2/P14), or null when none holds it. */
    suspend fun folderFor(noteUri: Uri): NoteFolderContext?

    /**
     * The names of the folders in [context], outermost (the added folder) first, down to the one
     * holding the note: what the open-file line shows. Off the main thread. A folder the provider
     * gives no name for is named after the last part of its document ID.
     */
    suspend fun folderNames(context: NoteFolderContext): List<String>

    suspend fun resolve(noteUri: Uri, destination: String): ResolvedLink

    /** K13/K14: the bare-link destinations in [content] that name a note in the folder. */
    suspend fun resolvableBareLinks(noteUri: Uri, content: String): Set<String>

    /** The same document: same provider and document ID, whether either URI is tree-based or not. */
    fun sameDocument(a: Uri, b: Uri): Boolean
}

/**
 * [NoteLinkRepository] over the notes folders and their folder grants. A note belongs to a folder
 * only when the provider confirms it ([DocumentTree.pathFromRoot] on the folder's tree, same
 * provider), so a note opened from Files, from Recents or from another app (case 25) all qualify.
 */
class LocalNoteLinkRepository(
    private val tree: DocumentTree,
    private val folders: NotesFolderRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NoteLinkRepository {

    override suspend fun folderFor(noteUri: Uri): NoteFolderContext? = withContext(ioDispatcher) {
        val documentId = documentIdOf(noteUri) ?: return@withContext null
        val candidates = folders.folders.first()
            .filter { it.available && it.treeUri.authority == noteUri.authority }
            .mapNotNull { folder ->
                runCatching { tree.pathFromRoot(folder.treeUri, documentId) }.getOrNull()
                    ?.takeIf { it.size >= 2 }
                    ?.let { folder.treeUri to it }
            }
        outermostFolder(candidates)?.let { (treeUri, chain) ->
            NoteFolderContext(treeUri, chain.first(), chain.dropLast(1))
        }
    }

    override suspend fun folderNames(context: NoteFolderContext): List<String> = withContext(ioDispatcher) {
        context.currentFolderChain.map { id ->
            tree.displayName(context.treeUri, id)
                ?: id.substringAfterLast('/').substringAfter(':').ifEmpty { id }
        }
    }

    override suspend fun resolve(noteUri: Uri, destination: String): ResolvedLink {
        val context = folderFor(noteUri) ?: return ResolvedLink.Unhandled
        return withContext(ioDispatcher) { resolveIn(context, resolver(context), destination) }
    }

    override suspend fun resolvableBareLinks(noteUri: Uri, content: String): Set<String> {
        val context = folderFor(noteUri) ?: return emptySet()
        return withContext(ioDispatcher) {
            val resolver = resolver(context)
            // At most MAX_BARE_CANDIDATES per document: each one may list folders, and a note with
            // more bare links than that is not worth stalling the reader for. The rest stay plain text.
            BareNoteLinks.find(content).asSequence()
                .map { it.destination }
                .distinct()
                .take(MAX_BARE_CANDIDATES)
                .filter { resolver.resolve(context.rootId, context.currentFolderChain, it) is LinkTarget.Note }
                .toSet()
        }
    }

    override fun sameDocument(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return a.authority == b.authority && documentIdOf(a)?.let { it == documentIdOf(b) } == true
    }

    private fun documentIdOf(uri: Uri): String? = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    private fun resolveIn(context: NoteFolderContext, resolver: NoteLinkResolver, destination: String) =
        when (val target = resolver.resolve(context.rootId, context.currentFolderChain, destination)) {
            is LinkTarget.Note -> ResolvedLink.OpenNote(tree.documentUri(context.treeUri, target.id), target.heading)
            is LinkTarget.OtherFile ->
                ResolvedLink.OpenFile(tree.documentUri(context.treeUri, target.id), mimeTypeOf(target.name))
            is LinkTarget.Web -> ResolvedLink.Web(target.url)
            is LinkTarget.NotFound -> ResolvedLink.NotFound(target.name)
            LinkTarget.Unhandled -> ResolvedLink.Unhandled
        }

    /** A resolver over the folder's listing, each folder listed at most once per resolver. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider fails in several ways
    private fun resolver(context: NoteFolderContext): NoteLinkResolver {
        val listed = HashMap<String, List<DirEntry>?>()
        return NoteLinkResolver(
            DirLister { folderId ->
                listed.getOrPut(folderId) {
                    try {
                        tree.childrenOrNull(context.treeUri, folderId)
                            ?.map { DirEntry(it.documentId, it.name, it.isFolder) }
                    } catch (e: Exception) {
                        null
                    }
                }
            },
        )
    }

    private companion object {
        const val MAX_BARE_CANDIDATES = 200

        fun mimeTypeOf(name: String): String? = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase(Locale.ROOT)) }
    }
}
