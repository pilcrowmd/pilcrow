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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A tap on a task box in the reader ticks that box in the document, and only that one. */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelTaskToggleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private val uri = Uri.parse("content://test/todo.md")

    private val text = "# Todo\n\nIntro.\n\n- [ ] one\n- [x] two\n\nMiddle.\n\n- [ ] three\n"

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("task_toggle_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class Repo(private val content: String) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(content, isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = "todo.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun openedViewModel(): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = Repo(text),
            storage = storage,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vm.loadFile(uri)
        awaitValue(true, "reader model prepared") {
            vm.currentDocument.value?.uri == uri && vm.readerModel.value?.content === vm.currentDocument.value?.content
        }
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

    @Test
    fun `a box in the first list ticks and the document is dirty`() {
        val vm = openedViewModel()
        // Blocks: heading 0, paragraph 1, list 2, paragraph 3, list 4.
        vm.toggleTask(block = 2, ordinal = 0, count = 2)
        val expected = text.replace("- [ ] one", "- [x] one")
        awaitValue(expected, "the first box ticks") { vm.currentDocument.value?.content }
        assertEquals(true, vm.currentDocument.value?.dirty)
    }

    @Test
    fun `a ticked box unticks, and a box in a later block is found by its block`() {
        val vm = openedViewModel()
        vm.toggleTask(block = 2, ordinal = 1, count = 2)
        awaitValue(text.replace("- [x] two", "- [ ] two"), "the second box unticks") {
            vm.currentDocument.value?.content
        }
        val now = vm.currentDocument.value!!.content
        awaitValue(true, "the reader model follows the edit") { vm.readerModel.value?.content === now }
        vm.toggleTask(block = 4, ordinal = 0, count = 1)
        awaitValue(now.replace("- [ ] three", "- [x] three"), "the third box ticks") {
            vm.currentDocument.value?.content
        }
    }

    @Test
    fun `a count the source disagrees with leaves the text alone`() {
        val vm = openedViewModel()
        vm.toggleTask(block = 2, ordinal = 0, count = 3)
        repeat(SETTLE_POLLS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertEquals(text, vm.currentDocument.value?.content)
        assertFalse(vm.currentDocument.value!!.dirty)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_POLLS = 30
    }
}
