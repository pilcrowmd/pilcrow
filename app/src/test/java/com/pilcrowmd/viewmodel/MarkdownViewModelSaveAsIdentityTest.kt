// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree
//
// Both M-147 and M-149 are FIXED, and every assertion here asserts the CORRECT behaviour. This
// suite began life as a characterization of the two defects (PR #164) and was inverted with the
// fixes; the git history of this file is the before/after record.

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
import com.pilcrowmd.storage.RecentFile
import com.pilcrowmd.storage.StorageManager
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

/**
 * CHARACTERIZATION of **M-147** — asserts the SHIPPED (defective) behaviour, not the desired one.
 *
 * `saveActiveDocumentAs` snapshots the document at MarkdownViewModel.kt:806 but stamps the new URI
 * onto whatever is current when the write RETURNS (`_currentDocument.update` at :819). Nothing
 * between those two points adjudicates identity, and the only guard on the path
 * (`if (_fileLoadState.value is FileLoadState.Saving) return@launch`, :805) rejects a second SAVE —
 * `loadFile` never consults it, and in fact CLEARS it (`loadDocumentOrThrow` emits `Loading` at
 * :417 before it does anything else).
 *
 * Two defects, one per row, SEPARATE because their fixes are different — an identity check does
 * not stop the toolbar re-enabling, and an in-flight flag does not stop `loadFile` replacing the
 * document:
 *  1. **M-147** — a load inside the window used to leave document B on screen wearing the
 *     Save-As TARGET's URI, so B's next in-place save overwrote the copy. **The file at risk was
 *     the TARGET, not source A** — A is never written on this path. **FIXED: the adoption is
 *     conditional on an opaque `DocumentId`, and everything derived from identity is gated with
 *     it;**
 *  2. **M-149** — the same load used to re-open every save guard, and the toolbar with it.
 *     **FIXED: `withWriteClaim` holds the claim on `_writeInFlight`, which no load touches.**
 *
 * They share this file on purpose: one interleaving seen from two angles, and splitting it would
 * mean building the same fixture twice.
 *
 * **⚠️ WHAT THIS SUITE DOES NOT MEASURE — read before quoting it on likelihood.** The real
 * `LocalFileRepository` holds a single `journalMutex` across the whole of BOTH `saveFile` and
 * `readFile`, so in the save-first ordering a concurrent load blocks at `readFile` until the write
 * completes. [GatedRepo] has no such lock, which makes that one ordering artificially wide. These
 * tests therefore prove the OUTCOME GIVEN the interleaving; they say nothing about how often the
 * interleaving occurs. M-147 records which orderings the real mutex narrows and which it leaves
 * wide open; M-149 records why the same mutex means neither defect is a byte-corruption defect.
 */
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelSaveAsIdentityTest {

    private val vmStore = ViewModelStore()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("saveas_characterization_test.preferences_pb")
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
     * Per-URI reads (so A and B are genuinely different documents) and a per-CALL write gate, so
     * the test decides when each write returns rather than racing it. Deliberately WITHOUT the
     * production journal mutex — see the class comment.
     */
    private class GatedRepo(
        private val contents: Map<Uri, String>,
        private val saveGates: List<CompletableDeferred<Unit>?>,
    ) : FileRepository {
        val capturedSaves = linkedMapOf<Uri, String>()

        /** Counts ENTRIES to [saveFile], so a caller blocked by a ViewModel guard is distinguishable. */
        var saveCalls = 0
            private set

        override suspend fun readFile(uri: Uri) = Result.success(
            FileText(contents[uri] ?: error("no fixture content for $uri"), isUtf8 = true),
        )

        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            saveGates.getOrNull(saveCalls++)?.await()
            capturedSaves[uri] = content
            return Result.success(Unit)
        }

        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    /**
     * Parks `addRecent` — the LAST call in `saveActiveDocumentAs`'s persistence tail — so the test
     * can stand inside the tail, after the user has been told "Saved", and ask whether the claim
     * is still held. Interface delegation: one override, everything else is the real manager.
     */
    private class GatedStorage(private val delegate: StorageManager, private val tailGate: CompletableDeferred<Unit>) :
        StorageManager by delegate {
        override suspend fun addRecent(file: RecentFile) {
            tailGate.await()
            delegate.addRecent(file)
        }
    }

    private fun vmWith(repo: FileRepository, storageOverride: StorageManager? = null): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = repo,
            storage = storageOverride ?: storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
        vmStore.put("vm", vm)
        return vm
    }

    /** Waits on what the load actually publishes: the document identity, then its terminal state. */
    private suspend fun MarkdownViewModel.awaitLoaded(uri: Uri) {
        currentDocument.first { it?.uri == uri }
        fileLoadState.first { it is FileLoadState.Success }
    }

    /** Waits for a write to have STARTED (parked at its gate), not finished. */
    private suspend fun MarkdownViewModel.awaitSaving() {
        fileLoadState.first { it is FileLoadState.Saving }
    }

    private suspend fun MarkdownViewModel.awaitSaveSettled() {
        fileLoadState.first { it is FileLoadState.SaveError || it is FileLoadState.SaveSuccess }
    }

    /**
     * **M-147, INVERTED.** The stamp must not land on a document other than the one written.
     *
     * This is the DEMONSTRATION case — A and B have different URIs, so it shows the defect's
     * original shape. It is deliberately NOT the test that proves the guard: a plain URI
     * comparison would satisfy it too. That is
     * [saveAsOntoADifferentUNSAVEDDocumentIsRefusedBecauseBothUrisAreNull].
     */
    @Test
    fun aLoadInsideTheSaveAsWindowDoesNotBindTheNewDocumentToTheTarget() = runTest {
        val uriA = Uri.parse("content://test/a-source.md")
        val uriB = Uri.parse("content://test/b-other.md")
        val target = Uri.parse("content://test/target-copy.md")
        val contentA = "AAA the document being copied\n"
        val contentB = "BBB an entirely different document\n"

        // Gate 0 = the Save-As write. Gate 1 = the later in-place save. Both parked on demand.
        val saveAsGate = CompletableDeferred<Unit>()
        val inPlaceGate = CompletableDeferred<Unit>()
        val repo = GatedRepo(
            contents = mapOf(uriA to contentA, uriB to contentB),
            saveGates = listOf(saveAsGate, inPlaceGate),
        )
        val vm = vmWith(repo)

        // A is open and clean — so openFromIntent below takes the immediate-load branch.
        vm.loadFile(uriA)
        vm.awaitLoaded(uriA)
        assertEquals("fixture: A is what is open", contentA, vm.currentDocument.value!!.content)

        // --- the window opens: Save-As snapshots A, then parks inside repository.saveFile ---
        vm.saveActiveDocumentAs(target)
        vm.awaitSaving()

        // --- B becomes current THROUGH THE SHIPPED LOAD PATH while the write is in flight ---
        vm.openFromIntent(uriB)
        vm.awaitLoaded(uriB)
        assertEquals("B is now the displayed document", contentB, vm.currentDocument.value!!.content)
        // ⚠️ A SECOND BARRIER, AND IT IS NOT REDUNDANT — CI caught its absence (run 35583785129).
        // `awaitLoaded` waits for FileLoadState.Success, which `loadDocumentOrThrow` emits BEFORE
        // its persistence tail, deliberately, so the outcome never waits on DataStore. The
        // lastFileUri assertion at the end of this test therefore had no barrier at all: it read
        // whatever had been written by then. Locally the write won the race every time; on CI it
        // lost, and the assertion read uriA. Establishing the precondition HERE — B's own pointer
        // is on disk — is what makes the final assertion mean "the Save-As did not overwrite it"
        // rather than "the Save-As got there first".
        storage.lastFileUri.first { it == uriB }

        // --- the window closes: the write (and displayName) complete ---
        saveAsGate.complete(Unit)
        vm.awaitSaveSettled()

        assertEquals("the copy on disk holds A's bytes, as the user asked", contentA, repo.capturedSaves[target])

        // NO MISBINDING. Both assertions matter: content == B alone would hold even with the
        // adoption deleted entirely, and uri == uriB alone would hold if B had never arrived.
        val displayed = vm.currentDocument.value
        assertNotNull("a document is displayed (no branch may be satisfied by null)", displayed)
        assertEquals("document B is what the user is looking at", contentB, displayed!!.content)
        assertEquals("...and it keeps its OWN URI — the target is not stamped onto it", uriB, displayed.uri)
        assertEquals("...and its own display name", "b-other.md", displayed.displayName)

        // --- the consequence: B's next in-place save goes to B's OWN file, not the copy ---
        // Wait on the CLAIM, not on SaveSuccess: the claim is the operation boundary and
        // SaveSuccess is a message to the user, emitted while the persistence tail is still
        // running. Waiting on the toast would ask for the next save before it can be admitted.
        vm.writeInFlight.first { !it }
        vm.saveFile()
        vm.awaitSaving() // a real transition out of SaveSuccess; the write is parked at its own gate
        inPlaceGate.complete(Unit)
        vm.awaitSaveSettled()

        assertEquals(
            "the Save-As target still holds A's bytes — the copy survives",
            contentA,
            repo.capturedSaves[target],
        )
        assertEquals("B's in-place save went to B's own file", contentB, repo.capturedSaves[uriB])
        assertFalse("source A is never written on this path", repo.capturedSaves.containsKey(uriA))

        // The `if (adopted)` gate on the derived state, tested where it can actually be decided.
        // Two things make this assertion mean something: the barrier above proved uriB was
        // persisted BEFORE the Save-As was released, so this compares two real URIs; and
        // `writeInFlight.first { !it }` above proved the Save-As tail has finished, so if the
        // gate were missing its `saveLastFileUri(target)` would already have landed. In the
        // two-unsaved fixture below there is no load, `lastFileUri` is unset, and `null == null`
        // would answer before the gate did.
        assertEquals(
            "lastFileUri follows the DISPLAYED document, not the Save-As target",
            uriB,
            storage.lastFileUri.first(),
        )
    }

    /**
     * **M-147 — THE TEST THAT PROVES THE GUARD, and its fixture is not negotiable.**
     *
     * **BOTH documents have `uri == null`.** The A/B case above is decided just as well by a URI
     * comparison, so it would pass against a weaker guard and prove nothing about `DocumentId`.
     * Here `null == null`, so only an opaque per-instance identity can refuse the adoption.
     *
     * This is round 3's own finding — `expectedUri == null` lets Save-As hijack a *different*
     * unsaved document — applied to the test of round 3's own fix, so it cannot repeat.
     */
    @Test
    fun saveAsOntoADifferentUNSAVEDDocumentIsRefusedBecauseBothUrisAreNull() = runTest {
        val target = Uri.parse("content://test/unsaved-target.md")
        val writeGate = CompletableDeferred<Unit>()
        val repo = GatedRepo(contents = emptyMap(), saveGates = listOf(writeGate))
        val vm = vmWith(repo)

        // U1: a brand-new unsaved document with some text.
        vm.newDocument()
        vm.currentDocument.first { it != null }
        vm.updateContent(vm.currentDocument.value!!.id, "first blank document\n")
        val firstId = vm.currentDocument.value!!.id
        assertNull("fixture: U1 has no URI", vm.currentDocument.value!!.uri)

        vm.saveActiveDocumentAs(target)
        vm.awaitSaving()

        // U2: a DIFFERENT unsaved document wins the slot while the write is suspended.
        // U1 is first typed back to empty, i.e. clean: Create MD File no longer replaces a document
        // with unsaved edits (IF_CLEAN, M-109/M-180). The bytes being written were captured when
        // the Save-As started, so this changes nothing about what reaches disk, and the only thing
        // that can refuse the adoption is still the DocumentId comparison.
        vm.updateContent(vm.currentDocument.value!!.id, "")
        vm.currentDocument.first { it?.dirty == false }
        vm.newDocument()
        vm.currentDocument.first { it != null && it.id != firstId }
        val second = vm.currentDocument.value!!
        assertNull("fixture: U2 also has no URI — a URI comparison CANNOT tell them apart", second.uri)

        writeGate.complete(Unit)
        vm.awaitSaveSettled()
        vm.writeInFlight.first { !it }

        val displayed = vm.currentDocument.value!!
        assertEquals("the slot still holds U2", second.id, displayed.id)
        assertNull("U2 did NOT adopt the target's URI, though null == null", displayed.uri)
        assertEquals("U1's text is what reached disk", "first blank document\n", repo.capturedSaves[target])
        assertEquals(
            "the outcome is still SaveSuccess — the bytes did reach disk",
            FileLoadState.SaveSuccess,
            vm.fileLoadState.value,
        )
    }

    /**
     * **M-149, INVERTED.** The same call, made twice against the same in-flight write, with only a
     * load in between. It must be refused BOTH times: the claim lives on `_writeInFlight` and no
     * load can reach it. Before the fix the second call got through, because the load's `Loading`
     * emit had replaced `Saving` on the shared state the guard was reading.
     */
    @Test
    fun aLoadInsideTheWriteWindowDoesNotReopenTheConcurrentSaveGuard() = runTest {
        val uriA = Uri.parse("content://test/guard-a.md")
        val uriB = Uri.parse("content://test/guard-b.md")
        val target = Uri.parse("content://test/guard-target.md")
        val contentA = "AAA guarded\n"
        val contentB = "BBB guarded\n"

        val saveAsGate = CompletableDeferred<Unit>()
        val secondWriteGate = CompletableDeferred<Unit>()
        val repo = GatedRepo(
            contents = mapOf(uriA to contentA, uriB to contentB),
            saveGates = listOf(saveAsGate, secondWriteGate),
        )
        val vm = vmWith(repo)

        vm.loadFile(uriA)
        vm.awaitLoaded(uriA)

        vm.saveActiveDocumentAs(target) // parks inside repository.saveFile, holding `Saving`
        vm.awaitSaving()
        assertEquals("fixture: exactly one write has been entered", 1, repo.saveCalls)

        // BEFORE: the guard does its job. `saveFile()` runs inline to its `return@launch` under the
        // unconfined dispatcher — it never reaches the repository, so the counter cannot move.
        vm.saveFile()
        assertEquals("guard CLOSED while Saving: the second write is refused", 1, repo.saveCalls)

        // The only thing that changes between the two attempts. Before M-149 this re-opened the
        // guard; the claim is not on FileLoadState any more, so it must not.
        vm.openFromIntent(uriB)
        vm.awaitLoaded(uriB)

        // AFTER: the identical call is STILL refused. `doc.uri` is uriB and non-null here, so
        // nothing cheaper than the claim can be turning it away.
        vm.saveFile()
        assertEquals("guard STILL CLOSED after the load: the write is refused again", 1, repo.saveCalls)

        saveAsGate.complete(Unit)
        vm.awaitSaveSettled()
        assertEquals(
            "the ONE admitted write is the Save-As, and it wrote A's bytes",
            contentA,
            repo.capturedSaves[target],
        )
        assertFalse("the refused writes never reached the repository", repo.capturedSaves.containsKey(uriB))
    }

    /**
     * **M-149 — THE RELEASE TEST. MANDATORY, and it tests WHERE the release happens, not merely
     * that one happens.**
     *
     * Every other assertion in this suite is a REFUSAL, and a claim that is never released
     * satisfies all of them — a leaked claim would make the suite GREENER while bricking every
     * save for the rest of the session, which is worse than the bug being fixed.
     *
     * A naive release test would not be enough either: a claim released at the terminal emit
     * passes "the next save is allowed" while leaving the persistence tail unguarded, which is
     * the M-145 shape the rule exists to prevent. So this test stands INSIDE the tail — after
     * `SaveSuccess`, before `addRecent` returns — and asserts the claim is still held there.
     *
     * Proved by two mutations, which fail on two DIFFERENT assertions:
     *  (a) delete the `finally` release          -> "released once the tail returns" fails;
     *  (b) release at the terminal emit instead  -> "still held while the tail runs" fails.
     */
    @Test
    fun theClaimIsReleasedAfterThePersistenceTailNotAtTheTerminalEmit() = runTest {
        val uriA = Uri.parse("content://test/tail-a.md")
        val target = Uri.parse("content://test/tail-target.md")
        val writeGate = CompletableDeferred<Unit>()
        val tailGate = CompletableDeferred<Unit>()
        val repo = GatedRepo(
            contents = mapOf(uriA to "a\n"),
            saveGates = listOf(writeGate), // the later in-place saves are ungated
        )
        val vm = vmWith(repo, storageOverride = GatedStorage(storage, tailGate))
        vm.loadFile(uriA)
        vm.awaitLoaded(uriA)

        vm.saveActiveDocumentAs(target)
        vm.awaitSaving()
        assertEquals("fixture: one write admitted", 1, repo.saveCalls)
        writeGate.complete(Unit)

        // We are now INSIDE the tail: the write returned, the user has been told "Saved", and the
        // coroutine is parked in addRecent. A real transition — the state was Saving until now.
        vm.fileLoadState.first { it is FileLoadState.SaveSuccess }
        assertTrue("(b) the claim is STILL HELD while the persistence tail runs", vm.writeInFlight.value)
        vm.saveFile()
        assertEquals("a save is refused while the tail is still running", 1, repo.saveCalls)

        // Only the tail returning may release it. A REAL barrier on the claim, not an assertion on
        // the line after: `addRecent` delegates to a DataStore write on Dispatchers.IO, so
        // completing the gate does NOT finish the tail inline. Under mutation (a) this barrier is
        // never satisfied and the test fails on runTest's timeout — which is exactly what a claim
        // that is never released looks like.
        tailGate.complete(Unit)
        vm.writeInFlight.first { !it }
        vm.saveFile()
        assertEquals("(a) the next save is admitted once the tail has RETURNED", 2, repo.saveCalls)
    }
}
