// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * **M-150: the ViewModel refuses a close while a save is writing.** Before, only the View stood in
 * the way (the toolbar X and Back are disabled on `writeInFlight`), and a close that got through
 * left the save to report "Saved" on the welcome screen for a document no longer open.
 *
 * Each test is built so the write check is the ONLY thing that can refuse the close. `closeFile`
 * also refuses a document with unsaved edits (IF_CLEAN), so the first test saves a CLEAN document,
 * and the second uses `discardAndClose`, which skips that check by design.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelCloseDuringWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val aUri = Uri.parse("content://t/a.md")

    private class ParkedWriteRepo : FileRepository {
        val writeGate = CompletableDeferred<Unit>()
        override suspend fun readFile(uri: Uri) = Result.success(FileText("alpha\n", isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            writeGate.await()
            return Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment!!
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("close_during_write_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(repo: FileRepository): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = storage,
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

    /** Open A, start an in-place save and leave it parked mid-write. */
    private fun MarkdownViewModel.openAndStartParkedSave() {
        loadFile(aUri)
        awaitTrue("A never opened") {
            currentDocument.value?.uri == aUri && fileLoadState.value == FileLoadState.Success
        }
        saveFile()
        awaitTrue("the save never started writing") { writeInFlight.value }
    }

    private fun MarkdownViewModel.releaseAndAssertStillOpenAndSaved(repo: ParkedWriteRepo, why: String) {
        repo.writeGate.complete(Unit)
        awaitTrue("the save never finished") { !writeInFlight.value }
        assertEquals("$why closed the document during the write (M-150)", aUri, currentDocument.value?.uri)
        // "Saved" is kept (C4): the write succeeded, and the document it describes is still open.
        assertEquals(FileLoadState.SaveSuccess, fileLoadState.value)
    }

    @Test
    fun `closeFile during a save is refused, and the save still reports Saved`() {
        val repo = ParkedWriteRepo()
        val vm = vm(repo)
        vm.openAndStartParkedSave()
        assertTrue("fixture: A must be clean, or IF_CLEAN refuses first", vm.currentDocument.value?.dirty == false)

        vm.closeFile()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("closeFile closed the document mid-write", aUri, vm.currentDocument.value?.uri)

        vm.releaseAndAssertStillOpenAndSaved(repo, "closeFile")
    }

    @Test
    fun `discardAndClose during a save is refused too`() {
        val repo = ParkedWriteRepo()
        val vm = vm(repo)
        vm.openAndStartParkedSave()

        vm.discardAndClose()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("discardAndClose closed the document mid-write", aUri, vm.currentDocument.value?.uri)

        vm.releaseAndAssertStillOpenAndSaved(repo, "discardAndClose")
    }

    @Test
    fun `a close after the save has finished still closes`() {
        val repo = ParkedWriteRepo()
        val vm = vm(repo)
        vm.openAndStartParkedSave()
        repo.writeGate.complete(Unit)
        awaitTrue("the save never finished") { !vm.writeInFlight.value }

        vm.closeFile()
        awaitTrue("a close after the write did not close") { vm.currentDocument.value == null }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
