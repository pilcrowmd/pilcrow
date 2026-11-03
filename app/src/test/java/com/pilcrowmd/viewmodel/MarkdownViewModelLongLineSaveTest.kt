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
import org.junit.Assert.assertNotEquals
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
 * S8-A64, Safeguard 2: a file whose long line the editor shows cut into display lines saves to the
 * byte what was opened, and an edit saves exactly the edit. Real ViewModel load and save (CRLF fold
 * and restore included), real [PilcrowCodeEditor] and Sora undo manager in between, the bytes taken
 * from the repository. Every expected byte string is built here from the original by plain string
 * operations, not by the code under test.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelLongLineSaveTest {

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

    private suspend fun MarkdownViewModel.awaitLoaded() {
        fileLoadState.first { it is FileLoadState.Success }
    }

    private suspend fun MarkdownViewModel.awaitSaveSettled() {
        fileLoadState.first { it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError }
    }

    /** The loaded document in the editor, as Edit shows it. */
    private fun editorFor(vm: MarkdownViewModel): PilcrowCodeEditor {
        val editor = PilcrowCodeEditor(context)
        editor.setFileText(vm.currentDocument.value!!.content)
        // Sora measures the text on its own thread pool before it accepts input.
        val deadline = System.currentTimeMillis() + 30_000
        while (!editor.isEditable && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertTrue("precondition: the editor must accept input", editor.isEditable)
        return editor
    }

    private suspend fun MarkdownViewModel.saveFrom(editor: PilcrowCodeEditor) {
        updateContent(currentDocument.value!!.id, editor.fileText())
        saveFile()
        awaitSaveSettled()
    }

    private val longAscii = "lorem ipsum dolor ".repeat(400) // 7,200 chars
    private val files = mapOf(
        "ascii" to "# Title\n" + longAscii + "\nend\n",
        "cjk" to "見出し\n" + "漢字かな交じり文。".repeat(700) + "\n",
        "emoji" to "👩‍👩‍👧‍👦😀 ".repeat(600) + "\nx",
        "crlf" to "A\r\n" + longAscii + "\r\nB\r\n",
    )

    @Test
    fun openEditSaveWithNoEditGivesTheSameBytes() = runTest {
        for ((name, original) in files) {
            val vm = createViewModelWithContent(original)
            val uri = Uri.parse("content://test/long-$name.md")
            vm.loadFile(uri)
            vm.awaitLoaded()
            val editor = editorFor(vm)
            val content = vm.currentDocument.value!!.content
            assertNotEquals("precondition ($name): the editor shows the long line cut", content, editor.text.toString())

            vm.saveFrom(editor)

            assertEquals("$name: the same bytes", original, capturedSaves[uri])
            editor.release()
        }
    }

    /**
     * Append X, type Y inside the long line, Enter further on, Backspace a display break (joins two
     * pieces), Backspace the Enter, Undo twice (the Enter and the break come back), Save.
     */
    private suspend fun scriptedEditSaves(original: String, crlf: Boolean) {
        val vm = createViewModelWithContent(original)
        val uri = Uri.parse("content://test/scripted.md")
        vm.loadFile(uri)
        vm.awaitLoaded()
        val editor = editorFor(vm)
        val lf = original.replace("\r\n", "\n")
        val long = lf.indexOf(longAscii)
        val typeAt = long + 2_500
        val enterAt = long + 4_321

        fun insert(fileOffset: Int, s: String) {
            val p = editor.text.indexer.getCharPosition(editor.toEditorOffset(fileOffset))
            editor.text.insert(p.line, p.column, s)
        }

        fun deleteEditorChar(at: Int) {
            val a = editor.text.indexer.getCharPosition(at)
            val b = editor.text.indexer.getCharPosition(at + 1)
            editor.text.delete(a.line, a.column, b.line, b.column)
        }
        insert(lf.length, "X")
        insert(typeAt, "Y")
        insert(enterAt + 1, "\n")
        val firstBreak = editor.text.getCharIndex(2, 0) - 1 // the long line is display line 1
        val withEnter = editor.fileText()
        deleteEditorChar(firstBreak)
        assertEquals("a display break's backspace leaves the file alone", withEnter, editor.fileText())
        deleteEditorChar(editor.toEditorOffset(enterAt + 1))
        editor.undo()
        editor.undo()

        vm.saveFrom(editor)

        val expectedLf =
            lf.substring(0, typeAt) + "Y" + lf.substring(typeAt, enterAt) + "\n" + lf.substring(enterAt) + "X"
        val expected = if (crlf) expectedLf.replace("\n", "\r\n") else expectedLf
        assertEquals(expected, capturedSaves[uri])
        editor.release()
    }

    @Test
    fun aScriptedEditWithUndoSavesExactlyTheEdit() = runTest {
        scriptedEditSaves(files.getValue("ascii"), crlf = false)
    }

    @Test
    fun aScriptedEditWithUndoSavesExactlyTheEditInACrlfFile() = runTest {
        scriptedEditSaves(files.getValue("crlf"), crlf = true)
    }
}
