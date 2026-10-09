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
import com.pilcrowmd.ui.components.PilcrowCodeEditor
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

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
@Category(MainDispatcherSuite::class)
class MarkdownViewModelLineEndingTest {

    // --- teardown quiescence ------------------------------------------------------
    // The ViewModels built by the factories below are per-test locals, so nothing ever
    // cancelled their `viewModelScope`. That was harmless until the load path gained a
    // real `withContext(Dispatchers.IO)` hop: a load coroutine can now still be suspended on
    // IO when the test body returns and then resume onto Main exactly as
    // `Dispatchers.resetMain()` releases the global, throwing
    // `IllegalStateException: Dispatchers.Main is used concurrently with setting it` - on a
    // RANDOM test, in TEARDOWN rather than an assertion. Measured pre-fix at 16/20 clean runs.
    //
    // Registering each ViewModel in a ViewModelStore lets `clear()` cancel those scopes
    // deterministically BEFORE the global is released. Public lifecycle API (2.8.7): no
    // reflection, no dependence on the internal viewModelScope job key.
    //
    // SEQUENCING, NOT SILENCING: no try/catch around resetMain, no swallowed exception, no
    // retry rule, no @Ignore, no timeout change - and not one await, barrier or assertion in
    // this suite is touched.
    private val vmStore = ViewModelStore()
    private var vmSeq = 0

    /** Registers [vm] so teardown can cancel its `viewModelScope` before `resetMain()`. */
    private fun track(vm: MarkdownViewModel): MarkdownViewModel {
        vmStore.put("vm-${vmSeq++}", vm)
        return vm
    }
    // ---------------------------------------------------------------------------------

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private lateinit var capturedSaves: MutableMap<Uri, String>

    @Before
    fun setup() {
        // `viewModelScope` dispatches to Dispatchers.Main — under Robolectric, the paused main
        // looper that the test thread itself owns. The moment a real barrier suspends the test
        // thread, nothing can pump that looper and the load can never complete. Substituting a test
        // dispatcher is what makes a real barrier possible at all.
        //
        // [MarkdownViewModelSaveAsTest] records the objection to this — `setMain` is a process-global
        // mutation that can race other classes sharing the JVM fork. It does not apply here, and the
        // reason is narrower than "we don't parallelise": Gradle's `maxParallelForks`/`forkEvery`
        // spawn SEPARATE JVMs, and a process-global cannot leak across a process boundary, so those
        // knobs are safe to turn on. The only configuration that would break this is CONCURRENCY
        // WITHIN ONE JVM — e.g. moving to JUnit 5 with `junit.jupiter.execution.parallel.enabled`,
        // where one class could call `resetMain()` while another is mid-flight. JUnit 4 does not do
        // that. `resetMain()` in @After restores the global between classes either way.
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        vmStore.clear() // cancels every viewModelScope BEFORE the global is released
        storageScope.cancel()
        Dispatchers.resetMain()
    }

    // Helper: create a ViewModel with specified read content
    private fun createViewModelWithContent(content: String): MarkdownViewModel {
        val fakeRepository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(FileText(content, isUtf8 = true))
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

        return track(
            MarkdownViewModel(
                repository = fakeRepository,
                storage = storage,
                parseHeadingsUseCase = parseHeadingsUseCase,
                searchUseCase = searchUseCase,
                pdfExporter = mockk(relaxed = true),
                appInfo = fakeAppInfo,
            ),
        )
    }

    // Helper: create a ViewModel whose repository always FAILS to save (to exercise the SaveError path).
    private fun createViewModelWithFailingSave(content: String): MarkdownViewModel {
        val fakeRepository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(FileText(content, isUtf8 = true))
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

        return track(
            MarkdownViewModel(
                repository = fakeRepository,
                storage = storage,
                parseHeadingsUseCase = parseHeadingsUseCase,
                searchUseCase = searchUseCase,
                pdfExporter = mockk(relaxed = true),
                appInfo = fakeAppInfo,
            ),
        )
    }

    /**
     * REAL barriers.
     *
     * `loadFile`/`saveFile` launch into `viewModelScope` and return immediately. Reading state on
     * the next line only worked because the fake repository above never suspends, so the whole
     * coroutine ran inline. Every assertion below was therefore TAUTOLOGICAL: it would have passed
     * against a load that never ran, so it could not have caught a real regression either — and it
     * broke the moment the production load path gained a genuine dispatcher hop.
     *
     * These suspend on the state each operation actually publishes, so they hold however many times
     * the production path switches threads. Third occurrence of this class here (`553fabb`, PR #56);
     * a barrier that cannot fail is not a barrier.
     */
    private suspend fun MarkdownViewModel.awaitLoaded() {
        fileLoadState.first { it is FileLoadState.Success }
    }

    /** Suspend until a save reached a terminal outcome; which one is the test's business. */
    private suspend fun MarkdownViewModel.awaitSaveSettled() {
        fileLoadState.first { it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError }
    }

    @Test
    fun testLfOnlyRoundTrip() = runTest {
        val content = "Line\nLine\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/lf_only.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals("LF content should round-trip byte-identical", content, saved)
    }

    @Test
    fun testCrlfOnlyRoundTrip() = runTest {
        val content = "Line\r\nLine\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/crlf_only.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals("CRLF content should round-trip byte-identical", content, saved)
    }

    @Test
    fun testFootnotedDocumentRoundTripsByteIdentical() = runTest {
        // Safeguard 2. The footnote work is viewing-only by design: a block parser, a pure
        // post-parse pass over the Document, and rendering. None of it is reachable from the save
        // path, which writes the raw string back. Proven rather than asserted — with CRLF endings
        // and a trailing space inside a note, the two things a "helpful" normaliser would eat.
        val content = "Newton[^1] and Curie[^c] wrote it down.\r\n" +
            "\r\n" +
            "[^c]: Discovered polonium. \r\n" +
            "\r\n" +
            "[^1]: gravity\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/footnotes.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()
        vm.saveFile()
        vm.awaitSaveSettled()

        assertEquals("CRLF", vm.lineEnding.value)
        assertEquals("footnote syntax must survive a save untouched", content, capturedSaves[testUri])
    }

    @Test
    fun testMixedCrlfDominant() = runTest {
        val content = "L1\r\nL2\nL3\r\nL4\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/mixed_crlf_dominant.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

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
        vm.awaitLoaded()

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals(content.replace("\r\n", "\n"), saved)
    }

    @Test
    fun testEmptyString() = runTest {
        val content = ""
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/empty.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals("Empty content should round-trip as empty", "", saved)
    }

    @Test
    fun testNoTrailingNewline() = runTest {
        val content = "L1\nL2\nL3"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/no_trailing_newline.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals("No trailing newline should be preserved", content, saved)
    }

    @Test
    fun testBlankLinesOnly() = runTest {
        val content = "\n\n\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/blank_lines.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

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
        vm.awaitLoaded()
        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveAndClose()
        // saveAndClose ends at Idle, not SaveSuccess (closing must not replay a "Saved"
        // toast), so the close itself is the barrier.
        vm.currentDocument.first { it == null }

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
        vm.awaitLoaded()
        assertEquals("CRLF", vm.lineEnding.value)

        // Dirty the doc so an incoming intent defers behind the Save/Discard prompt (sets pendingOpenUri).
        vm.updateContent(vm.currentDocument.value!!.id, "L1\nL2\nL3\n") // editor/model work in LF
        vm.openFromIntent(Uri.parse("content://test/incoming.md"))

        vm.saveAndOpenPending()
        // The pending file opens only AFTER the save completes, so its arrival is the save barrier.
        vm.currentDocument.first { it?.uri == Uri.parse("content://test/incoming.md") }

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
        vm.awaitLoaded()
        vm.saveFile()
        vm.awaitSaveSettled()
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
        vm.awaitLoaded()
        vm.saveFile()
        vm.awaitSaveSettled()
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
        vm.awaitLoaded()
        vm.saveFile()
        vm.awaitSaveSettled()
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

    // ---- M-353: a bare CR in the file must survive the CRLF round trip (Safeguard 2) ----
    //
    // Load folds every "\r\n" to "\n"; any "\r" left in the model is a byte the file really had.
    // Saving a CRLF document therefore converts ONLY "\n" back to "\r\n". The old `\r?\n` regex
    // treated the surviving CR + the restored LF as one CRLF and wrote the CR away (33 bytes in,
    // 32 out; found by PR #239's byte-identity corpus).

    private suspend fun assertRoundTripsByteIdentical(content: String, expectedEnding: String, name: String) {
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/$name")

        vm.loadFile(testUri)
        vm.awaitLoaded()
        assertEquals(expectedEnding, vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        val saved = capturedSaves[testUri]
        assertEquals("$name should round-trip byte-identical", content, saved)
    }

    @Test
    fun testCrBeforeCrlfRoundTrips() = runTest {
        assertRoundTripsByteIdentical("line one\r\r\nline two\r\nline three\r\n", "CRLF", "cr_cr_lf.md")
    }

    @Test
    fun testTwoCrsBeforeCrlfRoundTrip() = runTest {
        assertRoundTripsByteIdentical("a\r\r\r\nb\r\n", "CRLF", "cr_cr_cr_lf.md")
    }

    @Test
    fun testLoneCrInsideLineRoundTrips() = runTest {
        assertRoundTripsByteIdentical("a\rb\r\nc\r\n", "CRLF", "lone_cr.md")
    }

    @Test
    fun testLoneCrOnlyFileRoundTrips() = runTest {
        assertRoundTripsByteIdentical("a\rb\rc", "LF", "cr_only.md")
    }

    @Test
    fun testCrAtEndOfFileRoundTrips() = runTest {
        assertRoundTripsByteIdentical("line\r\nend\r", "CRLF", "trailing_cr.md")
    }

    @Test
    fun testCrlfBlankLinesRoundTrip() = runTest {
        assertRoundTripsByteIdentical("para\r\n\r\npara\r\n\r\n", "CRLF", "crlf_blank.md")
    }

    @Test
    fun testCrBeforeCrlfInMixedCrlfDominantFile() = runTest {
        // Mixed endings normalise to the dominant style (documented limitation, pinned by
        // testMixedCrlfDominant above); the bare CR is content and must still come back.
        val content = "A\r\r\nB\nC\r\nD\r\n"
        val vm = createViewModelWithContent(content)
        val testUri = Uri.parse("content://test/mixed_cr.md")

        vm.loadFile(testUri)
        vm.awaitLoaded()
        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitSaveSettled()

        assertEquals("A\r\r\nB\r\nC\r\nD\r\n", capturedSaves[testUri])
    }

    // ---- M-353: a pasted CRLF must be saved as CRLF, never as CR CR LF (Safeguard 2) ----
    //
    // Measured on the emulator: pasting "\r\n" into a CRLF file saved the break as 0d 0d 0a, because
    // Sora kept the pasted CR and the save then restored a CRLF from its LF. PilcrowCodeEditor folds the
    // pair at insertion; this drives the real editor into the real ViewModel save.

    @Test
    fun testPastedCrlfIsSavedAsCrlfNotCrCrLf() = runTest {
        val vm = createViewModelWithContent("A\r\nB\r\n")
        val testUri = Uri.parse("content://test/paste_crlf.md")
        vm.loadFile(testUri)
        vm.awaitLoaded()

        val editor = PilcrowCodeEditor(context)
        editor.setText(vm.currentDocument.value!!.content)
        // Sora builds the layout on its own thread pool and fails an insert made before it is done.
        val deadline = System.currentTimeMillis() + 30_000
        while (!editor.isEditable && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("precondition: the editor must accept input", editor.isEditable)
        editor.commitText("PASTE-A\r\nPASTE-B\r\n") // the cursor sits at the start of the document
        vm.updateContent(vm.currentDocument.value!!.id, editor.text.toString())

        vm.saveFile()
        vm.awaitSaveSettled()

        assertEquals(
            "a pasted CRLF must reach the file as 0d 0a, never 0d 0d 0a",
            "PASTE-A\r\nPASTE-B\r\nA\r\nB\r\n",
            capturedSaves[testUri],
        )
    }
}
