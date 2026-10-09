// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import io.github.rosemoe.sora.widget.CodeEditor
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * M-23 / M-112: a Save-As must not throw away the editor's undo history.
 *
 * Save-As changes the document's URI (a new document's goes `null → real`, M-112). MainScreen used to
 * key the editor on that URI, so the editor node was rebuilt and its factory called `setText`,
 * which hands Sora a new `Content` and with it a new, empty undo manager. The document's
 * [com.pilcrowmd.viewmodel.DocumentId] survives Save-As (`adoptIdentity`), so keying on it keeps the
 * node, the `Content` and the history.
 *
 * **The Save-As write is held on a gate**, so the adoption genuinely happens after the call returns:
 * the test must wait on the URI Save-As publishes before it can observe anything. Without that wait
 * the assertions would read the editor before the key ever changed and pass against the bug.
 */
@RunWith(RobolectricTestRunner::class)
class SaveAsKeepsEditorUndoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val aUri = Uri.parse("content://t/a.md")
    private val copyUri = Uri.parse("content://t/copy.md")
    private val aDisk = "alpha from A\n"

    private val saveGate = CompletableDeferred<Unit>()

    private lateinit var storageScope: CoroutineScope

    // Wait for the scope, not just cancel it: the TemporaryFolder rule deletes the folder after @After,
    // and a DataStore write still in flight would fail into the next test.
    @After
    fun tearDown() {
        val storageJob = storageScope.coroutineContext.job
        storageJob.cancel()
        awaitTrue("the storage scope never settled") { storageJob.isCompleted }
    }

    private val repo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            aUri -> Result.success(FileText(aDisk, isUtf8 = true))
            else -> Result.failure(IOException("unreadable: $uri"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            saveGate.await()
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

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("save_as_undo.preferences_pb")
        }
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = LocalStorageManager(context, ds),
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

    private fun findEditor(view: View): CodeEditor? = when (view) {
        is CodeEditor -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditor(view.getChildAt(it)) }
        else -> null
    }

    private fun composeMainScreen(vm: MarkdownViewModel) {
        compose.setContent {
            MaterialTheme {
                MainScreen(viewModel = vm, context = compose.activity, renderer = MarkwonRenderer(compose.activity))
            }
        }
    }

    /**
     * Save-As's the open document to [copyUri] and checks the editor still holds the same `Content`
     * and the same undo manager. Sora keeps the undo history ON the `Content` (`getUndoManager`),
     * and `setText` is the only thing that replaces it, so the same `Content` IS the same history.
     *
     * **No edit is typed, deliberately.** Any write to the `Content` blocks forever under
     * Robolectric: the word-wrap layout task spins in the JDK's `RuleBasedBreakIterator` (the
     * stand-in for Android's ICU) holding the content's read lock, so the insert never gets the
     * write lock. Measured on this test with `editor.text.insert(...)`: the thread dump showed the
     * test thread parked in `Content.lock` under `Content.insert`, and two `WordwrapAnalyzeTask`s
     * spinning in `RuleBasedBreakIterator.handlePrevious`. This is the M-23 hang; `canUndo()` after a
     * real edit therefore cannot be asserted here.
     */
    private fun saveAsAndAssertUndoHistorySurvives(vm: MarkdownViewModel) {
        // The CodeEditor itself is hoisted and reused even when the node is rebuilt, so comparing
        // instances would pass on the broken code too: the Content is what a rebuild replaces.
        val editor = requireNotNull(findEditor(compose.activity.window.decorView)) { "the editor never composed" }
        val contentBefore = editor.text
        val undoBefore = contentBefore.undoManager
        val idBefore = vm.currentDocument.value!!.id

        vm.saveActiveDocumentAs(copyUri)
        saveGate.complete(Unit)
        // Wait on the adoption alone: MainScreen resets SaveSuccess to Idle within a frame, so a
        // poll can miss it. adoptIdentity publishes the new uri before SaveSuccess is emitted.
        awaitTrue("Save-As never adopted the copy") { vm.currentDocument.value?.uri == copyUri }
        compose.waitForIdle()

        assertEquals("precondition: Save-As keeps the document identity", idBefore, vm.currentDocument.value!!.id)
        assertSame(
            "Save-As rebuilt the editor's Content (setText), which discards its undo history",
            contentBefore,
            editor.text,
        )
        assertSame("Save-As replaced the editor's undo manager", undoBefore, editor.text.undoManager)
    }

    /** M-23: an opened file, saved as a copy. */
    @Test
    fun saveAsOfAnOpenedFileKeepsTheEditorAndItsUndoHistory() {
        val vm = viewModel()
        composeMainScreen(vm)
        vm.loadFile(aUri)
        awaitTrue("A never finished loading") {
            vm.currentDocument.value?.uri == aUri && vm.fileLoadState.value == FileLoadState.Success
        }
        vm.setMode(ViewMode.EDITOR)
        compose.waitForIdle()

        saveAsAndAssertUndoHistorySurvives(vm)
    }

    /** M-112: the first Save-As of a new document flips its URI from null to a real one. */
    @Test
    fun firstSaveAsOfANewDocumentKeepsTheEditorAndItsUndoHistory() {
        val vm = viewModel()
        composeMainScreen(vm)
        vm.newDocument()
        awaitTrue("the new document never opened in the editor") {
            vm.currentDocument.value?.let { it.uri == null } == true && vm.mode.value == ViewMode.EDITOR
        }
        compose.waitForIdle()

        saveAsAndAssertUndoHistorySurvives(vm)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
