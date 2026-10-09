// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.model.RenderMode
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * Safeguard 2 guard for M-365: a `.txt` whose run has no blank line is shown as several TextViews
 * (see `PlainTextBlocks`), but the split is DISPLAY ONLY. Opening such a file and saving it without
 * an edit must write back exactly the bytes that were read.
 *
 * This is expected to pass on the code before the split existed, too: nothing on the save path reads
 * the chunks. It is here so that a future change which routes saving through the render model fails.
 *
 * Barriers follow [MarkdownViewModelLineEndingTest]: wait on the state each call publishes
 * (document load, terminal save outcome), never on a value whose initial state is already the
 * expected one. The render-mode assertion is not vacuous either: its initial value is MARKDOWN.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelPlainSplitRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private val capturedSaves = ConcurrentHashMap<Uri, String>()

    // Cancels each viewModelScope BEFORE resetMain() releases the global (see the line-ending suite).
    private val vmStore = ViewModelStore()

    @Before
    fun setup() {
        // viewModelScope runs on Dispatchers.Main; a real barrier suspends the test thread, so the
        // paused Robolectric main looper must be replaced by a test dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("plainsplit_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear()
        storageScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModelReading(content: String): MarkdownViewModel {
        val repository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(FileText(content, isUtf8 = true))

            // A genuine hop to another thread, so the write is NOT finished by the time saveFile()
            // returns: only awaitSaveSettled() makes the assertion below see it.
            override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
                Thread.sleep(SAVE_DELAY_MS)
                capturedSaves[uri] = content
                Result.success(Unit)
            }

            override suspend fun recoverPendingSaves() = Result.success(0)
            override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
            override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "notes.txt"
            override fun hasPersistedPermission(uri: Uri): Boolean = true
            override fun hasPersistedWritePermission(uri: Uri): Boolean = true
            override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
            override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
            override suspend fun discardSlot(key: String) = Result.success(Unit)
        }
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        val viewModel = MarkdownViewModel(
            repository = repository,
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vmStore.put("vm", viewModel)
        return viewModel
    }

    private suspend fun MarkdownViewModel.awaitLoaded() {
        fileLoadState.first { it is FileLoadState.Success }
    }

    private suspend fun MarkdownViewModel.awaitSaveSettled() {
        fileLoadState.first { it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError }
    }

    private fun assertRoundTripsUntouched(content: String, name: String) = runTest {
        val vm = viewModelReading(content)
        val uri = Uri.parse("content://test/$name")

        vm.loadFile(uri)
        vm.awaitLoaded()
        assertEquals("a .txt opens in plain mode, where the run is split", RenderMode.PLAIN, vm.renderMode.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        assertEquals("saved bytes equal the bytes that were read", content, capturedSaves[uri])
    }

    @Test
    fun blankFreeLfTextWithTrailingNewlineSavesByteIdentical() =
        assertRoundTripsUntouched((1..1000).joinToString("") { "line $it\n" }, "lf.txt")

    @Test
    fun blankFreeCrlfTextSavesByteIdentical() =
        assertRoundTripsUntouched((1..1000).joinToString("") { "line $it\r\n" }, "crlf.txt")

    private companion object {
        const val SAVE_DELAY_MS = 200L
    }
}
