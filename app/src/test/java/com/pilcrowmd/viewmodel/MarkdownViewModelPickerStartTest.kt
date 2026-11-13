// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-162a: the Open and Save pickers start at the newest recent file that still has a permission, so
 * [MarkdownViewModel.pickerStartUri] must follow it. A real DataStore-backed [LocalStorageManager]
 * and the `awaitValue` idiom of [MarkdownViewModelRenderModeTest]: every barrier waits for a value
 * that differs from the current one, so it only returns once the open, the Save-As or the clear has
 * actually reached recents.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelPickerStartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val vmStore = ViewModelStore()
    private val revoked = mutableSetOf<Uri>()
    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("picker_start_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear()
        storageScope.cancel()
    }

    private inner class FakeRepo : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText("# note\n", isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = uri !in revoked
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun newViewModel(): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = FakeRepo(),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vmStore.put("vm", vm)
        return vm
    }

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(message, expected, actual())
    }

    private val first = Uri.parse("content://test/Notes/first.md")
    private val second = Uri.parse("content://test/Journal/second.md")
    private val copy = Uri.parse("content://test/Archive/copy.md")

    @Test
    fun theStartFollowsTheNewestOpenedFile() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "opening a file makes it the picker start") { vm.pickerStartUri() }
        vm.loadFile(second)
        awaitValue(second, "a later open moves the start to that file") { vm.pickerStartUri() }
    }

    @Test
    fun saveAsMovesTheStartToTheNewFile() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "the source is open") { vm.pickerStartUri() }
        awaitValue(FileLoadState.Success, "the load finished") { vm.fileLoadState.value }
        vm.saveActiveDocumentAs(copy)
        awaitValue(copy, "Save-As makes the new file the picker start") { vm.pickerStartUri() }
    }

    @Test
    fun noRecentsMeansNoStart() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "the file is a recent") { vm.pickerStartUri() }
        vm.clearRecents()
        awaitValue(null, "with no recents the picker keeps the system default") { vm.pickerStartUri() }
    }

    @Test
    fun aNewestFileWithNoPermissionFallsBackToTheNextOlderRecent() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "the older file is a recent") { vm.pickerStartUri() }
        revoked += second
        vm.loadFile(second)
        // Wait on recents, not the start: the start is `first` before and after this open lands.
        awaitValue(second, "the file with no permission is the newest recent") {
            vm.recentFiles.value.firstOrNull()?.uri
        }
        assertEquals("the next older recent with a permission is used", first, vm.pickerStartUri())
    }

    @Test
    fun aPermissionLostWhileRunningMovesTheStartAtTheNextLaunch() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "the older file is a recent") { vm.pickerStartUri() }
        vm.loadFile(second)
        awaitValue(second, "the newer file is the start") { vm.pickerStartUri() }
        val recents = vm.recentFiles.value
        revoked += second
        assertEquals("recents did not change", recents, vm.recentFiles.value)
        assertEquals("the lost permission is seen at the next launch", first, vm.pickerStartUri())
    }

    @Test
    fun noRecentWithAPermissionMeansNoStart() {
        val vm = newViewModel()
        vm.loadFile(first)
        awaitValue(first, "the file is a recent") { vm.pickerStartUri() }
        revoked += first
        assertEquals("the picker keeps the system default, with no error", null, vm.pickerStartUri())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
