// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.pilcrowmd.repository.DocumentFolders.EXTERNAL_STORAGE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-93: the folder rules — which grant covers a note, and how `images/x.png` is walked inside it.
 * The documents provider is a fake that also enforces what the real one does: a folder outside a
 * grant cannot be listed.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFolderAccessRepositoryTest {

    private class Doc(val name: String, val isFolder: Boolean, val parent: String?)

    /** A tiny storage volume, with the grants the app holds and every folder it listed. */
    private class FakeTree : DocumentTree {
        val docs = mutableMapOf<String, Doc>()
        val grants = mutableListOf<Uri>()
        val listed = mutableListOf<String>()
        var revokeOnList = false

        fun add(id: String, isFolder: Boolean = false) {
            val parent = id.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.contains(':') }
            docs[id] = Doc(id.substringAfterLast('/').substringAfter(':'), isFolder, parent)
        }

        override fun persistedTrees(): List<Uri> = grants.toList()

        override fun takeTree(treeUri: Uri) {
            grants += treeUri
        }

        override fun pathFromRoot(treeUri: Uri, documentId: String): List<String>? {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            val chain = generateSequence(documentId) { docs[it]?.parent }.toList().reversed()
            val at = chain.indexOf(root)
            return if (at < 0 || documentId !in docs) null else chain.drop(at)
        }

        override fun children(treeUri: Uri, folderId: String): List<TreeChild> {
            if (revokeOnList) throw SecurityException("grant revoked")
            if (pathFromRoot(treeUri, folderId) == null) throw SecurityException("$folderId is outside $treeUri")
            listed += folderId
            return docs.filter {
                it.value.parent == folderId
            }.map { TreeChild(it.key, it.value.name, it.value.isFolder) }
        }

        override fun documentUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    private val tree = FakeTree().apply {
        add("primary:Documents", isFolder = true)
        add("primary:Documents/Notes", isFolder = true)
        add("primary:Documents/Notes/trip.md")
        add("primary:Documents/Notes/images", isFolder = true)
        add("primary:Documents/Notes/images/hut.jpg")
        add("primary:Documents/Notes/images/my photo.png")
        add("primary:Documents/Notes/Sub", isFolder = true)
        add("primary:Documents/Notes/Sub/deep.md")
        add("primary:Documents/shared", isFolder = true)
        add("primary:Documents/shared/map.png")
        add("primary:Download", isFolder = true)
        add("primary:Download/loose.md")
    }
    private val repo = LocalFolderAccessRepository(tree, sdkInt = 34)

    private fun note(id: String) = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, id)
    private fun grant(id: String) = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE, id).also {
        tree.grants +=
            it
    }
    private fun resolve(noteId: String, path: String) = runBlocking { repo.resolve(note(noteId), path) }
    private fun folderFor(noteId: String) = runBlocking { repo.folderFor(note(noteId)) }

    private val trip = "primary:Documents/Notes/trip.md"

    @Test
    fun `a picture next to the note resolves inside the granted folder`() {
        val notes = grant("primary:Documents/Notes")

        val found = resolve(trip, "images/hut.jpg")

        assertEquals(ImageLookup.Found(tree.documentUri(notes, "primary:Documents/Notes/images/hut.jpg")), found)
    }

    @Test
    fun `a percent-encoded name and a leading dot segment resolve`() {
        grant("primary:Documents/Notes")
        assertTrue(resolve(trip, "./images/my%20photo.png") is ImageLookup.Found)
    }

    @Test
    fun `a grant on a parent folder covers every note below it`() {
        val documents = grant("primary:Documents")

        assertEquals(NoteFolder.Covered(documents), folderFor("primary:Documents/Notes/Sub/deep.md"))
        assertTrue(resolve("primary:Documents/Notes/Sub/deep.md", "../images/hut.jpg") is ImageLookup.Found)
    }

    @Test
    fun `dot-dot inside the granted folder is followed`() {
        grant("primary:Documents")
        assertTrue(resolve(trip, "../shared/map.png") is ImageLookup.Found)
    }

    @Test
    fun `dot-dot above the granted folder is refused, and nothing outside it is ever listed`() {
        // Granted: Notes only. `../shared/map.png` exists, one level above the grant.
        grant("primary:Documents/Notes")

        assertEquals(ImageLookup.Unavailable, resolve(trip, "../shared/map.png"))
        assertTrue("listed ${tree.listed}", tree.listed.none { !it.startsWith("primary:Documents/Notes") })
    }

    @Test
    fun `a rooted path is never resolved`() {
        grant("primary:Documents")
        assertEquals(ImageLookup.Unavailable, resolve(trip, "/Documents/Notes/images/hut.jpg"))
        assertTrue(tree.listed.isEmpty())
    }

    @Test
    fun `a missing picture is unavailable`() {
        grant("primary:Documents/Notes")
        assertEquals(ImageLookup.Unavailable, resolve(trip, "images/gone.jpg"))
        assertEquals(ImageLookup.Unavailable, resolve(trip, "images"))
    }

    @Test
    fun `with no grant a picture needs the folder, and the picker starts at the note`() {
        assertEquals(ImageLookup.NeedsFolder, resolve(trip, "images/hut.jpg"))
        assertEquals(
            NoteFolder.NeedsGrant("$EXTERNAL_STORAGE|primary:Documents/Notes", pickerStart = note(trip)),
            folderFor(trip),
        )
    }

    @Test
    fun `a grant on a sibling folder does not cover the note`() {
        grant("primary:Documents/shared")
        assertTrue(folderFor(trip) is NoteFolder.NeedsGrant)
        assertEquals(ImageLookup.NeedsFolder, resolve(trip, "images/hut.jpg"))
    }

    @Test
    fun `a note straight in Download is blocked and never asks for a folder`() {
        assertEquals(NoteFolder.Blocked("$EXTERNAL_STORAGE|primary:Download"), folderFor("primary:Download/loose.md"))
        assertEquals(ImageLookup.Unavailable, resolve("primary:Download/loose.md", "images/hut.jpg"))
    }

    @Test
    fun `a grant revoked under the walk asks for the folder again instead of crashing`() {
        grant("primary:Documents/Notes")
        tree.revokeOnList = true
        assertEquals(ImageLookup.NeedsFolder, resolve(trip, "images/hut.jpg"))
    }

    @Test
    fun `a grant that is gone from Android's list no longer covers the note`() {
        val notes = grant("primary:Documents/Notes")
        assertEquals(NoteFolder.Covered(notes), folderFor(trip))

        tree.grants.clear() // revoked by the user, or pruned by Android

        assertTrue(folderFor(trip) is NoteFolder.NeedsGrant)
    }

    @Test
    fun `a note shared by another app has an unknown folder`() {
        val shared = Uri.parse("content://com.example.files.provider/root/Notes/trip.md")
        assertEquals(NoteFolder.Unknown, runBlocking { repo.folderFor(shared) })
        assertEquals(ImageLookup.Unavailable, runBlocking { repo.resolve(shared, "images/hut.jpg") })
    }
}
