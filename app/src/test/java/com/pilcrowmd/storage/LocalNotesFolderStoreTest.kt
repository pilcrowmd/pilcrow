// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** M-162b: the notes-folder list survives a restart exactly as it was (F2, P16, L2). */
@RunWith(RobolectricTestRunner::class)
class LocalNotesFolderStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    private val work = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AWork%2FNotes")
    private val home = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AHome%2FNotes")
    private val drive = Uri.parse("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3D9")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        file = tempFolder.newFile("notes_folders_test.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun store(): LocalNotesFolderStore {
        val context: Context = ApplicationProvider.getApplicationContext()
        return LocalNotesFolderStore(context, PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @Test
    fun `empty by default`() = runBlocking {
        val store = store()
        assertEquals(emptyList<StoredFolder>(), store.folders.first())
        assertNull(store.selectedFolder.first())
        assertEquals(emptySet<String>(), store.expanded.first())
    }

    @Test
    fun `folders keep the order they were added in, and round-trip`() = runBlocking {
        val store = store()
        val folders = listOf(
            StoredFolder(work, "Notes", "Work"),
            StoredFolder(drive, "Notes", null),
            StoredFolder(home, "Notes", "Home"),
        )
        store.setFolders(folders)
        assertEquals(folders, store.folders.first())

        store.setFolders(folders.reversed())
        assertEquals(folders.reversed(), store.folders.first())

        store.setFolders(emptyList())
        assertEquals(emptyList<StoredFolder>(), store.folders.first())
    }

    @Test
    fun `a name holding tabs and newlines round-trips and corrupts no other record`() = runBlocking {
        val store = store()
        val folders = listOf(
            StoredFolder(work, "a\tb\nc", "x\ny\tz"),
            StoredFolder(home, "\n", "\t"),
            StoredFolder(drive, "100% %41 plain", null),
        )
        store.setFolders(folders)
        assertEquals(folders, store.folders.first())
    }

    @Test
    fun `tick and open subfolders round-trip`() = runBlocking {
        val store = store()
        store.setSelectedFolder(home)
        val keys = setOf(expandedKey(home, "primary:Home/Notes/projects"), expandedKey(work, "x"))
        store.setExpanded(keys)

        assertEquals(home, store.selectedFolder.first())
        assertEquals(keys, store.expanded.first())
        store.setSelectedFolder(null)
        assertNull(store.selectedFolder.first())
        assertTrue(expandedKey(home, "a").startsWith("$home\u0000"))
    }
}
