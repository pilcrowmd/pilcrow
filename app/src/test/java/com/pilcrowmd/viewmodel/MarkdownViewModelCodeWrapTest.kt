// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.ReaderModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * S8-A66: a long code line is in pieces whatever the code-wrap setting, so the reader model does not
 * depend on it: wrap off shows the pieces, search numbers its matches by them, and a toggle neither
 * prepares a new model nor moves an open search. Real DataStore and dispatchers, so every check awaits
 * the state the load or the toggle publishes ([awaitValue]).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelCodeWrapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("codewrap_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val readContent: String) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(readContent, isUtf8 = true))
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

    private fun vmWith(content: String): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(content),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
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

    private val uri = Uri.parse("content://test/long-line.md")

    // One code line of 24,006 chars, a needle across the cut at 15,750: on a later piece, so an
    // uncut tree (the whole block one item) would number it differently.
    private val line = "x".repeat(15_747) + "NEEDLE" + "x".repeat(8_253)
    private val content = "# Top\n\n```\n$line\n```\n\nafter\n"

    private fun MarkdownViewModel.markdown() = readerModel.value as? ReaderModel.Markdown

    private fun MarkdownViewModel.pieces() = markdown()?.blocks.orEmpty().count { it is CodeBlockPiece }

    @Test
    fun `with wrap off, search finds a needle across a cut and lands on the piece it starts in`() {
        val vm = vmWith(content)
        vm.loadFile(uri)
        awaitValue(true, "wrap off: the line is in pieces") { vm.pieces() >= 3 }
        assertEquals(false, vm.wrapCodeLines.value)

        vm.updateSearchQuery("needle")
        awaitValue(1, "one match") { vm.searchMatches.value.size }

        val match = vm.searchMatches.value.single()
        val piece = vm.markdown()!!.blocks[match.adapterPosition] as CodeBlockPiece
        assertTrue("the match is on the piece it starts in", piece.literal.endsWith("NEE"))
        assertEquals(content.indexOf("NEEDLE"), match.startIndex)
    }

    @Test
    fun `a code wrap toggle keeps the model and an open search's positions`() {
        val vm = vmWith(content)
        vm.loadFile(uri)
        awaitValue(true, "the line is in pieces") { vm.pieces() >= 3 }
        val model = vm.markdown()
        vm.updateSearchQuery("needle")
        awaitValue(1, "one match") { vm.searchMatches.value.size }
        val before = vm.searchMatches.value.single()

        vm.setWrapCodeLines(true)
        awaitValue(true, "wrap on stored") { vm.wrapCodeLines.value }
        // Anything the toggle started has had its turn: a re-prepare is one parse of 24 KB.
        settle()

        assertTrue("the same model: nothing was cut again", vm.markdown() === model)
        assertEquals(before, vm.searchMatches.value.single())
    }

    private fun settle() {
        val until = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 500L
    }
}
