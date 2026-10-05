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
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import com.pilcrowmd.testing.MainDispatcherSuite
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections

/**
 * Save, close, switch and load outcomes that the existing ViewModel suites leave unobserved. Each
 * test was written against a mutant that survived them (mutation testing, hardening item (d)) and
 * was seen to fail with that mutant applied:
 *
 *  - a saved document stays dirty (the `markSaved` call removed from saveFile);
 *  - Save-and-close keeps the document open, or closes it but never reaches `Idle` or clears the
 *    remembered file (`markSaved` / `onClosed` removed from saveAndClose);
 *  - Save-and-open leaves the Save/Discard prompt raised (the `pendingOpenUri` reset removed);
 *  - a line-ending TIE is saved as CRLF (`crlf > lf` turned into `crlf >= lf`);
 *  - a load that throws after the read leaves the welcome screen without its failure message;
 *  - a failed Save-and-open or Save-and-close goes on as if the write had succeeded (the
 *    `isFailure` / `onSuccess` checks removed) — Safeguard 1;
 *  - Save-and-open writes a document that is clean again (type, then undo) — the `dirty` check;
 *  - a picked file is opened without persisting its write grant (the `takePermission` branch);
 *  - a new load attempt leaves the previous failure's message up (`markLoadFailure(false)`).
 *
 * Harness and barriers follow [MarkdownViewModelLineEndingTest]: `Dispatchers.setMain`, hence the
 * [MainDispatcherSuite] category, and every assertion after an asynchronous call waits on the state
 * that call publishes, under a timeout, so a mutant that never publishes it fails instead of hanging.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelSaveLoadEdgeTest {

    private val vmStore = ViewModelStore()
    private var vmSeq = 0

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val uriA: Uri = Uri.parse("content://test/a.md")
    private val uriB: Uri = Uri.parse("content://test/b.md")

    /**
     * Reads from [contents] (a read of [gatedUri] parks until [readGate] completes); records every
     * save and every permission taken. [savesFail] fails every write; [displayNameThrows] fails the
     * load after the read. A write grant exists only for URIs whose permission was taken, when
     * [grantsNeedTaking] is set — so a picked file that is never persisted opens transient.
     */
    private class FakeRepo(
        private val contents: Map<Uri, String>,
        private val displayNameThrows: Boolean = false,
        private val savesFail: Boolean = false,
        private val grantsNeedTaking: Boolean = false,
        private val gatedUri: Uri? = null,
        val readGate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : FileRepository {
        val saves = mutableListOf<Pair<Uri, String>>()
        val permissionsTaken = mutableListOf<Uri>()
        override suspend fun readFile(uri: Uri): Result<FileText> {
            if (uri == gatedUri) readGate.await()
            return contents[uri]?.let { Result.success(FileText(it, isUtf8 = true)) }
                ?: Result.failure(IllegalStateException("no such file"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            if (savesFail) return Result.failure(IOException("disk full (test)"))
            saves += uri to content
            return Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> {
            permissionsTaken += uri
            return Result.success(Unit)
        }
        override suspend fun displayName(uri: Uri): String {
            check(!displayNameThrows) { "provider went away" }
            return uri.lastPathSegment ?: "doc.md"
        }
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = !grantsNeedTaking || uri in permissionsTaken
        override suspend fun strandedSlots() = Result.success(emptyList<StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    /** One write to the remembered-file pointer, in the order the ViewModel made it. */
    private sealed interface PointerWrite {
        data class Set(val uri: Uri) : PointerWrite
        data object Clear : PointerWrite
    }

    /**
     * The remembered-file pointer in memory, with a log of every write; everything else is the real
     * manager, by delegation. [clearGate] parks the clear until the test releases it.
     *
     * **Why not the real DataStore (M-211).** DataStore 1.1.1 bumps its version before it writes
     * the file. A `data` subscriber that starts inside that window reads the OLD file, tags it with
     * the NEW version, and then drops the cache update of that same version, so it keeps the old
     * value until some later write. `onClosed` launches the clear without awaiting it, and nothing
     * writes after it, so a wait for `null` that started during the clear waited for ever: 24 of
     * 200 looped runs timed out there. Here the pointer is a StateFlow, which cannot lose an update.
     */
    private class PointerStorage(delegate: StorageManager, private val clearGate: CompletableDeferred<Unit>) :
        StorageManager by delegate {
        val pointer = MutableStateFlow<Uri?>(null)
        val writes: MutableList<PointerWrite> = Collections.synchronizedList(mutableListOf())
        val clearEntered = CompletableDeferred<Unit>()
        override val lastFileUri: Flow<Uri?> = pointer
        override suspend fun saveLastFileUri(uri: Uri) {
            writes += PointerWrite.Set(uri)
            pointer.value = uri
        }
        override suspend fun clearLastFileUri() {
            writes += PointerWrite.Clear
            clearEntered.complete(Unit)
            clearGate.await()
            pointer.value = null
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("save_load_edge_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear() // cancels every viewModelScope BEFORE the global is released
        storageScope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm(repo: FileRepository, storage: StorageManager = this.storage): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = repo,
            storage = storage,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vmStore.put("vm-${vmSeq++}", vm)
        return vm
    }

    /**
     * A timeout in REAL time. Inside `runTest`, `withTimeout` runs on virtual time, which jumps
     * straight to the deadline while the load is off on Dispatchers.IO — so the wait moves to
     * Dispatchers.Default, where the clock is the wall clock.
     */
    private suspend fun <T> within(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { block() } }

    private suspend fun MarkdownViewModel.awaitOpen(uri: Uri) = within {
        currentDocument.first { it?.uri == uri }
        fileLoadState.first { it is FileLoadState.Success }
    }

    private suspend fun MarkdownViewModel.awaitState(label: String, done: (FileLoadState) -> Boolean) =
        within { fileLoadState.first(done) }.also { assertTrue(label, done(it)) }

    @Test
    fun aSuccessfulSaveLeavesTheDocumentClean() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n"))
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")
        assertTrue("fixture: the edit makes the document dirty", vm.currentDocument.value!!.dirty)

        vm.saveFile()
        vm.awaitState("the save must report success") { it is FileLoadState.SaveSuccess }

        assertEquals(listOf(uriA to "first, edited\n"), repo.saves)
        assertFalse("a document whose text reached disk is not dirty", vm.currentDocument.value!!.dirty)
    }

    @Test
    fun saveAndCloseWritesClosesAndForgetsTheFile() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n"))
        val clearGate = CompletableDeferred<Unit>()
        val pointerStorage = PointerStorage(storage, clearGate)
        val vm = vm(repo, pointerStorage)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        within { pointerStorage.lastFileUri.first { it == uriA } } // fixture: remembered
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")

        vm.saveAndClose()
        // A completed close reports Idle; a close that did not happen reports SaveSuccess instead.
        val end = vm.awaitState("save-and-close must settle") {
            it == FileLoadState.Idle || it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError
        }

        assertEquals("the close must complete, reporting Idle", FileLoadState.Idle, end)
        assertEquals(listOf(uriA to "first, edited\n"), repo.saves)
        assertNull("the document is closed", vm.currentDocument.value)
        assertFalse("no close prompt: the edits were saved", vm.closeNeedsConfirm.value)

        // The close launches the clear without awaiting it; wait for it to start, not to finish.
        val entered = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TIMEOUT_MS) { pointerStorage.clearEntered.await() }
        }
        assertNotNull("save-and-close must clear the remembered file; writes: ${pointerStorage.writes}", entered)
        assertEquals(
            "the load remembers the file once, the save writes no pointer, the close clears it",
            listOf(PointerWrite.Set(uriA), PointerWrite.Clear),
            pointerStorage.writes.toList(),
        )
        assertEquals("the clear is parked, so the file is still remembered", uriA, pointerStorage.pointer.value)
        clearGate.complete(Unit)
        within { pointerStorage.lastFileUri.first { it == null } } // not reopened on next launch
    }

    @Test
    fun saveAndOpenPendingRetiresThePrompt() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n", uriB to "second\n"))
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")
        vm.openFromIntent(uriB)
        assertEquals("fixture: the dirty document raises the prompt", uriB, vm.pendingOpenUri.value)

        vm.saveAndOpenPending()
        vm.awaitOpen(uriB)

        assertEquals(listOf(uriA to "first, edited\n"), repo.saves)
        assertNull("the prompt has been answered and must not come back", vm.pendingOpenUri.value)
    }

    /**
     * One CRLF and one bare LF: a tie, which the detector resolves to LF. Saved as LF, the text keeps
     * its one LF ending; resolved to CRLF instead, the save would rewrite the LF line as CRLF — a
     * silent change to bytes the user never touched (Safeguard 2).
     */
    @Test
    fun aLineEndingTieIsTreatedAsLf() = runTest {
        val repo = FakeRepo(mapOf(uriA to "one\r\ntwo\n"))
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)

        assertEquals("LF", vm.lineEnding.value)

        vm.saveFile()
        vm.awaitState("the save must report success") { it is FileLoadState.SaveSuccess }
        assertEquals(listOf(uriA to "one\ntwo\n"), repo.saves)
    }

    /**
     * M-126 for the path M-115 guards: a load that throws AFTER the read (here the display-name
     * lookup), with nothing open, must earn the persistent welcome-screen message, exactly as a
     * failed read does.
     */
    @Test
    fun aLoadThatThrowsAfterTheReadWithNothingOpenEarnsTheFailureMessage() = runTest {
        val vm = vm(FakeRepo(mapOf(uriA to "first\n"), displayNameThrows = true))

        vm.loadFile(uriA)
        vm.awaitState("the load must end in Error") { it is FileLoadState.Error }

        assertNull(vm.currentDocument.value)
        assertTrue("nothing is on screen, so the failure must stay visible", vm.loadFailedWithNoDocument.value)
    }

    /** Safeguard 1: a failed write must stop the switch, report it, and keep the edits on screen. */
    @Test
    fun aFailedSaveAndOpenPendingKeepsTheDocumentAndThePrompt() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n", uriB to "second\n"), savesFail = true)
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")
        vm.openFromIntent(uriB)

        vm.saveAndOpenPending()
        vm.awaitState("the failed write must be reported") { it is FileLoadState.SaveError }

        assertEquals(uriA, vm.currentDocument.value?.uri)
        assertEquals("first, edited\n", vm.currentDocument.value?.content)
        assertTrue("the edits were not written, so they stay dirty", vm.currentDocument.value!!.dirty)
        assertEquals("the prompt stays, so the user can still choose", uriB, vm.pendingOpenUri.value)
    }

    /** Safeguard 1: a failed write must not close the document or mark its edits saved. */
    @Test
    fun aFailedSaveAndCloseKeepsTheDocumentOpenAndDirty() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n"), savesFail = true)
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")

        vm.saveAndClose()
        vm.awaitState("the failed write must be reported") { it is FileLoadState.SaveError }

        assertEquals(uriA, vm.currentDocument.value?.uri)
        assertEquals("first, edited\n", vm.currentDocument.value?.content)
        assertTrue("the edits were not written, so they stay dirty", vm.currentDocument.value!!.dirty)
    }

    /**
     * Typed, then undone: the document is clean again while the prompt is up. There is nothing to
     * save, and writing the baseline back anyway would overwrite whatever the file holds now.
     */
    @Test
    fun saveAndOpenPendingDoesNotWriteADocumentThatIsCleanAgain() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n", uriB to "second\n"))
        val vm = vm(repo)
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        vm.updateContent(vm.currentDocument.value!!.id, "first, edited\n")
        vm.openFromIntent(uriB)
        vm.updateContent(vm.currentDocument.value!!.id, "first\n") // undo
        assertFalse("fixture: clean again", vm.currentDocument.value!!.dirty)

        vm.saveAndOpenPending()
        vm.awaitOpen(uriB)

        assertEquals("nothing was dirty, so nothing is written", emptyList<Pair<Uri, String>>(), repo.saves)
    }

    /** A file chosen in the picker is opened with its write grant persisted, so it is not transient. */
    @Test
    fun aPickedFileIsPersistedAndOpensWritable() = runTest {
        val repo = FakeRepo(mapOf(uriA to "first\n"), grantsNeedTaking = true)
        val vm = vm(repo)

        vm.openPickedFile(uriA)
        vm.awaitOpen(uriA)

        assertEquals(listOf(uriA), repo.permissionsTaken)
        assertFalse("a persisted grant makes the document writable", vm.transient.value)
    }

    /** M-126: a new attempt retires the last failure's message while it is still loading. */
    @Test
    fun aNewLoadAttemptRetiresThePreviousFailureMessage() = runTest {
        val repo = FakeRepo(mapOf(uriB to "second\n"), gatedUri = uriB)
        val vm = vm(repo)
        vm.loadFile(uriA) // not in contents: the read fails
        vm.awaitState("the first load must fail") { it is FileLoadState.Error }
        assertTrue("fixture: the failure message is up", vm.loadFailedWithNoDocument.value)

        vm.loadFile(uriB) // parks inside readFile
        vm.awaitState("the retry must be loading") { it is FileLoadState.Loading }

        assertFalse("the retry has retired the old message", vm.loadFailedWithNoDocument.value)
        repo.readGate.complete(Unit)
        vm.awaitOpen(uriB)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
