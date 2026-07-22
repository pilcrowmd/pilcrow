// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for MarkdownViewModel line-ending round-trip fidelity (Safeguard 2).
 *
 * Verifies that files with LF, CRLF, mixed, empty, no-trailing-newline, and blank-line
 * content round-trip correctly through loadFile → saveFile. Tests both the detectLineEnding
 * and applyLineEnding private functions indirectly via the public API.
 *
 * Uses a real StorageManager (Robolectric DataStore) + fake FileRepository (captures saved bytes)
 * + runTest to pump coroutines until idle.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLineEndingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private lateinit var capturedSaves: MutableMap<Uri, String>

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Test isolation: fresh DataStore per test (temp file)
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("lineending_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)

        // Capture saves
        capturedSaves = mutableMapOf()
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    // Helper: create a ViewModel with specified read content
    private fun createViewModelWithContent(content: String): MarkdownViewModel {
        val fakeRepository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(content)
            override suspend fun saveFile(uri: Uri, content: String) = kotlin.run {
                capturedSaves[uri] = content
                Result.success(Unit)
            }

            override suspend fun recoverPendingSaves() = Result.success(0)
            override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
            override suspend fun displayName(uri: Uri): String = "test.md"
            override fun hasPersistedPermission(uri: Uri): Boolean = true
            override fun hasPersistedWritePermission(uri: Uri): Boolean = true
            override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
            override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
            override suspend fun discardSlot(key: String) = Result.success(Unit)
        }

        val parseHeadingsUseCase = ParseMarkdownHeadingsUseCase()
        val searchUseCase = SearchMarkdownUseCase(parseHeadingsUseCase)
        val fakeAppInfo = object : AppInfo {
            override val versionName: String = "test"
        }

        return MarkdownViewModel(
            repository = fakeRepository,
            storage = storage,
            parseHeadingsUseCase = parseHeadingsUseCase,
            searchUseCase = searchUseCase,
            pdfExporter = mockk(relaxed = true),
            appInfo = fakeAppInfo,
        )
    }

    // Helper: create a ViewModel whose repository always FAILS to save (to exercise the SaveError path).
    private fun createViewModelWithFailingSave(content: String): MarkdownViewModel {
        val fakeRepository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(content)
            override suspend fun saveFile(uri: Uri, content: String) =
                Result.failure<Unit>(java.io.IOException("disk full (test)"))

            override suspend fun recoverPendingSaves() = Result.success(0)
            override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
            override suspend fun displayName(uri: Uri): String = "test.md"
            override fun hasPersistedPermission(uri: Uri): Boolean = true
            override fun hasPersistedWritePermission(uri: Uri): Boolean = true
            override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
            override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
            override suspend fun discardSlot(key: String) = Result.success(Unit)
        }

        val parseHeadingsUseCase = ParseMarkdownHeadingsUseCase()
        val searchUseCase = SearchMarkdownUseCase(parseHeadingsUseCase)
        val fakeAppInfo = object : AppInfo {
            override val versionName: String = "test"
        }

        return MarkdownViewModel(
            repository = fakeRepository,
            storage = storage,
            parseHeadingsUseCase = parseHeadingsUseCase,
            searchUseCase = searchUseCase,
            pdfExporter = mockk(relaxed = true),
            appInfo = fakeAppInfo,
        )
    }

    @Test
    fun testLfOnlyRoundTrip() = runTest {
        val content = "Line\nLine\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/lf_only.md")

        vm.loadFile(testUri)

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals("LF content should round-trip byte-identical", content, saved)
    }

    @Test
    fun testCrlfOnlyRoundTrip() = runTest {
        val content = "Line\r\nLine\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/crlf_only.md")

        vm.loadFile(testUri)

        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals("CRLF content should round-trip byte-identical", content, saved)
    }

    @Test
    fun testMixedCrlfDominant() = runTest {
        val content = "L1\r\nL2\nL3\r\nL4\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/mixed_crlf_dominant.md")

        vm.loadFile(testUri)

        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        val normalized = "L1\r\nL2\r\nL3\r\nL4\r\n"
        assertEquals(
            "Mixed CRLF-dominant should normalize to all-CRLF",
            normalized,
            saved,
        )
    }

    @Test
    fun testMixedLfDominant() = runTest {
        val content = "L1\nL2\r\nL3\nL4\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/mixed_lf_dominant.md")

        vm.loadFile(testUri)

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals(content.replace("\r\n", "\n"), saved)
    }

    @Test
    fun testEmptyString() = runTest {
        val content = ""
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/empty.md")

        vm.loadFile(testUri)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals("Empty content should round-trip as empty", "", saved)
    }

    @Test
    fun testNoTrailingNewline() = runTest {
        val content = "L1\nL2\nL3"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/no_trailing_newline.md")

        vm.loadFile(testUri)

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals("No trailing newline should be preserved", content, saved)
    }

    @Test
    fun testBlankLinesOnly() = runTest {
        val content = "\n\n\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/blank_lines.md")

        vm.loadFile(testUri)

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()

        val saved = capturedSaves[testUri]
        assertEquals("Blank-lines-only content should round-trip byte-identical", content, saved)
    }

    @Test
    fun testCrlfPreservedThroughSaveAndClose() = runTest {
        // Safeguard 2: Save & Close must restore the original CRLF line ending, exactly like
        // the plain Save path — it must not silently convert a CRLF file to LF on disk.
        val content = "L1\r\nL2\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/crlf_save_and_close.md")

        vm.loadFile(testUri)
        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveAndClose()

        assertEquals(
            "Save & Close must preserve CRLF on disk (Safeguard 2), not convert to LF",
            content,
            capturedSaves[testUri],
        )
    }

    @Test
    fun testCrlfPreservedThroughSaveAndOpenPending() = runTest {
        // Safeguard 2: the Save-then-open-pending path (intent arriving while the current file is
        // dirty) must also restore CRLF, not write the editor's LF form.
        val content = "L1\r\nL2\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/crlf_save_and_open_pending.md")

        vm.loadFile(testUri)
        assertEquals("CRLF", vm.lineEnding.value)

        // Dirty the doc so an incoming intent defers behind the Save/Discard prompt (sets pendingOpenUri).
        vm.updateContent("L1\nL2\nL3\n") // editor/model work in LF
        vm.openFromIntent(Uri.parse("content://test/incoming.md"))

        vm.saveAndOpenPending()

        assertEquals(
            "Save & open-pending must preserve CRLF on disk (Safeguard 2), not write LF",
            "L1\r\nL2\r\nL3\r\n",
            capturedSaves[testUri],
        )
    }

    @Test
    fun testResetSaveStateConsumesSaveSuccess() = runTest {
        // A save leaves fileLoadState at SaveSuccess; resetSaveState must consume it to Idle
        // so the "Saved" toast can't replay on a later recomposition / screen remount.
        val vm = createViewModelWithContent("hello\n")
        val testUri = Uri.parse("content://test/reset_save.md")
        vm.loadFile(testUri)
        vm.saveFile()
        assertEquals(
            "Save should leave fileLoadState at SaveSuccess",
            FileLoadState.SaveSuccess,
            vm.fileLoadState.value,
        )
        vm.resetSaveState()
        assertEquals(
            "resetSaveState consumes SaveSuccess → Idle",
            FileLoadState.Idle,
            vm.fileLoadState.value,
        )
    }

    @Test
    fun testResetSaveStateLeavesNonTerminalStateUntouched() = runTest {
        // Guard: resetSaveState acts only on the terminal save outcomes (SaveSuccess/SaveError).
        // Drive to SaveSuccess, consume to Idle, then a second reset on the now-Idle state must
        // leave it unchanged.
        val vm = createViewModelWithContent("hello\n")
        val testUri = Uri.parse("content://test/reset_guard.md")
        vm.loadFile(testUri)
        vm.saveFile()
        vm.resetSaveState()
        assertEquals(FileLoadState.Idle, vm.fileLoadState.value)
        vm.resetSaveState()
        assertEquals(
            "resetSaveState leaves a non-terminal state unchanged (the guard branch)",
            FileLoadState.Idle,
            vm.fileLoadState.value,
        )
    }

    @Test
    fun testResetSaveStateConsumesSaveError() = runTest {
        // A failed save must surface as SaveError (so the error SnackBar shows — Safeguard 1: a
        // failed save is never silent) and then be consumed to Idle so the error can't replay on a
        // later recomposition / screen remount.
        val vm = createViewModelWithFailingSave("hello\n")
        val testUri = Uri.parse("content://test/reset_save_error.md")
        vm.loadFile(testUri)
        vm.saveFile()
        assertTrue(
            "A failed save should leave fileLoadState at SaveError",
            vm.fileLoadState.value is FileLoadState.SaveError,
        )
        vm.resetSaveState()
        assertEquals(
            "resetSaveState consumes SaveError → Idle",
            FileLoadState.Idle,
            vm.fileLoadState.value,
        )
    }
}
