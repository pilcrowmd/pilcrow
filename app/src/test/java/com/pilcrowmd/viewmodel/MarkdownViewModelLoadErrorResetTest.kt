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
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
 * M-126 — a reported load failure must be clearable once it has been shown, and clearing it must
 * not swallow anything else.
 *
 * `resetLoadErrorState` exists because the UI consumes `FileLoadState.Error` immediately (so a
 * recomposition or screen remount cannot replay the toast) while keeping its own copy of the
 * message on screen. That makes the reset's **narrowness** the property worth pinning: a reset
 * that cleared any state would be able to drop a `Loading` that is still in flight — which is
 * M-115's permanently dead welcome screen, reintroduced from the other direction.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLoadErrorResetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private class FailingRepo(
        /** Parks the load inside `readFile`, so a test can observe `Loading` while it is live. */
        private val readGate: CompletableDeferred<Unit>? = null,
        private val result: Result<String> = Result.failure(IllegalStateException("gone")),
    ) : FileRepository {
        override suspend fun readFile(uri: Uri): Result<String> {
            readGate?.await()
            return result
        }
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

    /** Serves queued outcomes in order, so a test can open a document and then fail a load. */
    private class ScriptedRepo(private val outcomes: MutableList<Result<String>>) : FileRepository {
        override suspend fun readFile(uri: Uri): Result<String> =
            if (outcomes.isEmpty()) Result.failure(IllegalStateException("gone")) else outcomes.removeAt(0)
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

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("load_error_reset_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(repo: FileRepository = FailingRepo()): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = storage,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            cpuDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun settle() {
        repeat(60) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5L)
        }
    }

    @Test
    fun aFailedLoadReportsError_andTheResetClearsIt() {
        val viewModel = vm()
        viewModel.loadFile(Uri.parse("content://test/gone.md"))
        settle()

        assertTrue(
            "a failed read must report Error — this is the state that had no consumer",
            viewModel.fileLoadState.value is FileLoadState.Error,
        )

        viewModel.resetLoadErrorState()

        assertEquals(
            "the reset must return to Idle so a remount cannot replay the failure",
            FileLoadState.Idle,
            viewModel.fileLoadState.value,
        )
    }

    @Test
    fun theResetDoesNotTouchALoadThatIsStillInFlight() {
        // ⚠️ THE OBVIOUS VERSION OF THIS TEST IS TAUTOLOGICAL, and it was written first: asserting
        // that the reset leaves `Idle` alone passes even if the reset clears unconditionally,
        // because the state was already `Idle`. The property only becomes observable against a
        // state that is NOT the reset's target and NOT the value it would write — so this parks a
        // real load in `readFile` and checks `Loading` survives. Dropping a live `Loading` is
        // M-115's permanently dead welcome screen, arrived at from the other side.
        val gate = CompletableDeferred<Unit>()
        val viewModel = vm(FailingRepo(readGate = gate))

        viewModel.loadFile(Uri.parse("content://test/slow.md"))
        settle()
        assertTrue(
            "precondition: the load must actually be in flight",
            viewModel.fileLoadState.value is FileLoadState.Loading,
        )

        viewModel.resetLoadErrorState()

        assertTrue(
            "the reset must NOT clear a load that is still running",
            viewModel.fileLoadState.value is FileLoadState.Loading,
        )
        gate.complete(Unit)
        settle()
    }

    @Test
    fun aFailureWithNothingOpenEarnsThePersistentMessage() {
        val viewModel = vm()

        viewModel.loadFile(Uri.parse("content://test/gone.md"))
        settle()

        assertTrue(
            "a failure with no document open is the case the persistent line exists for",
            viewModel.loadFailedWithNoDocument.value,
        )
    }

    @Test
    fun aFailureWhileADocumentIsOpenDoesNotSurviveClosingThatDocument() {
        // ⚠️ THIS IS THE BUG THE FIRST VERSION OF M-126 SHIPPED WITH, and no test could see it,
        // because the decision lived inside MainScreen's LaunchedEffect and nothing composes
        // MainScreen. Sequence: a document is open, a second open fails (the toast reports it and
        // is the whole answer — the welcome screen is not on screen), the user works on, then
        // closes the document. If the failure were remembered, they would be greeted by
        // "Couldn't open that file" for something they did an hour ago.
        val viewModel = vm(
            ScriptedRepo(mutableListOf(Result.success("# open"), Result.failure(IllegalStateException("gone")))),
        )

        viewModel.loadFile(Uri.parse("content://test/good.md"))
        settle()
        assertTrue("precondition: a document must actually be open", viewModel.currentDocument.value != null)

        viewModel.loadFile(Uri.parse("content://test/gone.md"))
        settle()
        assertTrue(
            "a failure with a document open is answered by the toast, not a stored message",
            !viewModel.loadFailedWithNoDocument.value,
        )

        viewModel.closeFile()
        settle()
        assertTrue(
            "closing the document must not surface a failure from while it was open",
            !viewModel.loadFailedWithNoDocument.value,
        )
    }

    @Test
    fun aNewAttemptRetiresTheLastFailuresMessage() {
        val viewModel = vm(
            ScriptedRepo(mutableListOf(Result.failure(IllegalStateException("gone")), Result.success("# open"))),
        )

        viewModel.loadFile(Uri.parse("content://test/gone.md"))
        settle()
        assertTrue("precondition: the first attempt must fail", viewModel.loadFailedWithNoDocument.value)

        viewModel.loadFile(Uri.parse("content://test/good.md"))
        settle()

        assertTrue(
            "a successful open must retire the message, not leave it under the document",
            !viewModel.loadFailedWithNoDocument.value,
        )
    }

    @Test
    fun creatingADocumentAlsoRetiresTheLastFailuresMessage() {
        // The second instance of the staleness, found by tracing every path that publishes a
        // document rather than only the load path: newDocument() (M-91) bypasses
        // loadDocumentOrThrow entirely, so it has to retire the message itself.
        val viewModel = vm()

        viewModel.loadFile(Uri.parse("content://test/gone.md"))
        settle()
        assertTrue("precondition: the failure must be remembered", viewModel.loadFailedWithNoDocument.value)

        viewModel.newDocument()
        settle()

        assertTrue(
            "creating a document must retire the message, or closing it later resurrects it",
            !viewModel.loadFailedWithNoDocument.value,
        )
    }
}
