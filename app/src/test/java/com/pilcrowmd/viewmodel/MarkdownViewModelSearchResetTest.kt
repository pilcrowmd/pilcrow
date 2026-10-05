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
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
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
 * In-document search belongs to the document it was run against. When a DIFFERENT document becomes
 * current — a load, a new document — or none does (close), the search bar closes and its query and
 * matches are cleared, exactly as closing it by hand does. Before this, searching "Lorem" in one
 * file, closing it and opening another left the bar open showing "Lorem" and "2/2" over a file
 * that contains neither.
 *
 * Save-As is the counter-case: the same document gains a file, so the search is kept (and re-run,
 * which [MarkdownViewModelRenderModeTest] already covers).
 *
 * Fixture and [awaitValue] barrier mirror [MarkdownViewModelNewDocumentTest]. Every "cleared"
 * assertion waits on `searchVisible` turning FALSE from a TRUE the test set itself, so the barrier
 * is a change, never the flow's initial value.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelSearchResetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("search_reset_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    /** Content per URI, so document B genuinely lacks the query document A matches. */
    private class FakeRepo(private val contents: Map<Uri, String>) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(contents.getValue(uri), isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private val uriA = Uri.parse("content://test/lorem.md")
    private val uriB = Uri.parse("content://test/other.md")
    private val copyUri = Uri.parse("content://test/copy.md")

    private fun vm(): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(mapOf(uriA to "Lorem ipsum.\n\nLorem again.\n", uriB to "Nothing to see.\n")),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    /** See [MarkdownViewModelRenderModeTest.awaitValue] — same barrier, same caveats. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    private fun MarkdownViewModel.loadAndAwait(uri: Uri) {
        loadFile(uri)
        awaitValue(uri, "document loaded") { currentDocument.value?.uri }
    }

    /** Document A open, search bar open, "Lorem" found twice. */
    private fun vmSearchingA(): MarkdownViewModel {
        val vm = vm()
        vm.loadAndAwait(uriA)
        vm.setSearchVisible(true)
        awaitValue(true, "search bar open") { vm.searchVisible.value }
        vm.updateSearchQuery("Lorem")
        awaitValue(2, "both matches found in A") { vm.searchMatches.value.size }
        return vm
    }

    private fun MarkdownViewModel.assertSearchCleared(after: String) {
        awaitValue(false, "search bar closed after $after") { searchVisible.value }
        assertEquals("query cleared after $after", "", searchQuery.value)
        assertEquals("matches cleared after $after", 0, searchMatches.value.size)
        assertEquals("focus reset after $after", 0, currentMatchIndex.value)
    }

    @Test
    fun loadingAnotherDocumentClosesAndClearsSearch() {
        val vm = vmSearchingA()
        vm.nextMatch()
        awaitValue(1, "focus moved to the second match") { vm.currentMatchIndex.value }

        vm.loadAndAwait(uriB)
        vm.assertSearchCleared("loading B")
    }

    @Test
    fun closingTheDocumentClosesAndClearsSearch() {
        val vm = vmSearchingA()

        vm.closeFile()
        awaitValue(null, "document closed") { vm.currentDocument.value }
        vm.assertSearchCleared("close")
    }

    @Test
    fun newDocumentClosesAndClearsSearch() {
        val vm = vmSearchingA()

        vm.newDocument()
        awaitValue(null, "new document has no uri") { vm.currentDocument.value?.uri }
        vm.assertSearchCleared("new document")
    }

    /**
     * Save-As keeps the document — only its file changes — so the search stays. Barrier: the
     * adopted URI AND the terminal `SaveSuccess`, both published after the write; a clear keyed on
     * the URI change would have run by then (the looper is idled inside [awaitValue]).
     */
    @Test
    fun saveAsKeepsTheSearch() {
        val vm = vmSearchingA()

        vm.saveActiveDocumentAs(copyUri)
        awaitValue(copyUri, "copy adopted") { vm.currentDocument.value?.uri }
        awaitValue(FileLoadState.SaveSuccess, "save-as settled") { vm.fileLoadState.value }

        assertTrue("search bar still open after Save-As", vm.searchVisible.value)
        assertEquals("query kept after Save-As", "Lorem", vm.searchQuery.value)
        awaitValue(2, "matches kept (re-run) after Save-As") { vm.searchMatches.value.size }
    }
}
