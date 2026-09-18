// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
 * **M-115.** The welcome screen disables its actions while [FileLoadState.Loading], so `Loading`
 * must be a state the load ALWAYS leaves. A gate on a state that can stick is a locked screen, so
 * every exit from `loadDocument` is pinned here: success, a `readFile` failure, and a throw from
 * inside the CPU pass — the one path that is neither a `Result` nor on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLoadTerminalStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private class Repo(
        private val result: Result<String> = Result.success("# hello\n"),
        /** Throw from `displayName` — a suspend call AFTER the CPU pass, BEFORE the document emit. */
        private val throwOnDisplayName: Boolean = false,
        /** Throw from the permission IPC — AFTER the document emit, BEFORE `emit(Success)`. */
        private val throwOnPermission: Boolean = false,
        /** Cancel from `displayName` — the guard's OTHER exit, which rethrows instead of reporting. */
        private val cancelOnDisplayName: Boolean = false,
        /** Parks the load inside `readFile` so the test can capture its Job while it is still alive. */
        private val readGate: CompletableDeferred<Unit>? = null,
    ) : FileRepository {
        override suspend fun readFile(uri: Uri): Result<String> {
            readGate?.await()
            return result
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String {
            if (cancelOnDisplayName) {
                throw kotlin.coroutines.cancellation.CancellationException("load cancelled")
            }
            if (throwOnDisplayName) error("display name blew up")
            return "doc.md"
        }
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean {
            if (throwOnPermission) error("permission lookup blew up")
            return true
        }
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("terminal_state_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vmWith(repo: FileRepository, cpu: CoroutineDispatcher? = null): MarkdownViewModel {
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
            cpuDispatcher = cpu ?: Dispatchers.Unconfined,
        )
    }

    private fun settle() {
        repeat(60) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5L)
        }
    }

    /** Terminal = the load is over. `Loading` is not terminal, and that is the whole point. */
    private fun assertTerminal(vm: MarkdownViewModel, path: String) {
        val state = vm.fileLoadState.value
        assertTrue(
            "$path left FileLoadState at $state — the welcome-screen gate disables its actions " +
                "while Loading, so a load that never leaves Loading is a permanently dead screen",
            state is FileLoadState.Success || state is FileLoadState.Error,
        )
    }

    @Test
    fun `a successful load ends in a terminal state`() {
        val vm = vmWith(Repo())
        vm.loadFile(Uri.parse("content://t/a.md"))
        settle()
        assertTerminal(vm, "a successful load")
    }

    @Test
    fun `a readFile failure ends in a terminal state`() {
        val vm = vmWith(Repo(Result.failure(RuntimeException("unreadable"))))
        vm.loadFile(Uri.parse("content://t/a.md"))
        settle()
        assertTerminal(vm, "a readFile failure")
    }

    @Test
    fun `a throw after readFile and before the document emit ends in a terminal state`() {
        // `displayName` is the first suspend call after the CPU pass. The CPU block itself is pure
        // string work over `content` (`detectLineEnding` + a `replace`) with no injectable failure,
        // so this is the nearest reachable point on that same stretch — and the stretch is what
        // matters: everything here ran on the raw path to `emit(Success)`.
        val vm = vmWith(Repo(throwOnDisplayName = true))
        vm.loadFile(Uri.parse("content://t/a.md"))
        settle()
        assertTerminal(vm, "a throw before the document emit")
    }

    @Test
    fun `a throw after the document emit and before Success ends in a terminal state`() {
        // The permission IPC runs AFTER the document is published but BEFORE the outcome. A throw
        // here used to leave a document on screen with the load never reported as finished.
        val vm = vmWith(Repo(throwOnPermission = true))
        vm.loadFile(Uri.parse("content://t/a.md"))
        settle()
        assertTerminal(vm, "a throw after the document emit")
    }

    @Test
    fun `a cancelled load leaves Loading and still cancels the coroutine`() {
        // Cancellation is the guard's other exit: it must NOT be reported as an error, but it must
        // still leave Loading, or a cancelled cold-start restore disables the welcome screen for
        // good. The rethrow has to survive that extra emit.
        val gate = CompletableDeferred<Unit>()
        val vm = vmWith(Repo(cancelOnDisplayName = true, readGate = gate))
        shadowOf(Looper.getMainLooper()).idle()
        // The ViewModel's init launches long-lived preference collectors, so the load has to be
        // picked out by difference — `children.first()` returns one of those and stays Active
        // forever, which would assert nothing.
        val before = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()

        vm.loadFile(Uri.parse("content://t/a.md"))
        shadowOf(Looper.getMainLooper()).idle()

        // Load-bearing, not ceremony: Idle is also the INITIAL state, so without proving Loading
        // was really published first, the final assertEquals(Idle) would pass against a load that
        // never ran. Parking in readFile is also what keeps the Job capturable — once the
        // coroutine finishes, the parent drops it from `children` and there is nothing to inspect.
        assertEquals(FileLoadState.Loading, vm.fileLoadState.value)
        val load = (vm.viewModelScope.coroutineContext[Job]!!.children.toSet() - before).single()

        gate.complete(Unit)
        settle()

        assertTrue(
            "the CancellationException was swallowed — the load coroutine completed normally " +
                "($load), so a cancelled load is indistinguishable from one that finished",
            load.isCancelled,
        )
        assertEquals(
            "a cancelled load left FileLoadState at Loading — the welcome-screen gate cannot tell " +
                "a cancelled load from a stuck one, so it would stay disabled",
            FileLoadState.Idle,
            vm.fileLoadState.value,
        )
    }

    @Test
    fun `a load cancelled from outside still reaches Idle`() {
        // THE TEST ABOVE DOES NOT COVER THIS, AND THE DIFFERENCE IS THE WHOLE POINT. There the
        // CancellationException is thrown from inside `displayName` while nothing has cancelled the
        // Job — and a Job only becomes Cancelled once the exception leaves the coroutine body, i.e.
        // AFTER the catch has run. So that test's `emit(Idle)` executes on a still-ACTIVE coroutine
        // and proves nothing about emitting from a cancelled one.
        //
        // Here the Job is cancelled EXTERNALLY while the load is parked in `readFile`, so the catch
        // runs on a genuinely cancelled coroutine. That is the case the guard relies on: emitting
        // there is only safe because `MutableStateFlow.emit` never suspends and so never reaches a
        // cancellation check. This test is what turns that from a claim into a guarantee — if
        // `emit` ever gains one, `Loading` sticks and this fails.
        val gate = CompletableDeferred<Unit>()
        val vm = vmWith(Repo(readGate = gate))
        shadowOf(Looper.getMainLooper()).idle()
        val before = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()

        vm.loadFile(Uri.parse("content://t/a.md"))
        shadowOf(Looper.getMainLooper()).idle()

        // Same reason as above: Idle is the INITIAL state, so proving Loading was really published
        // is what stops the final assertion passing against a load that never ran.
        assertEquals(FileLoadState.Loading, vm.fileLoadState.value)
        val load = (vm.viewModelScope.coroutineContext[Job]!!.children.toSet() - before).single()

        load.cancel()
        gate.complete(Unit)
        settle()

        assertTrue("the load Job was not cancelled ($load)", load.isCancelled)
        assertEquals(
            "a load cancelled from outside left FileLoadState at Loading — the catch ran on a " +
                "genuinely cancelled coroutine and its emit did not take effect",
            FileLoadState.Idle,
            vm.fileLoadState.value,
        )
    }
}
