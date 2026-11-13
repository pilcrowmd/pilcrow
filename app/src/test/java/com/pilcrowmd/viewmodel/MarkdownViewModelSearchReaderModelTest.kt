// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
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
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8-A94 (large-documents spec, Rule 1): search walks the reader model's blocks and never parses the
 * document itself. Two trees at once (the reader's and search's) ran 5 MB files out of memory (S8-A93).
 *
 * The proof that search reads the MODEL and not the text: the reader's parse here ignores the text it
 * is given and always returns the tree of [READER_TREE_TEXT]. The document says "Nothing here", so a
 * search that parsed the document would find no "needle"; one that walks the model finds it.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelSearchReaderModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("search_reader_model_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val content: String) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(content, isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private val uri = Uri.parse("content://test/doc.md")
    private val parses = AtomicInteger()

    /** The reader's parse: counted, held while [gate] is shut, and always the tree of [READER_TREE_TEXT]. */
    private fun vm(
        gate: CountDownLatch = CountDownLatch(0),
        search: SearchMarkdownUseCase = SearchMarkdownUseCase(ReaderWords.ENGLISH),
    ): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(DOCUMENT_TEXT),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = search,
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            prepareReaderModel = PrepareReaderModelUseCase(
                parse = {
                    parses.incrementAndGet()
                    gate.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    parseHeadings.parseTree(READER_TREE_TEXT)
                },
                headings = parseHeadings,
            ),
        )
    }

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + WAIT_SECONDS * 1_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    @Test
    fun `search finds what the reader model holds, not what a parse of the text would`() {
        val vm = vm()
        vm.loadFile(uri)
        awaitValue(true, "reader model ready") { vm.readerModel.value != null }

        vm.setSearchVisible(true)
        vm.updateSearchQuery("needle")

        awaitValue(1, "the needle in the reader's blocks") { vm.searchMatches.value.size }
        assertEquals("one parse: the reader's", 1, parses.get())
    }

    @Test
    fun `a search typed while the reader model is being prepared waits for it instead of parsing`() {
        val gate = CountDownLatch(1)
        val vm = vm(gate)
        vm.loadFile(uri)
        awaitValue(1, "the reader's parse started") { parses.get() }

        vm.setSearchVisible(true)
        vm.updateSearchQuery("needle")
        awaitValue("needle", "query taken") { vm.searchQuery.value }
        assertEquals("nothing found before the model exists", 0, vm.searchMatches.value.size)

        gate.countDown()
        awaitValue(1, "found once the model is ready") { vm.searchMatches.value.size }
        assertEquals("still one parse: the reader's", 1, parses.get())
    }

    @Test
    fun `in the editor after an edit there is no reader model, so search prepares one for itself`() {
        val vm = vm()
        vm.loadFile(uri)
        awaitValue(true, "reader model ready") { vm.readerModel.value != null }
        vm.setMode(ViewMode.EDITOR)
        awaitValue(ViewMode.EDITOR, "editor open") { vm.mode.value }
        vm.updateContent(vm.currentDocument.value!!.id, "Edited.\n")
        awaitValue(true, "the reader model released while typing (M-154)") { vm.readerModel.value == null }
        val before = parses.get()

        vm.setSearchVisible(true)
        vm.updateSearchQuery("needle")

        awaitValue(1, "found in the model prepared for the search") { vm.searchMatches.value.size }
        assertEquals("one parse, for this search", before + 1, parses.get())
    }

    @Test
    fun `a search that runs out of memory finds nothing and says so`() {
        // The scan asks isFormula about every inline container; throwing there is running out of memory
        // in the middle of the scan.
        val vm = vm(search = SearchMarkdownUseCase(ReaderWords.ENGLISH, isFormula = { throw OutOfMemoryError("test") }))
        val said = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.searchOutOfMemory.collect { said.incrementAndGet() } }
        try {
            vm.loadFile(uri)
            awaitValue(true, "reader model ready") { vm.readerModel.value != null }

            vm.setSearchVisible(true)
            vm.updateSearchQuery("needle")

            awaitValue(1, "the screen is told") { said.get() }
            assertEquals("nothing found", 0, vm.searchMatches.value.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a good search after one that ran out of memory and was cancelled shows its matches and no out-of-memory`() {
        // S8-A95 (review r3/r4): the first scan is typed past while it runs, then runs out of memory. Its
        // coroutine is cancelled by then, so nothing after the scan in it runs; the next scan must not
        // inherit its out-of-memory state.
        val armed = AtomicBoolean(true)
        val inFirstScan = CountDownLatch(1)
        val release = CountDownLatch(1)
        val search = SearchMarkdownUseCase(
            ReaderWords.ENGLISH,
            isFormula = {
                if (armed.compareAndSet(true, false)) {
                    inFirstScan.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    throw OutOfMemoryError("test")
                }
                false
            },
        )
        val vm = vm(search = search)
        val said = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.searchOutOfMemory.collect { said.incrementAndGet() } }
        try {
            vm.loadFile(uri)
            awaitValue(true, "reader model ready") { vm.readerModel.value != null }
            vm.setSearchVisible(true)

            vm.updateSearchQuery("needl")
            awaitValue(0L, "the first scan started") { inFirstScan.count }
            vm.updateSearchQuery("needle") // cancels the first search while its scan is still running
            release.countDown() // ...which now runs out of memory

            awaitValue(1, "the good search shows its match") { vm.searchMatches.value.size }
            awaitValue("needle", "the good search's query") { vm.searchQuery.value }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("no out-of-memory said for the good search", 0, said.get())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    private companion object {
        const val DOCUMENT_TEXT = "Nothing here.\n"
        const val READER_TREE_TEXT = "A needle in the reader's tree.\n"
        const val WAIT_SECONDS = 5L
    }
}
