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
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.testing.MainDispatcherSuite
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
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

/**
 * Unit tests for the Save-As + transient-file behavior.
 *
 * Covers `saveActiveDocumentAs` (durable write to a NEW URI via the unchanged repository save path +
 * identity adoption — Safeguards 1 & 2) and the transient flag derived from a persisted *write* grant.
 *
 * Mirrors [MarkdownViewModelLineEndingTest]: a real StorageManager (Robolectric DataStore) + a
 * configurable fake FileRepository.
 *
 * It used to add "whose suspend functions return inline, so viewModelScope launches complete
 * synchronously and state can be asserted right after the call", and to avoid `Dispatchers.setMain`
 * on the grounds that it is a process-global mutation racing other classes in the JVM fork. Both
 * halves are corrected here. The first was the defect: asserting right after the call made every
 * assertion below TAUTOLOGICAL — it would have passed against a Save-As that never ran, and it broke
 * the moment the load path gained a real dispatcher hop. The second was true but for the
 * wrong reason: `maxParallelForks`/`forkEvery` spawn SEPARATE JVMs, so a process-global cannot leak
 * across them; only concurrency WITHIN one JVM (JUnit 5 parallel execution) would break it, and
 * JUnit 4 does not do that. `resetMain()` in @After restores the global between classes.
 * A barrier must wait on state the call actually publishes, never on an initial value.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelSaveAsTest {

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

    @Before
    fun setup() {
        // Required for the barriers below: viewModelScope dispatches to Main, which under Robolectric
        // is the paused looper the test thread itself owns, so suspending that thread would deadlock.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("saveas_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear() // cancels every viewModelScope BEFORE the global is released
        storageScope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * Configurable fake repository.
     * @param writableUris URIs for which a persisted *write* grant is held (drives transient state).
     */
    private class FakeRepo(
        private val readContent: String,
        val writableUris: MutableSet<Uri> = mutableSetOf(),
        private val saveSucceeds: Boolean = true,
        private val takePermSucceeds: Boolean = true,
        private val saveGate: CompletableDeferred<Unit>? = null,
    ) : FileRepository {
        val capturedSaves = linkedMapOf<Uri, String>()
        var takePermCalls = 0

        override suspend fun readFile(uri: Uri) = Result.success(readContent)

        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            saveGate?.await()
            return if (saveSucceeds) {
                capturedSaves[uri] = content
                Result.success(Unit)
            } else {
                Result.failure(IOException("save failed (test)"))
            }
        }

        override suspend fun recoverPendingSaves() = Result.success(0)

        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> {
            takePermCalls++
            return if (takePermSucceeds) {
                writableUris.add(uri) // models SAF granting persistable R/W
                Result.success(Unit)
            } else {
                Result.failure(SecurityException("not persistable (test)"))
            }
        }

        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = uri in writableUris
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun vmWith(repo: FileRepository): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return track(
            MarkdownViewModel(
                repository = repo,
                storage = storage,
                parseHeadingsUseCase = parseHeadings,
                searchUseCase = SearchMarkdownUseCase(parseHeadings),
                pdfExporter = mockk(relaxed = true),
                appInfo = object : AppInfo {
                    override val versionName = "test"
                },
            ),
        )
    }

    /**
     * REAL barriers. Each waits on the state the operation actually publishes, so
     * they hold however many times the production path switches threads. Every one was proved by
     * watching its test fail without it.
     */
    private suspend fun MarkdownViewModel.awaitLoaded(uri: Uri) {
        currentDocument.first { it?.uri == uri }
        // Identity alone is NOT enough: `loadDocument` publishes the document, THEN resolves the write
        // grant, THEN emits Success. Stopping at the identity returns before `transient` is set, which
        // only went unnoticed while the permission lookup was a non-suspending binder call. Waiting on
        // Success covers everything the load publishes.
        //
        // Deliberately not waiting on `transient` itself: its initial value is `false`, so a
        // `first { !it }` would return instantly without the load having run — the same unsound
        // barrier `553fabb` removed from the render-mode tests.
        fileLoadState.first { it is FileLoadState.Success }
    }

    /** A Save-As that is expected to finish, either way — the test asserts which. */
    private suspend fun MarkdownViewModel.awaitSaveSettled() {
        fileLoadState.first { it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError }
    }

    /** A Save-As deliberately parked at a gate: wait for it to have STARTED, not finished. */
    private suspend fun MarkdownViewModel.awaitSaving() {
        fileLoadState.first { it is FileLoadState.Saving }
    }

    @Test
    fun saveActiveDocumentAsAdoptsNewIdentityOnSuccess() = runTest {
        val oldUri = Uri.parse("content://test/old.md")
        val newUri = Uri.parse("content://test/new.md")
        val repo = FakeRepo("hello\nworld\n", writableUris = mutableSetOf(oldUri))
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        val doc = vm.currentDocument.value!!
        assertEquals("identity re-points to the new URI", newUri, doc.uri)
        assertEquals("display name follows the new URI", "new.md", doc.displayName)
        assertFalse("adopted document is clean", doc.dirty)
        assertEquals("content is preserved", "hello\nworld\n", doc.content)
        assertEquals("save outcome is success", FileLoadState.SaveSuccess, vm.fileLoadState.value)
    }

    @Test
    fun saveActiveDocumentAsWritesToNewUriAndNeverTouchesOriginal() = runTest {
        // Safeguard 1: Save-As writes the NEW URI only; the original file is a different URI, untouched.
        // Safeguard 2: CRLF source → byte-identical CRLF written to the copy (contentForDisk reuse).
        val oldUri = Uri.parse("content://test/source.md")
        val newUri = Uri.parse("content://test/copy.md")
        val repo = FakeRepo("L1\r\nL2\r\n", writableUris = mutableSetOf(oldUri))
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)
        assertEquals("CRLF", vm.lineEnding.value)

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        assertEquals("copy is byte-identical CRLF", "L1\r\nL2\r\n", repo.capturedSaves[newUri])
        assertFalse("original URI is never written by Save-As", repo.capturedSaves.containsKey(oldUri))
    }

    @Test
    fun saveActiveDocumentAsCarriesEditsIntoTheCopy() = runTest {
        val oldUri = Uri.parse("content://test/a.md")
        val newUri = Uri.parse("content://test/b.md")
        val repo = FakeRepo("orig\n", writableUris = mutableSetOf(oldUri))
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)
        vm.updateContent("edited\n")

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        assertEquals("the edited content is what gets copied", "edited\n", repo.capturedSaves[newUri])
        assertEquals("edited\n", vm.currentDocument.value!!.content)
        assertFalse(vm.currentDocument.value!!.dirty)
    }

    @Test
    fun saveActiveDocumentAsPreservesEditsTypedDuringTheWrite() = runTest {
        // Mirrors saveFile's concurrency rule: if the user types WHILE the Save-As write is in flight,
        // the copy holds the snapshot taken at save start, but the live document keeps the newer edit
        // and stays dirty (Safeguard 2 — those edits are never silently marked saved).
        val oldUri = Uri.parse("content://test/live.md")
        val newUri = Uri.parse("content://test/livecopy.md")
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo("orig\n", writableUris = mutableSetOf(oldUri, newUri), saveGate = gate)
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)

        vm.saveActiveDocumentAs(newUri) // snapshots "orig\n", parks at the gate
        vm.awaitSaving() // the snapshot is taken by now; only then is typing a race worth testing
        vm.updateContent("edited during save\n") // user types while the write is suspended
        gate.complete(Unit)
        vm.awaitSaveSettled()

        assertEquals("copy holds the save-start snapshot", "orig\n", repo.capturedSaves[newUri])
        val doc = vm.currentDocument.value!!
        assertEquals("identity adopted", newUri, doc.uri)
        assertEquals("live document keeps the newer edit", "edited during save\n", doc.content)
        assertTrue("still dirty because content changed during the write", doc.dirty)
    }

    @Test
    fun saveActiveDocumentAsFailureKeepsSourceAndEdits() = runTest {
        // Safeguard 1: a failed Save-As surfaces SaveError, keeps the original identity, and retains
        // the unsaved edits in memory (nothing is lost; the source file was never touched).
        val oldUri = Uri.parse("content://test/keep.md")
        val newUri = Uri.parse("content://test/willfail.md")
        val repo = FakeRepo("base\n", writableUris = mutableSetOf(oldUri), saveSucceeds = false)
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)
        vm.updateContent("unsaved edit\n")

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        assertTrue("failure surfaces SaveError", vm.fileLoadState.value is FileLoadState.SaveError)
        val doc = vm.currentDocument.value!!
        assertEquals("identity stays on the original (not adopted)", oldUri, doc.uri)
        assertEquals("edits are retained in memory", "unsaved edit\n", doc.content)
        assertTrue("document is still dirty", doc.dirty)
        assertTrue("nothing was written", repo.capturedSaves.isEmpty())
    }

    @Test
    fun saveActiveDocumentAsIsNoOpWhenNoDocumentOpen() = runTest {
        val repo = FakeRepo("x\n")
        val vm = vmWith(repo)

        vm.saveActiveDocumentAs(Uri.parse("content://test/n.md"))

        assertNull(vm.currentDocument.value)
        assertTrue(repo.capturedSaves.isEmpty())
    }

    @Test
    fun concurrentSaveGuardBlocksASecondSaveAs() = runTest {
        // Reuses the existing FileLoadState.Saving guard: a Save-As must not start while a save is
        // in flight (two interleaved writes could truncate each other — Safeguard 1).
        val oldUri = Uri.parse("content://test/g.md")
        val newUri1 = Uri.parse("content://test/g1.md")
        val newUri2 = Uri.parse("content://test/g2.md")
        val gate = CompletableDeferred<Unit>()
        val repo = FakeRepo("doc\n", writableUris = mutableSetOf(oldUri, newUri1, newUri2), saveGate = gate)
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)

        vm.saveActiveDocumentAs(newUri1) // enters Saving, parks at the gate
        vm.awaitSaving()
        assertEquals(FileLoadState.Saving, vm.fileLoadState.value)
        vm.saveActiveDocumentAs(newUri2) // must be blocked by the guard
        gate.complete(Unit)
        vm.awaitSaveSettled()

        assertTrue("only the first Save-As wrote", repo.capturedSaves.containsKey(newUri1))
        assertFalse("the guarded second Save-As never wrote", repo.capturedSaves.containsKey(newUri2))
    }

    @Test
    fun transientFlagIsTrueWithoutAWriteGrant() = runTest {
        // A file opened read-only via "Open with" holds no persisted write grant → transient.
        val uri = Uri.parse("content://test/readonly.md")
        val repo = FakeRepo("ro\n") // uri NOT in writableUris
        val vm = vmWith(repo)

        vm.loadFile(uri)
        vm.awaitLoaded(uri)

        assertTrue("no write grant → transient", vm.transient.value)
    }

    @Test
    fun transientFlagIsFalseWithAWriteGrant() = runTest {
        val uri = Uri.parse("content://test/writable.md")
        val repo = FakeRepo("rw\n", writableUris = mutableSetOf(uri))
        val vm = vmWith(repo)

        vm.loadFile(uri)
        vm.awaitLoaded(uri)

        assertFalse("write grant held → not transient", vm.transient.value)
    }

    @Test
    fun saveAsClearsTransientAfterAdoption() = runTest {
        // Save-As of a transient doc to a persistable location → the adopted file is no longer transient.
        val transientUri = Uri.parse("content://test/temp.md")
        val newUri = Uri.parse("content://test/kept.md")
        val repo = FakeRepo("t\n") // transientUri not writable; takePersistableUriPermission grants newUri
        val vm = vmWith(repo)
        vm.loadFile(transientUri)
        vm.awaitLoaded(transientUri)
        assertTrue(vm.transient.value)

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        assertFalse("after adopting a persistable copy, no longer transient", vm.transient.value)
        assertEquals(newUri, vm.currentDocument.value!!.uri)
    }

    @Test
    fun takePersistablePermissionFailureDoesNotAbortTheWrite() = runTest {
        // The created-document URI is already SAF-auto-persisted; takePersistableUriPermission is
        // best-effort and may fail on some providers. Its failure must NOT abort saveFile.
        val oldUri = Uri.parse("content://test/o.md")
        val newUri = Uri.parse("content://test/created.md")
        // newUri pre-seeded writable to model SAF's auto-grant on a created doc, even though the
        // explicit takePersistableUriPermission call is configured to FAIL.
        val repo = FakeRepo(
            "c\n",
            writableUris = mutableSetOf(oldUri, newUri),
            takePermSucceeds = false,
        )
        val vm = vmWith(repo)
        vm.loadFile(oldUri)
        vm.awaitLoaded(oldUri)

        vm.saveActiveDocumentAs(newUri)
        vm.awaitSaveSettled()

        assertTrue("takePersistableUriPermission was attempted", repo.takePermCalls > 0)
        assertEquals("write still succeeded despite the permission failure", "c\n", repo.capturedSaves[newUri])
        assertEquals("identity still adopted", newUri, vm.currentDocument.value!!.uri)
        assertEquals(FileLoadState.SaveSuccess, vm.fileLoadState.value)
    }
}
