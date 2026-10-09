// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import android.provider.DocumentsContract
import com.pilcrowmd.repository.DocumentFolders.EXTERNAL_STORAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * M-93: the folder rules — which grant covers a note, and how `images/x.png` is walked inside it.
 * The documents provider is a fake that also enforces what the real one does: a folder outside a
 * grant cannot be listed.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFolderAccessRepositoryTest {

    private class Doc(val name: String, val isFolder: Boolean, val parent: String?, val size: Long?)

    /**
     * A tiny storage volume, with the grants the app holds and every folder it listed. [described]
     * is what other providers say about a note (its name and size); anything not in it is unreadable.
     * A tree in [failing] has a provider that throws; [listDelayMs] makes every listing slow, so
     * concurrent callers overlap.
     */
    private class FakeTree : DocumentTree {
        val docs = mutableMapOf<String, Doc>()
        val grants = mutableListOf<Uri>()
        val listed = CopyOnWriteArrayList<String>()
        val describeCalls = AtomicInteger()
        val failing = mutableSetOf<Uri>()
        var listDelayMs = 0L
        val described = mutableMapOf<Uri, NoteFile>()
        var revokeOnList = false

        fun add(id: String, isFolder: Boolean = false, size: Long? = null) {
            val parent = id.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.contains(':') }
            docs[id] = Doc(id.substringAfterLast('/').substringAfter(':'), isFolder, parent, size)
        }

        override fun persistedTrees(): List<Uri> = grants.toList()

        // Android keeps one persisted grant per tree: taking it again changes nothing.
        override fun takeTree(treeUri: Uri) {
            if (treeUri !in grants) grants += treeUri
        }

        override fun pathFromRoot(treeUri: Uri, documentId: String): List<String>? {
            val root = DocumentsContract.getTreeDocumentId(treeUri)
            val chain = generateSequence(documentId) { docs[it]?.parent }.toList().reversed()
            val at = chain.indexOf(root)
            return if (at < 0 || documentId !in docs) null else chain.drop(at)
        }

        override fun children(treeUri: Uri, folderId: String): List<TreeChild> {
            check(treeUri !in failing) { "provider gone" }
            if (listDelayMs > 0) Thread.sleep(listDelayMs)
            if (revokeOnList) throw SecurityException("grant revoked")
            if (pathFromRoot(treeUri, folderId) == null) throw SecurityException("$folderId is outside $treeUri")
            listed += folderId
            return docs.filter {
                it.value.parent == folderId
            }.map { TreeChild(it.key, it.value.name, it.value.isFolder, it.value.size) }
        }

        override fun documentUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        override fun describe(uri: Uri): NoteFile? = described[uri].also { describeCalls.incrementAndGet() }

        override fun rootId(treeUri: Uri): String {
            check(treeUri !in failing) { "provider gone" }
            return DocumentsContract.getTreeDocumentId(treeUri)
        }

        override fun releaseTree(treeUri: Uri) {
            grants -= treeUri
        }
    }

    private val tree = FakeTree().apply {
        add("primary:Documents", isFolder = true)
        add("primary:Documents/Notes", isFolder = true)
        add("primary:Documents/Notes/trip.md", size = TRIP_SIZE)
        add("primary:Documents/Notes/pic.png")
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

    // M-272: a note opened from the picker's Recent view names no folder.
    private val recent = DocumentsContract.buildDocumentUri("com.android.providers.media.documents", "document:5")

    @Test
    fun `a note from Recent with no grant asks for its folder, starting the picker at the note`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE)

        assertEquals(NoteFolder.Unlocated("trip.md", pickerStart = recent), runBlocking { repo.folderFor(recent) })
        assertEquals(ImageLookup.NeedsFolder, runBlocking { repo.resolve(recent, "pic.png") })
    }

    @Test
    fun `a granted folder holding a file of the same name and size covers a note from Recent`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE)
        val notes = grant("primary:Documents/Notes")

        assertEquals(NoteFolder.Covered(notes), runBlocking { repo.folderFor(recent) })
        assertEquals(
            ImageLookup.Found(tree.documentUri(notes, "primary:Documents/Notes/pic.png")),
            runBlocking { repo.resolve(recent, "pic.png") },
        )
        assertEquals(ImageLookup.Unavailable, runBlocking { repo.resolve(recent, "../x.png") })
    }

    @Test
    fun `a file of the same name but another size does not cover a note from Recent`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE + 1)
        grant("primary:Documents/Notes")

        assertEquals(NoteFolder.Unlocated("trip.md", pickerStart = recent), runBlocking { repo.folderFor(recent) })
        assertEquals(ImageLookup.NeedsFolder, runBlocking { repo.resolve(recent, "pic.png") })
    }

    @Test
    fun `a note shared by another app asks for its folder, with the picker at Android's default`() {
        val shared = Uri.parse("content://com.example.files.provider/root/Notes/trip.md")
        tree.described[shared] = NoteFile("trip.md", null)

        assertEquals(NoteFolder.Unlocated("trip.md", pickerStart = null), runBlocking { repo.folderFor(shared) })
    }

    @Test
    fun `picking a granted folder again looks again, after the note was moved into it`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE)
        tree.docs.remove("primary:Documents/Notes/trip.md")
        val notes = grant("primary:Documents/Notes")
        assertEquals(NoteFolder.Unlocated("trip.md", pickerStart = recent), runBlocking { repo.folderFor(recent) })

        // The user moves the note into Notes, then picks Notes again: the grant list is unchanged.
        tree.add("primary:Documents/Notes/trip.md", size = TRIP_SIZE)
        runBlocking { repo.grantFolder(notes) }

        assertEquals(NoteFolder.Covered(notes), runBlocking { repo.folderFor(recent) })
    }

    @Test
    fun `a note's pictures resolving together look through the grants once`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE)
        grant("primary:Documents/shared") // no trip.md: listed only while looking for the note
        val notes = grant("primary:Documents/Notes")
        tree.listDelayMs = 200

        val found = runBlocking {
            List(5) { async(Dispatchers.IO) { repo.resolve(recent, "pic.png") } }.awaitAll()
        }

        assertEquals(List(5) { ImageLookup.Found(tree.documentUri(notes, "primary:Documents/Notes/pic.png")) }, found)
        assertEquals("shared listed", 1, tree.listed.count { it == "primary:Documents/shared" })
        assertEquals("note described", 1, tree.describeCalls.get())
    }

    @Test
    fun `a grant whose provider fails does not hide a later grant that holds the note`() {
        tree.described[recent] = NoteFile("trip.md", TRIP_SIZE)
        val gone = DocumentsContract.buildTreeDocumentUri("com.example.gone", "root")
        tree.grants += gone
        tree.failing += gone
        val notes = grant("primary:Documents/Notes")

        assertEquals(NoteFolder.Covered(notes), runBlocking { repo.folderFor(recent) })
    }

    @Test
    fun `a note whose name cannot be read has an unknown folder`() {
        val shared = Uri.parse("content://com.example.files.provider/root/Notes/trip.md")
        assertEquals(NoteFolder.Unknown, runBlocking { repo.folderFor(shared) })
        assertEquals(NoteFolder.Unknown, runBlocking { repo.folderFor(recent) })
        assertEquals(ImageLookup.Unavailable, runBlocking { repo.resolve(shared, "images/hut.jpg") })
    }

    private companion object {
        const val TRIP_SIZE = 120L
    }
}
