// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.testing.MainDispatcherSuite
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A55 "Allow editing": a read-only ("Open with") document adopts the file the user picks in the
 * system picker, but only when that file holds exactly what was opened.
 *
 * Set up like [MarkdownViewModelSaveAsTest] (real DataStore, fake repository, ViewModels cleared
 * before `resetMain`). Every barrier waits on the outcome `allowEditing` publishes, never on an
 * initial value, so no assertion can run before the pick was handled.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelAllowEditingTest {

    private val vmStore = ViewModelStore()
    private var vmSeq = 0

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val opened = Uri.parse("content://media/external/file/132")
    private val picked = Uri.parse("content://com.android.providers.downloads.documents/document/msf%3A132")

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("allow_editing_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear() // cancels every viewModelScope BEFORE the global is released
        storageScope.cancel()
        Dispatchers.resetMain()
    }

    /** Each URI reads its own text; a persisted write grant exists only after a successful take. */
    private class FakeRepo(private val files: Map<Uri, FileText>, private val takePermSucceeds: Boolean = true) :
        FileRepository {
        val writableUris = mutableSetOf<Uri>()
        val capturedSaves = linkedMapOf<Uri, String>()
        val released = mutableListOf<Uri>()

        override suspend fun releasePersistableUriPermission(uri: Uri) {
            released += uri
            writableUris.remove(uri)
        }

        override suspend fun readFile(uri: Uri): Result<FileText> =
            files[uri]?.let { Result.success(it) } ?: Result.failure(java.io.IOException("gone (test)"))

        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            capturedSaves[uri] = content
            return Result.success(Unit)
        }

        override suspend fun recoverPendingSaves() = Result.success(0)

        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = if (takePermSucceeds) {
            writableUris.add(uri)
            Result.success(Unit)
        } else {
            Result.failure(SecurityException("not persistable (test)"))
        }

        override suspend fun displayName(uri: Uri): String = "notes.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = uri in writableUris
        override fun hasPersistedWritePermission(uri: Uri): Boolean = uri in writableUris
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun vmWith(repo: FileRepository): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = repo,
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vmStore.put("vm-${vmSeq++}", vm)
        return vm
    }

    /** Opens [opened] read-only, as "Open with" does: no write grant, so the document is transient. */
    private suspend fun openReadOnly(repo: FakeRepo): MarkdownViewModel {
        val vm = vmWith(repo)
        vm.loadFile(opened)
        vm.currentDocument.first { it?.uri == opened }
        vm.fileLoadState.first { it is FileLoadState.Success }
        assertTrue("precondition: opened read-only", vm.transient.value)
        return vm
    }

    private suspend fun MarkdownViewModel.awaitEditOutcome(): FileLoadState = fileLoadState.first {
        it is FileLoadState.EditAllowed ||
            it is FileLoadState.EditRefusedOtherFile ||
            it is FileLoadState.EditRefusedReadOnly
    }

    @Test
    fun theSameFileIsAdoptedAndKeepsTheEdits() = runTest {
        val text = FileText("# Notes\n\nline\n", isUtf8 = true)
        val repo = FakeRepo(mapOf(opened to text, picked to text))
        val vm = openReadOnly(repo)
        vm.updateContent(vm.currentDocument.value!!.id, "# Notes\n\nline edited\n")

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditAllowed, vm.awaitEditOutcome())
        val doc = vm.currentDocument.value!!
        assertEquals("the document now saves to the picked file", picked, doc.uri)
        assertFalse("no longer read-only", vm.transient.value)
        assertEquals("edits kept", "# Notes\n\nline edited\n", doc.content)
        assertTrue("edits still unsaved", doc.dirty)
        assertTrue("nothing written by allowing", repo.capturedSaves.isEmpty())
    }

    @Test
    fun recentsListThePickedFileInsteadOfTheOpenedOne() = runTest {
        val text = FileText("same\n", isUtf8 = true)
        val repo = FakeRepo(mapOf(opened to text, picked to text))
        val vm = openReadOnly(repo)
        storage.recentFiles.first { list -> list.any { it.uri == opened } }

        vm.allowEditing(picked)
        vm.awaitEditOutcome()

        val uris = storage.recentFiles.first { list -> list.none { it.uri == opened } }.map { it.uri }
        assertEquals(listOf(picked), uris)
    }

    @Test
    fun theNextSaveWritesTheEditsToThePickedFile() = runTest {
        val text = FileText("a\r\nb\r\n", isUtf8 = true)
        val repo = FakeRepo(mapOf(opened to text, picked to text))
        val vm = openReadOnly(repo)
        vm.updateContent(vm.currentDocument.value!!.id, "a\nb changed\n")
        vm.allowEditing(picked)
        vm.awaitEditOutcome()
        vm.resetSaveState()

        vm.saveFile()
        vm.fileLoadState.first { it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError }

        assertEquals("CRLF kept (Safeguard 2)", "a\r\nb changed\r\n", repo.capturedSaves[picked])
        assertFalse("the read-only URI is never written", repo.capturedSaves.containsKey(opened))
    }

    @Test
    fun anotherFileIsRefusedAndNothingChanges() = runTest {
        val repo = FakeRepo(
            mapOf(opened to FileText("mine\n", isUtf8 = true), picked to FileText("someone else\n", isUtf8 = true)),
        )
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertEquals("still the opened file", opened, vm.currentDocument.value!!.uri)
        assertTrue("still read-only", vm.transient.value)
        assertEquals("the grant just taken is given back", listOf(picked), repo.released)
    }

    @Test
    fun aRefusalKeepsAGrantThatWasHeldBefore() = runTest {
        val repo = FakeRepo(
            mapOf(opened to FileText("mine\n", isUtf8 = true), picked to FileText("other\n", isUtf8 = true)),
        )
        repo.writableUris += picked // already opened with "Open file" earlier
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertTrue("not ours to release", repo.released.isEmpty())
    }

    @Test
    fun aRefusalKeepsAReadGrantThatWasHeldBefore() = runTest {
        val readable = mutableSetOf(picked) // in Recents with a read grant only
        val repo = object : FileRepository by FakeRepo(
            mapOf(opened to FileText("mine\n", isUtf8 = true), picked to FileText("other\n", isUtf8 = true)),
        ) {
            val released = mutableListOf<Uri>()
            override fun hasPersistedPermission(uri: Uri): Boolean = uri in readable
            override suspend fun releasePersistableUriPermission(uri: Uri) {
                released += uri
            }
        }
        val vm = vmWith(repo)
        vm.loadFile(opened)
        vm.fileLoadState.first { it is FileLoadState.Success }

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertTrue("its read grant is not ours to release", repo.released.isEmpty())
    }

    @Test
    fun theOpenedFileChangedSinceOpeningIsRefused() = runTest {
        // The picked file equals the opened file as it is NOW, but both differ from what was read.
        val files = mutableMapOf(opened to FileText("old\n", isUtf8 = true), picked to FileText("new\n", isUtf8 = true))
        val repo = FakeRepo(files)
        val vm = openReadOnly(repo)
        files[opened] = FileText("new\n", isUtf8 = true)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertEquals(opened, vm.currentDocument.value!!.uri)
    }

    @Test
    fun aMixedLineEndingThatNormalisesTheSameIsRefused() = runTest {
        // Same text, same detected ending, but one line differs CR for CR: not the same bytes.
        val repo = FakeRepo(
            mapOf(
                opened to FileText("a\r\nb\r\nc\n", isUtf8 = true),
                picked to FileText("a\r\nb\nc\r\n", isUtf8 = true),
            ),
        )
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
    }

    @Test
    fun whenTheOpenedFileCanNoLongerBeReadTheLineEndingStillDecides() = runTest {
        // The one-time "Open with" grant can be gone by now; then only the opened text and its line
        // ending are left to compare against.
        val files = mutableMapOf(
            opened to FileText("x\r\ny\r\n", isUtf8 = true),
            picked to FileText("x\ny\n", isUtf8 = true),
        )
        val vm = openReadOnly(FakeRepo(files))
        files.remove(opened)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
    }

    @Test
    fun whenTheOpenedFileCanNoLongerBeReadTheSameTextIsAdopted() = runTest {
        val files = mutableMapOf(
            opened to FileText("x\r\ny\r\n", isUtf8 = true),
            picked to FileText("x\r\ny\r\n", isUtf8 = true),
        )
        val vm = openReadOnly(FakeRepo(files))
        files.remove(opened)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditAllowed, vm.awaitEditOutcome())
        assertEquals(picked, vm.currentDocument.value!!.uri)
    }

    @Test
    fun aCancelledPickChangesNothing() = runTest {
        val text = FileText("same\n", isUtf8 = true)
        val repo = FakeRepo(mapOf(opened to text, picked to text))
        val vm = openReadOnly(repo)
        vm.updateContent(vm.currentDocument.value!!.id, "edited\n")

        vm.allowEditing(null)
        // Prove the null was handled by sending a real pick after it: its outcome is the barrier,
        // and the document must still be the opened one with the edits until that pick lands.
        assertEquals(opened, vm.currentDocument.value!!.uri)
        assertEquals("edited\n", vm.currentDocument.value!!.content)
        assertTrue(vm.transient.value)
        vm.allowEditing(picked)
        assertEquals(FileLoadState.EditAllowed, vm.awaitEditOutcome())
        assertEquals("one take only: the cancel took nothing", setOf(picked), repo.writableUris)
    }

    @Test
    fun theSameTextWithAnotherLineEndingIsRefused() = runTest {
        // A save would rewrite every line ending of the picked file, so it is not "the same file".
        val repo = FakeRepo(
            mapOf(opened to FileText("x\r\ny\r\n", isUtf8 = true), picked to FileText("x\ny\n", isUtf8 = true)),
        )
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertEquals(opened, vm.currentDocument.value!!.uri)
    }

    @Test
    fun aPickThatIsNotUtf8IsRefused() = runTest {
        val repo = FakeRepo(
            mapOf(opened to FileText("same\n", isUtf8 = true), picked to FileText("same\n", isUtf8 = false)),
        )
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedOtherFile, vm.awaitEditOutcome())
        assertEquals(opened, vm.currentDocument.value!!.uri)
    }

    @Test
    fun aPickWithoutLastingWriteAccessIsRefused() = runTest {
        val text = FileText("same\n", isUtf8 = true)
        val repo = FakeRepo(mapOf(opened to text, picked to text), takePermSucceeds = false)
        val vm = openReadOnly(repo)

        vm.allowEditing(picked)

        assertEquals(FileLoadState.EditRefusedReadOnly, vm.awaitEditOutcome())
        assertEquals(opened, vm.currentDocument.value!!.uri)
        assertTrue(vm.transient.value)
    }
}
