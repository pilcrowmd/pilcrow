// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * GitHub #19: the "*" after the file name and the toolbar's Save follow the document's one dirty
 * state. Dirty on the first edit, clean again when the text is back to the opened text (today's rule:
 * dirty = text differs from what is on disk), clean after a save and after a reload. Save stays on for
 * a new document, which has no file yet.
 *
 * Barriers: every step waits on the state its call publishes (`dirty` flipping, the save's terminal
 * outcome, the reload's new document id), never on a value the document already held. The fake
 * repository answers inline, so without the waits each assertion would read before the launch ran.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelUnsavedStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val aUri = Uri.parse("content://t/a.md")
    private var aDisk = "alpha\n"
    private val saved = mutableListOf<String>()

    private lateinit var storageScope: CoroutineScope

    @After
    fun tearDown() {
        val storageJob = storageScope.coroutineContext.job
        storageJob.cancel()
        awaitTrue("the storage scope never settled") { storageJob.isCompleted }
    }

    private val repo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            aUri -> Result.success(FileText(aDisk, isUtf8 = true))
            else -> Result.failure(IOException("unreadable: $uri"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            saved += content
            aDisk = content
            return Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment!!
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("unsaved_state.preferences_pb")
        }
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = LocalStorageManager(context, ds),
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(message, condition())
    }

    private val MarkdownViewModel.doc get() = currentDocument.value!!

    private fun MarkdownViewModel.openA() {
        loadFile(aUri)
        awaitTrue("A never opened") {
            currentDocument.value?.uri == aUri && fileLoadState.value == FileLoadState.Success
        }
    }

    private fun MarkdownViewModel.type(text: String, dirty: Boolean) {
        updateContent(doc.id, text)
        awaitTrue("the edit never made the document ${if (dirty) "dirty" else "clean"}") {
            doc.content == text && doc.dirty == dirty
        }
    }

    @Test
    fun anOpenedFileHasNothingToSaveUntilTheFirstEdit() {
        val vm = viewModel()
        vm.openA()
        assertFalse("an opened file starts clean", vm.doc.dirty)
        assertFalse("Save is greyed out on an opened file", vm.doc.hasSomethingToSave)

        vm.type("alpha, edited\n", dirty = true)
        assertTrue("the first edit turns Save on", vm.doc.hasSomethingToSave)
    }

    @Test
    fun undoingBackToTheOpenedTextIsCleanAgain() {
        val vm = viewModel()
        vm.openA()
        vm.type("alpha, edited\n", dirty = true)

        vm.type("alpha\n", dirty = false)
        assertFalse("back at the opened text there is nothing to save", vm.doc.hasSomethingToSave)
    }

    @Test
    fun aSaveLeavesNothingToSave() {
        val vm = viewModel()
        vm.openA()
        vm.type("alpha, edited\n", dirty = true)

        vm.saveFile()
        awaitTrue("the save never finished") {
            vm.fileLoadState.value is FileLoadState.SaveSuccess && !vm.writeInFlight.value
        }
        assertEquals(listOf("alpha, edited\n"), saved)
        assertFalse("saved, so clean", vm.doc.dirty)
        assertFalse("and Save is greyed out", vm.doc.hasSomethingToSave)
    }

    @Test
    fun aReloadLeavesNothingToSave() {
        val vm = viewModel()
        vm.openA()
        val opened = vm.doc.id
        vm.type("alpha, edited\n", dirty = true)

        vm.reloadChangedFile(discardEdits = true)
        awaitTrue("the reload never published") { vm.currentDocument.value?.id != opened }
        assertNotEquals(opened, vm.doc.id)
        assertEquals("alpha\n", vm.doc.content)
        assertFalse("reloaded, so clean", vm.doc.dirty)
        assertFalse("and Save is greyed out", vm.doc.hasSomethingToSave)
    }

    /** A new document has no file yet; Save creates it, so Save stays on before any edit. */
    @Test
    fun aNewDocumentCanBeSavedBeforeAnyEdit() {
        val vm = viewModel()
        vm.newDocument()
        awaitTrue("the new document never opened") { vm.currentDocument.value?.isUnsaved == true }
        assertFalse(vm.doc.dirty)
        assertTrue("Save creates the file", vm.doc.hasSomethingToSave)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
