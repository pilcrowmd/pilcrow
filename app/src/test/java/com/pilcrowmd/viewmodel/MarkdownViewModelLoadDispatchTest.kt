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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

/**
 * The load path must do its CPU work OFF the main thread (Play Vitals ANR, 1.0.3).
 *
 * This is the only automated assertion that the threading half of that fix is real. It cannot
 * measure the SPEED of the work — a JVM unit test cannot observe the Android ICU cost that caused
 * the outage (see [MarkdownViewModelLineEndingScalingTest]) — but it does pin the structural
 * property a future refactor could silently undo: that `loadDocument`'s CPU block is dispatched
 * away from the caller's context rather than run inline on Main.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLoadDispatchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    /** Records that work was handed to it, then runs it inline so the test stays deterministic. */
    private class TrackingDispatcher : CoroutineDispatcher() {
        var dispatchCount = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            block.run()
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("load_dispatch_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private fun viewModel(content: String, dispatcher: CoroutineDispatcher): MarkdownViewModel {
        val fakeRepository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(content)
            override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
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
        return MarkdownViewModel(
            repository = fakeRepository,
            storage = storage,
            parseHeadingsUseCase = parseHeadingsUseCase,
            searchUseCase = SearchMarkdownUseCase(parseHeadingsUseCase),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName: String = "test"
            },
            cpuDispatcher = dispatcher,
        )
    }

    @Test
    fun `loading a document dispatches its CPU work off the caller's thread`() = runTest {
        val tracking = TrackingDispatcher()
        val vm = viewModel("Line\r\nLine\r\n", tracking)

        vm.loadFile(Uri.parse("content://test/dispatch.md"))

        assertTrue(
            "loadDocument must hand its CPU block to the injected dispatcher, not run it on Main",
            tracking.dispatchCount > 0,
        )
        // The work still has to be CORRECT, not merely relocated.
        assertEquals("CRLF", vm.lineEnding.value)
    }

    /**
     * A REAL barrier: suspend until the load has actually published a document. The protected
     * round-trip suite reads state immediately after `loadFile` and passed only because its fake
     * repository never suspends — see the note in the PR. These re-check the same semantics with a
     * barrier, so they hold regardless of how many dispatcher hops the load path contains.
     */
    private suspend fun MarkdownViewModel.awaitLoaded() {
        withTimeout(5_000) { currentDocument.filterNotNull().first() }
    }

    @Test
    fun `CRLF is still detected once the load has actually finished`() = runTest {
        val vm = viewModel("Line\r\nLine\r\n", TrackingDispatcher())
        vm.loadFile(Uri.parse("content://test/crlf.md"))
        vm.awaitLoaded()
        assertEquals("CRLF", vm.lineEnding.value)
    }

    @Test
    fun `a mixed file still resolves to its DOMINANT ending, not its first`() = runTest {
        // 2 CRLF vs 3 LF -> LF dominates. First-match would wrongly answer CRLF here, which is why
        // the both-counts semantics are load-bearing for Safeguard 2.
        val vm = viewModel("a\r\nb\r\nc\nd\ne\n", TrackingDispatcher())
        vm.loadFile(Uri.parse("content://test/mixed.md"))
        vm.awaitLoaded()
        assertEquals("LF", vm.lineEnding.value)
    }

    @Test
    fun `a CRLF-dominant mixed file still resolves to CRLF`() = runTest {
        val vm = viewModel("a\r\nb\r\nc\r\nd\ne\n", TrackingDispatcher())
        vm.loadFile(Uri.parse("content://test/mixed2.md"))
        vm.awaitLoaded()
        assertEquals("CRLF", vm.lineEnding.value)
    }
}
