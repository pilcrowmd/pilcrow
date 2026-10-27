// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * M-160: the notes-folder spec section 5 layout on a fake provider, for the link tests.
 *
 * Notes/ (added folder) holds index.md, Note 2.md and projects/, which holds plan.md and img.png.
 * Other/ holds secret.md and is never added. Document IDs are paths, as the external-storage provider's are.
 */
internal object NotesTreeFixture {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val ROOT = "primary:Notes"
    const val PROJECTS = "$ROOT/projects"
    const val OTHER = "primary:Other"

    val notesTree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT)
    val projectsTree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, PROJECTS)
    val otherTree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, OTHER)

    /** A note as the Files screen opens it: a document URI under the Notes tree. */
    fun inNotes(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(notesTree, documentId)

    /** A note as Recents or another app hands it over: a plain document URI (case 25). */
    fun plain(documentId: String): Uri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)

    fun folder(treeUri: Uri, name: String = "Notes", available: Boolean = true) =
        NotesFolder(treeUri, name, parentHint = null, available = available)

    /** The provider: path-like IDs, `pathFromRoot` confirms only a document under the tree's root. */
    class Tree : DocumentTree {
        val children = mapOf(
            ROOT to listOf(
                TreeChild("$ROOT/index.md", "index.md", isFolder = false),
                TreeChild("$ROOT/Note 2.md", "Note 2.md", isFolder = false),
                TreeChild(PROJECTS, "projects", isFolder = true),
            ),
            PROJECTS to listOf(
                TreeChild("$PROJECTS/plan.md", "plan.md", isFolder = false),
                TreeChild("$PROJECTS/img.png", "img.png", isFolder = false),
            ),
            OTHER to listOf(TreeChild("$OTHER/secret.md", "secret.md", isFolder = false)),
        )

        override fun pathFromRoot(treeUri: Uri, documentId: String): List<String>? {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            if (documentId != root && !documentId.startsWith("$root/")) return null
            val rest = documentId.removePrefix(root).split('/').filter { it.isNotEmpty() }
            return rest.runningFold(root) { path, part -> "$path/$part" }
        }

        override fun childrenOrNull(treeUri: Uri, folderId: String, waitWhileLoading: Boolean) = children[folderId]

        override fun children(treeUri: Uri, folderId: String) = children[folderId].orEmpty()

        override fun documentUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        override fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

        override fun describe(uri: Uri): NoteFile? = null

        override fun persistedTrees(): List<Uri> = emptyList()
        override fun takeTree(treeUri: Uri) = Unit
        override fun takeTreeReadWrite(treeUri: Uri) = Unit
        override fun releaseTree(treeUri: Uri) = Unit
        override fun displayName(treeUri: Uri, documentId: String): String? = null
        override fun providerLabel(authority: String): String? = null
        override fun watch(treeUri: Uri, folderId: String, onChange: () -> Unit) = AutoCloseable {}
        override fun documentStats(documentUri: Uri): DocumentStats? = null
    }

    /** The added folders, in the order added; nothing else is used by the link code. */
    class Folders(vararg added: NotesFolder) : NotesFolderRepository {
        override val folders = MutableStateFlow(added.toList())
        override suspend fun add(treeUri: Uri): AddFolderResult = error("unused")
        override suspend fun remove(treeUri: Uri) = error("unused")
        override suspend fun findAgain(old: Uri, picked: Uri): AddFolderResult = error("unused")
        override suspend fun list(treeUri: Uri): FolderListing = error("unused")
        override fun changes(treeUri: Uri, folderIds: Set<String>) = error("unused")
        override fun noteUri(treeUri: Uri, documentId: String): Uri = error("unused")
        override fun documentIdOf(documentUri: Uri): String? = error("unused")
        override suspend fun noteStats(documentUri: Uri): DocumentStats? = error("unused")
    }
}
