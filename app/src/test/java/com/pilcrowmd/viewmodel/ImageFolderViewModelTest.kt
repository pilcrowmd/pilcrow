// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.usecase.CountRelativeImagesUseCase
import com.pilcrowmd.repository.FolderAccessRepository
import com.pilcrowmd.repository.ImageLookup
import com.pilcrowmd.repository.NoteFolder
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M-93: when the reader shows the folder banner, and what each answer does.
 *
 * Every assertion first waits for a value the ViewModel publishes only once it has looked at the
 * note — the banner itself, or the granted-folders key, which starts empty and is non-empty in
 * every test here — never on a value that already holds before the work runs.
 */
@RunWith(RobolectricTestRunner::class)
class ImageFolderViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope
    private val folders = FakeFolders()

    /** Folder facts by note, and the grants Android holds; a pick runs [onGrant]. */
    private class FakeFolders : FolderAccessRepository {
        val byNote = ConcurrentHashMap<Uri, NoteFolder>()
        val grants = CopyOnWriteArrayList<Uri>()
        val released = CopyOnWriteArrayList<Uri>()
        var onGrant: (Uri) -> Unit = {}

        override suspend fun grantedFolders(): List<Uri> = grants.toList()

        // A real dispatcher hop, as in production: a pick's outcome arrives after onFolderPicked returns.
        override suspend fun grantFolder(treeUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
            grants += treeUri
            onGrant(treeUri)
            Result.success(Unit)
        }
        override suspend fun releaseFolder(treeUri: Uri) {
            released += treeUri
            grants -= treeUri
        }
        override suspend fun folderFor(noteUri: Uri): NoteFolder = byNote[noteUri] ?: NoteFolder.Unknown
        override suspend fun resolve(noteUri: Uri, relativePath: String): ImageLookup = ImageLookup.Unavailable
    }

    private val noteA = Uri.parse("content://docs/document/primary%3ANotes%2Fa.md")
    private val noteB = Uri.parse("content://docs/document/primary%3ANotes%2Fb.md")
    private val noteOther = Uri.parse("content://docs/document/primary%3ATrips%2Fc.md")
    private val noteInDownload = Uri.parse("content://docs/document/primary%3ADownload%2Fd.md")
    private val notesTree = Uri.parse("content://docs/tree/primary%3ANotes")
    private val unrelatedTree = Uri.parse("content://docs/tree/primary%3AMusic")
    private val noteRecent = Uri.parse("content://media/document/document%3A5")
    private val noteShared = Uri.parse("content://com.example.files/root/e.md")
    private val twoPictures = "# Trip\n\n![Hut](images/hut.jpg)\n\n![Map](images/map.png)\n"

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("image_folders.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
        // A grant the app holds for some other folder: the granted-folders key is never empty, so a
        // test can wait for it as proof that the ViewModel has looked at the note.
        folders.grants += unrelatedTree
        folders.byNote[noteA] = NoteFolder.NeedsGrant(NOTES, noteA)
        folders.byNote[noteB] = NoteFolder.NeedsGrant(NOTES, noteB)
        folders.byNote[noteOther] = NoteFolder.NeedsGrant(TRIPS, noteOther)
        folders.byNote[noteInDownload] = NoteFolder.Blocked(DOWNLOAD)
        folders.byNote[noteRecent] = NoteFolder.Unlocated("trip.md", pickerStart = noteRecent)
        folders.byNote[noteShared] = NoteFolder.Unlocated("e.md", pickerStart = null)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private fun vm() = ImageFolderViewModel(folders, storage, CountRelativeImagesUseCase())

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    /** Barrier: the ViewModel has looked at the note against exactly these grants. */
    private fun ImageFolderViewModel.awaitLookedAt(grants: List<Uri> = folders.grants.toList()) =
        awaitValue(grants.joinToString("|"), "note looked at") { state.value.accessKey }

    @Test
    fun `pictures next to the note and no grant show the banner, once per folder`() {
        val vm = vm()
        vm.onDocumentShown(noteA, twoPictures)

        awaitValue(ImageFolderBanner.AskForFolder(2), "banner") { vm.state.value.banner }
        assertTrue(vm.state.value.canAskForFolder)
    }

    @Test
    fun `no pictures next to the note means no banner, even with remote and embedded images`() {
        val vm = vm()
        vm.onDocumentShown(noteA, "![Badge](https://example.com/b.png) ![Dot](data:image/png;base64,AA==)\n")

        vm.awaitLookedAt()
        assertNull(vm.state.value.banner)
        assertFalse(vm.state.value.canAskForFolder)
    }

    @Test
    fun `a grant covering the note means no banner`() {
        folders.grants += notesTree
        folders.byNote[noteA] = NoteFolder.Covered(notesTree)
        val vm = vm()
        vm.onDocumentShown(noteA, twoPictures)

        vm.awaitLookedAt()
        assertNull(vm.state.value.banner)
    }

    @Test
    fun `Not now is remembered for the folder, not for other folders`() {
        val first = vm()
        first.onDocumentShown(noteA, twoPictures)
        awaitValue(ImageFolderBanner.AskForFolder(2), "banner before Not now") { first.state.value.banner }

        first.dismissBanner()
        awaitValue(null, "banner after Not now") { first.state.value.banner }

        // A fresh ViewModel (the app reopened), another note in the same folder: still remembered.
        val second = vm()
        second.onDocumentShown(noteB, twoPictures)
        second.awaitLookedAt()
        assertNull("same folder", second.state.value.banner)
        assertTrue("Tap to show still offered", second.state.value.canAskForFolder)

        second.onDocumentShown(noteOther, twoPictures)
        awaitValue(ImageFolderBanner.AskForFolder(2), "other folder") { second.state.value.banner }
    }

    @Test
    fun `a note in Download explains, and never offers the picker`() {
        val vm = vm()
        val picks = collectPicks(vm)
        vm.onDocumentShown(noteInDownload, twoPictures)

        awaitValue(ImageFolderBanner.CannotShowHere, "banner") { vm.state.value.banner }
        assertFalse(vm.state.value.canAskForFolder)
        vm.requestFolderAccess()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("picker requested: $picks", picks.isEmpty())
    }

    @Test
    fun `asking for the folder opens the picker at the note`() {
        val vm = vm()
        val picks = collectPicks(vm)
        vm.onDocumentShown(noteA, twoPictures)
        awaitValue(ImageFolderBanner.AskForFolder(2), "banner") { vm.state.value.banner }

        vm.requestFolderAccess()

        awaitValue(listOf(noteA), "picker start") { picks.toList() }
    }

    @Test
    fun `a revoked grant brings the banner back`() {
        folders.grants += notesTree
        folders.byNote[noteA] = NoteFolder.Covered(notesTree)
        val vm = vm()
        vm.onDocumentShown(noteA, twoPictures)
        vm.awaitLookedAt()
        assertNull(vm.state.value.banner)

        // Revoked in Android's settings (or pruned by Android) while the app was in the background.
        folders.grants -= notesTree
        folders.byNote[noteA] = NoteFolder.NeedsGrant(NOTES, noteA)
        vm.refresh()

        awaitValue(ImageFolderBanner.AskForFolder(2), "banner after revoke") { vm.state.value.banner }
    }

    @Test
    fun `granting clears the folder's Not now, so losing the grant later shows the banner again`() {
        val vm = vm()
        vm.onDocumentShown(noteA, twoPictures)
        awaitValue(ImageFolderBanner.AskForFolder(2), "banner") { vm.state.value.banner }
        vm.dismissBanner()
        awaitValue(null, "after Not now") { vm.state.value.banner }

        // "Tap to show" → the user picks the note's folder.
        folders.onGrant = { tree -> folders.byNote[noteA] = NoteFolder.Covered(tree) }
        vm.onFolderPicked(notesTree)
        vm.awaitLookedAt(listOf(unrelatedTree, notesTree))
        awaitValue(emptySet<String>(), "Not now cleared") { dismissedNow() }

        folders.grants -= notesTree
        folders.byNote[noteA] = NoteFolder.NeedsGrant(NOTES, noteA)
        vm.refresh()

        awaitValue(ImageFolderBanner.AskForFolder(2), "banner after the grant went") { vm.state.value.banner }
    }

    @Test
    fun `a note from Recent asks to pick its folder, and the picker starts where the repository says`() {
        val vm = vm()
        val picks = collectPicks(vm)
        vm.onDocumentShown(noteRecent, twoPictures)

        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }
        assertTrue(vm.state.value.canAskForFolder)
        vm.requestFolderAccess()
        awaitValue(listOf<Uri?>(noteRecent), "picker start at the note") { picks.toList() }

        // Another app's share: no document to start at, so Android's default.
        vm.onDocumentShown(noteShared, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "e.md"), "shared banner") { vm.state.value.banner }
        vm.requestFolderAccess()
        awaitValue(listOf(noteRecent, null), "picker at Android's default") { picks.toList() }
    }

    @Test
    fun `picking a new folder that does not hold the note says so and gives the grant back`() {
        val vm = vm()
        vm.onDocumentShown(noteRecent, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }

        vm.onFolderPicked(notesTree)

        awaitValue(ImageFolderBanner.WrongFolder("trip.md"), "wrong folder") { vm.state.value.banner }
        assertEquals(listOf(notesTree), folders.released.toList())
        assertTrue(vm.state.value.canAskForFolder)
    }

    @Test
    fun `picking a folder already granted that does not hold the note keeps that grant`() {
        val vm = vm()
        vm.onDocumentShown(noteRecent, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }

        vm.onFolderPicked(unrelatedTree)

        awaitValue(ImageFolderBanner.WrongFolder("trip.md"), "wrong folder") { vm.state.value.banner }
        assertTrue("released ${folders.released}", folders.released.isEmpty())
    }

    @Test
    fun `picking the folder that holds the note shows its pictures and keeps the grant`() {
        val vm = vm()
        vm.onDocumentShown(noteRecent, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }

        folders.onGrant = { tree -> folders.byNote[noteRecent] = NoteFolder.Covered(tree) }
        vm.onFolderPicked(notesTree)

        awaitValue(null, "banner after the right pick") { vm.state.value.banner }
        vm.awaitLookedAt(listOf(unrelatedTree, notesTree))
        assertTrue("released ${folders.released}", folders.released.isEmpty())
    }

    @Test
    fun `Not now on a note from Recent is remembered for that note`() {
        val vm = vm()
        vm.onDocumentShown(noteRecent, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }

        vm.dismissBanner()

        awaitValue(setOf("note:$noteRecent"), "stored for the note") { dismissedNow() }
        awaitValue(null, "banner after Not now") { vm.state.value.banner }
        assertTrue("Tap to show still offered", vm.state.value.canAskForFolder)
    }

    @Test
    fun `a wrong pick after Not now still says so, and Not now hides it again`() {
        val vm = vm()
        vm.onDocumentShown(noteRecent, twoPictures)
        awaitValue(ImageFolderBanner.PickNoteFolder(2, "trip.md"), "banner") { vm.state.value.banner }
        vm.dismissBanner()
        awaitValue(setOf("note:$noteRecent"), "stored for the note") { dismissedNow() }
        awaitValue(null, "banner after Not now") { vm.state.value.banner }

        // "Tap to show" → the user picks a folder that does not hold the note.
        vm.onFolderPicked(notesTree)

        awaitValue(ImageFolderBanner.WrongFolder("trip.md"), "wrong folder after Not now") { vm.state.value.banner }

        vm.dismissBanner()
        awaitValue(null, "wrong folder after Not now on it") { vm.state.value.banner }
    }

    private fun dismissedNow(): Set<String> = runBlocking { storage.dismissedImageFolders.first() }

    private fun collectPicks(vm: ImageFolderViewModel): MutableList<Uri?> {
        val picks = CopyOnWriteArrayList<Uri?>()
        storageScope.launch(Dispatchers.Unconfined) { vm.pickFolder.collect { picks += it } }
        return picks
    }

    private companion object {
        const val NOTES = "docs|primary:Notes"
        const val TRIPS = "docs|primary:Trips"
        const val DOWNLOAD = "docs|primary:Download"
    }
}
