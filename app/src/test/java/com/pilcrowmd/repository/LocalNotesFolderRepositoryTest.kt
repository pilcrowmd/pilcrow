// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StoredFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M-162b: adding, removing and finding again notes folders (spec section 2), and listing them
 * (section 3). The provider is a fake that holds Android's persisted grants and a small folder tree.
 */
@RunWith(RobolectricTestRunner::class)
class LocalNotesFolderRepositoryTest {

    /** A storage volume: grants with their access flags, folders by document ID, and a call log. */
    private class FakeTree : DocumentTree {
        val grants = mutableMapOf<Uri, Int>()
        val children = mutableMapOf<String, List<TreeChild>>()
        val names = mutableMapOf<String, String>()
        val released = mutableListOf<Uri>()
        val listingThreads = CopyOnWriteArrayList<Thread>()
        val waitedWhileLoading = CopyOnWriteArrayList<Boolean>()
        val watched = CopyOnWriteArrayList<String>()
        val watchers = CopyOnWriteArrayList<() -> Unit>()
        val closed = CopyOnWriteArrayList<String>()
        var refuseGrant = false

        override fun persistedTrees(): List<Uri> = grants.filterValues {
            it and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        }.keys.toList()

        override fun takeTree(treeUri: Uri) = takeTreeReadWrite(treeUri)

        override fun takeTreeReadWrite(treeUri: Uri) {
            if (refuseGrant) throw SecurityException("no persistable grant")
            grants[treeUri] = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        override fun releaseTree(treeUri: Uri) {
            released += treeUri
            grants.remove(treeUri)
        }

        override fun pathFromRoot(treeUri: Uri, documentId: String): List<String>? = null

        override fun children(treeUri: Uri, folderId: String): List<TreeChild> =
            childrenOrNull(treeUri, folderId).orEmpty()

        override fun childrenOrNull(treeUri: Uri, folderId: String, waitWhileLoading: Boolean): List<TreeChild>? {
            listingThreads += Thread.currentThread()
            waitedWhileLoading += waitWhileLoading
            return children[folderId]
        }

        override fun documentUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

        override fun describe(uri: Uri): NoteFile? = null

        override fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

        override fun displayName(treeUri: Uri, documentId: String): String? = names[documentId]

        override fun providerLabel(authority: String): String? = "Drive"

        override fun watch(treeUri: Uri, folderId: String, onChange: () -> Unit): AutoCloseable {
            watched += folderId
            watchers += onChange
            return AutoCloseable { closed += folderId }
        }

        override fun documentStats(documentUri: Uri): DocumentStats? = null
    }

    private class MemoryStore : NotesFolderStore {
        override val folders = MutableStateFlow<List<StoredFolder>>(emptyList())
        override val selectedFolder = MutableStateFlow<Uri?>(null)
        override val expanded = MutableStateFlow<Set<String>>(emptySet())
        override suspend fun setFolders(folders: List<StoredFolder>) {
            this.folders.value = folders
        }
        override suspend fun setSelectedFolder(uri: Uri?) {
            selectedFolder.value = uri
        }
        override suspend fun setExpanded(keys: Set<String>) {
            expanded.value = keys
        }
    }

    private val tree = FakeTree()
    private val store = MemoryStore()
    private val repo = LocalNotesFolderRepository(
        tree,
        store,
        ApplicationProvider.getApplicationContext<Context>().resources,
    )

    private fun treeOf(documentId: String, authority: String = STORAGE) =
        DocumentsContract.buildTreeDocumentUri(authority, documentId)

    private val work = treeOf("primary:Work/Notes")
    private val home = treeOf("primary:Home/Notes")
    private val top = treeOf("primary:Journal")
    private val drive = treeOf("acc=1;doc=9", DRIVE)

    private fun child(id: String, name: String, folder: Boolean = false) =
        TreeChild(id, name, folder, lastModified = 1_000L, size = 42L)

    init {
        tree.names["primary:Work/Notes"] = "Notes"
        tree.children["primary:Work/Notes"] = listOf(
            child("primary:Work/Notes/b.md", "b.md"),
            child("primary:Work/Notes/sub", "sub", folder = true),
            child("primary:Work/Notes/a.md", "a.md"),
        )
        tree.children["primary:Work/Notes/sub"] = listOf(child("primary:Work/Notes/sub/c.md", "c.md"))
    }

    @Test
    fun `adding a folder lists it and keeps read and write access`() = runBlocking {
        val result = repo.add(work)

        val expected = NotesFolder(work, "Notes", parentHint = "Work", available = true)
        assertEquals(AddFolderResult.Added(expected), result)
        assertEquals(listOf(expected), repo.folders.first())
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(rw, tree.grants[work])
    }

    @Test
    fun `a folder at the top of a volume is named after the volume, a cloud folder after its app`() = runBlocking {
        val sdCard = treeOf("1234-ABCD:Notes")
        repo.add(top)
        repo.add(sdCard)
        repo.add(drive)
        repo.add(treeOf("primary:")) // the volume itself: no parent, and phone storage is not named as an app

        val folders = repo.folders.first()
        assertEquals(listOf("Internal storage", "SD card", "Drive", null), folders.map { it.parentHint })
        assertEquals("Journal", folders[0].name) // no display name: the last part of the ID
    }

    @Test
    fun `adding the same folder again under another URI string ticks the existing entry`() = runBlocking {
        repo.add(work)
        val sameFolder = Uri.parse("content://$STORAGE/tree/primary%3aWork%2fNotes")
        assertTrue("a different string", sameFolder.toString() != work.toString())

        val result = repo.add(sameFolder)

        assertEquals(AddFolderResult.AlreadyListed(NotesFolder(work, "Notes", "Work", available = true)), result)
        assertEquals(listOf(work), repo.folders.first().map { it.treeUri })
        assertFalse("no second grant", sameFolder in tree.grants)
    }

    @Test
    fun `adding a listed folder whose grant is gone takes the new grant and makes it available`() = runBlocking {
        repo.add(work)
        tree.grants.remove(work)
        assertFalse(repo.folders.first().single().available)
        val sameFolder = Uri.parse("content://$STORAGE/tree/primary%3aWork%2fNotes")

        val result = repo.add(sameFolder)

        assertEquals(AddFolderResult.AlreadyListed(NotesFolder(sameFolder, "Notes", "Work", available = true)), result)
        assertEquals(listOf(NotesFolder(sameFolder, "Notes", "Work", available = true)), repo.folders.first())
        assertTrue(sameFolder in tree.grants)
    }

    @Test
    fun `adding a listed folder whose grant is gone, with the grant refused, leaves it unavailable`() = runBlocking {
        repo.add(work)
        tree.grants.remove(work)
        tree.refuseGrant = true

        val result = repo.add(work)

        assertEquals(AddFolderResult.AlreadyListed(NotesFolder(work, "Notes", "Work", available = false)), result)
        assertFalse(repo.folders.first().single().available)
    }

    @Test
    fun `a file is not a folder`() = runBlocking {
        val file = DocumentsContract.buildDocumentUri(STORAGE, "primary:Work/Notes/a.md")

        assertEquals(AddFolderResult.NotAFolder, repo.add(file))
        assertTrue(tree.grants.isEmpty())
        assertTrue(repo.folders.first().isEmpty())
    }

    @Test
    fun `a refused grant adds nothing`() = runBlocking {
        tree.refuseGrant = true

        assertEquals(AddFolderResult.PermissionRefused, repo.add(work))
        assertTrue(repo.folders.first().isEmpty())
    }

    @Test
    fun `removing drops the folder and gives back its grant`() = runBlocking {
        repo.add(work)
        repo.add(home)

        repo.remove(work)

        assertEquals(listOf(home), repo.folders.first().map { it.treeUri })
        assertEquals(listOf(work), tree.released)
        // DocumentTree has no call that writes, renames or deletes: files cannot be touched.
        assertEquals(setOf(home), tree.grants.keys)
    }

    @Test
    fun `Find again refuses a folder already in the list`() = runBlocking {
        repo.add(work)
        repo.add(home)
        val homeAgain = Uri.parse("content://$STORAGE/tree/primary%3aHome%2fNotes")

        val result = repo.findAgain(old = work, picked = homeAgain)

        assertEquals(AddFolderResult.AlreadyListed(NotesFolder(home, "Notes", "Home", available = true)), result)
        assertEquals(listOf(work, home), repo.folders.first().map { it.treeUri })
        assertTrue("nothing released", tree.released.isEmpty())
    }

    @Test
    fun `Find again replaces the folder in place and ticks it`() = runBlocking {
        repo.add(work)
        repo.add(home)
        repo.add(drive)
        tree.grants.remove(home) // the grant was lost
        val moved = treeOf("primary:Archive/Notes")

        val result = repo.findAgain(old = home, picked = moved)

        assertEquals(AddFolderResult.Added(NotesFolder(moved, "Notes", "Archive", available = true)), result)
        assertEquals(listOf(work, moved, drive), repo.folders.first().map { it.treeUri })
        assertEquals(listOf(home), tree.released)
        assertEquals(moved, store.selectedFolder.value)
    }

    @Test
    fun `Find again with the grant refused changes nothing`() = runBlocking {
        repo.add(work)
        repo.add(home)
        tree.grants.remove(home)
        tree.refuseGrant = true

        val result = repo.findAgain(old = home, picked = treeOf("primary:Archive/Notes"))

        assertEquals(AddFolderResult.PermissionRefused, result)
        assertEquals(listOf(work, home), repo.folders.first().map { it.treeUri })
        assertTrue("nothing released", tree.released.isEmpty())
    }

    @Test
    fun `Find again for a folder no longer listed adds the folder found and ticks it`() = runBlocking {
        repo.add(work)
        store.selectedFolder.value = work

        val result = repo.findAgain(old = treeOf("primary:Gone"), picked = home)

        assertEquals(AddFolderResult.Added(NotesFolder(home, "Notes", "Home", available = true)), result)
        assertEquals(listOf(work, home), repo.folders.first().map { it.treeUri })
        assertEquals(home, store.selectedFolder.value)
    }

    @Test
    fun `Find again with the same folder keeps the grant it just took`() = runBlocking {
        repo.add(work)
        tree.grants.remove(work)

        repo.findAgain(old = work, picked = work)

        assertTrue(repo.folders.first().single().available)
        assertTrue(tree.released.isEmpty())
    }

    @Test
    fun `a folder whose grant is gone is unavailable, and so is its listing`() = runBlocking {
        repo.add(work)
        tree.grants.remove(work)

        assertFalse(repo.folders.first().single().available)
        assertEquals(FolderListing.Unavailable, repo.list(work))
    }

    @Test
    fun `a root that cannot be read is unavailable, never an empty list`() = runBlocking {
        repo.add(home) // granted, but the fake has no listing for it

        assertEquals(FolderListing.Unavailable, repo.list(home))
    }

    @Test
    fun `listing gives the folder's notes with their dates and sizes`() = runBlocking {
        repo.add(work)

        val root = (repo.list(work) as FolderListing.Listed).root

        assertEquals("Notes", root.name)
        assertEquals(listOf("sub"), root.folders.map { it.name })
        assertEquals(listOf("a.md", "b.md"), root.notes.map { it.name })
        assertEquals(1_000L, root.notes.first().lastModified)
        assertEquals(42L, root.notes.first().size)
        assertEquals(3, root.noteCount)
        assertEquals("the notes list waits for a loading provider", listOf(true, true), tree.waitedWhileLoading)
    }

    @Test
    fun `L7 - listing never reads the provider on the main thread`() = runBlocking {
        assertEquals("the test runs on the main looper", Looper.getMainLooper().thread, Thread.currentThread())
        repo.add(work)

        repo.list(work)

        assertEquals(2, tree.listingThreads.size)
        val main = Looper.getMainLooper().thread
        assertTrue("read on the main thread: ${tree.listingThreads}", tree.listingThreads.none { it == main })
    }

    @Test
    fun `changes watch each folder while collected and close them all after`() = runBlocking {
        val events = CopyOnWriteArrayList<Unit>()
        val job = launch(Dispatchers.Default) {
            repo.changes(work, setOf("primary:Work/Notes", "primary:Work/Notes/sub")).collect { events += it }
        }
        withTimeout(5_000) { while (tree.watchers.size < 2) delay(5) }
        assertEquals(setOf("primary:Work/Notes", "primary:Work/Notes/sub"), tree.watched.toSet())

        tree.watchers[1]()
        withTimeout(5_000) { while (events.isEmpty()) delay(5) }

        job.cancel()
        job.join()
        assertEquals(setOf("primary:Work/Notes", "primary:Work/Notes/sub"), tree.closed.toSet())
    }

    private companion object {
        const val STORAGE = "com.android.externalstorage.documents"
        const val DRIVE = "com.google.android.apps.docs.storage"
    }
}
