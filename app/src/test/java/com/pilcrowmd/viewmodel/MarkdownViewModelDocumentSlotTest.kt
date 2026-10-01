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
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * **The document slot: who may replace the open document, and when** (M-109, M-110, M-152, M-180,
 * the `saveAndClose` half of M-152, and the restore no longer being joined).
 *
 * Every read and every write in the fake repository can be parked on its own gate, so each
 * interleaving here is CHOSEN, not hoped for: a fake that returns inline finishes the load before
 * the competing action runs, and the assertion then passes against the racy code (M-109's first
 * trap). Each test was seen failing with the guard it names removed; see the PR for the mutations.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelDocumentSlotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val aUri = Uri.parse("content://t/a.md")
    private val bUri = Uri.parse("content://t/b.md")
    private val cUri = Uri.parse("content://t/c.md")

    private class GatedRepo(private val contents: Map<Uri, String>) : FileRepository {
        /** A read of that uri parks here until the test completes the gate. */
        val readGates = ConcurrentHashMap<Uri, CompletableDeferred<Unit>>()

        /** Like [readGates], but the park ignores cancellation — a blocking ContentResolver read. */
        val uninterruptibleReadGates = ConcurrentHashMap<Uri, CompletableDeferred<Unit>>()

        /** Every write parks here until the test completes it, when set. */
        @Volatile var saveGate: CompletableDeferred<Unit>? = null
        val reads = ConcurrentHashMap<Uri, Int>()
        val saves = ConcurrentHashMap<Uri, String>()

        override suspend fun readFile(uri: Uri): Result<FileText> {
            reads.merge(uri, 1, Int::plus)
            readGates[uri]?.await()
            uninterruptibleReadGates[uri]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            return contents[uri]?.let { Result.success(FileText(it, isUtf8 = true)) }
                ?: Result.failure(IOException("unreadable: $uri"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            saveGate?.await()
            saves[uri] = content
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

    private fun defaultRepo() = GatedRepo(mapOf(aUri to "alpha\n", bUri to "bravo\n", cUri to "charlie\n"))

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("document_slot_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(repo: FileRepository): MarkdownViewModel {
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
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(message, condition())
    }

    /** Let queued work run for a while. Only ever used to show that something did NOT happen. */
    private fun settle() = repeat(SETTLE_ROUNDS) {
        idle()
        Thread.sleep(POLL_MS)
    }

    private fun MarkdownViewModel.openAndAwait(uri: Uri) {
        loadFile(uri)
        awaitTrue("$uri never finished loading") {
            currentDocument.value?.uri == uri && fileLoadState.value == FileLoadState.Success
        }
    }

    private fun MarkdownViewModel.typeAndAwait(text: String) {
        updateContent(currentDocument.value!!.id, text)
        awaitTrue("the edit never registered") { currentDocument.value?.content == text }
    }

    // ── M-109 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a startup restore that finishes after Create MD File does not replace the typed document`() {
        val repo = defaultRepo().apply { readGates[bUri] = CompletableDeferred() }
        runBlocking { storage.saveLastFileUri(bUri) }
        val vm = vm(repo)
        awaitTrue("the restore never started reading") { repo.reads[bUri] == 1 }

        vm.newDocument()
        awaitTrue("the new document never appeared") { vm.currentDocument.value?.isUnsaved == true }
        vm.typeAndAwait("typed while the restore was loading")

        repo.readGates.getValue(bUri).complete(Unit)
        settle()

        assertTrue("the restore replaced the new document", vm.currentDocument.value?.isUnsaved == true)
        assertEquals("typed while the restore was loading", vm.currentDocument.value?.content)
        assertFalse("the restore left Loading on screen", vm.fileLoadState.value is FileLoadState.Loading)
    }

    @Test
    fun `a startup restore that finishes after Create MD File does not replace even an untouched new document`() {
        // The typed test above cannot prove the claim ordering: its document is dirty, so the
        // cheaper IF_CLEAN check refuses the restore first. Here the new document is clean, so
        // only "the operation started last wins" can keep it on screen.
        val repo = defaultRepo().apply { readGates[bUri] = CompletableDeferred() }
        runBlocking { storage.saveLastFileUri(bUri) }
        val vm = vm(repo)
        awaitTrue("the restore never started reading") { repo.reads[bUri] == 1 }

        vm.newDocument()
        awaitTrue("the new document never appeared") { vm.currentDocument.value?.isUnsaved == true }
        repo.readGates.getValue(bUri).complete(Unit)
        settle()

        assertTrue(
            "the restore replaced the document the user created after it",
            vm.currentDocument.value?.isUnsaved == true,
        )
    }

    @Test
    fun `of two explicit opens the one started last wins, whichever finishes last`() {
        val repo = defaultRepo().apply { readGates[bUri] = CompletableDeferred() }
        val vm = vm(repo)

        vm.loadFile(bUri) // parks in its read
        awaitTrue("B never started reading") { repo.reads[bUri] == 1 }
        vm.openAndAwait(cUri) // started later, finishes first

        repo.readGates.getValue(bUri).complete(Unit)
        settle()

        assertEquals("B, which started first, replaced C", cUri, vm.currentDocument.value?.uri)
        assertEquals("the remembered file is not what is on screen", cUri, runBlocking { storage.lastFileUri.first() })
    }

    // ── M-180 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `text typed while a warm open is loading is kept, and the open asks instead`() {
        val repo = defaultRepo()
        val vm = vm(repo)
        vm.openAndAwait(aUri)
        repo.readGates[bUri] = CompletableDeferred()

        vm.openFromIntent(bUri) // A is clean, so this loads straight away
        awaitTrue("B never started reading") { repo.reads[bUri] == 1 }
        vm.typeAndAwait("alpha, edited during the load\n")

        repo.readGates.getValue(bUri).complete(Unit)
        awaitTrue("the open never asked") { vm.pendingOpenUri.value == bUri }

        assertEquals("the typed document was replaced", aUri, vm.currentDocument.value?.uri)
        assertEquals("alpha, edited during the load\n", vm.currentDocument.value?.content)
        assertTrue("the edits were marked clean", vm.currentDocument.value?.dirty == true)
        assertFalse("the load left Loading on screen", vm.fileLoadState.value is FileLoadState.Loading)
    }

    @Test
    fun `Discard on the open prompt does replace the edited document`() {
        val repo = defaultRepo()
        val vm = vm(repo)
        vm.openAndAwait(aUri)
        vm.typeAndAwait("alpha, edited\n")
        vm.openFromIntent(bUri)
        awaitTrue("the dirty document did not ask") { vm.pendingOpenUri.value == bUri }

        vm.discardAndOpenPending()
        awaitTrue("Discard did not open B") { vm.currentDocument.value?.uri == bUri }
    }

    // ── M-110 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `save-then-open on an edited unsaved document keeps it and does not start the open`() {
        val repo = defaultRepo()
        val vm = vm(repo)
        vm.newDocument()
        awaitTrue("the new document never appeared") { vm.currentDocument.value?.isUnsaved == true }
        vm.typeAndAwait("never saved\n")
        vm.openFromIntent(bUri)
        awaitTrue("the dirty document did not ask") { vm.pendingOpenUri.value == bUri }

        vm.saveAndOpenPending() // the View routes this case to Save-As; the ViewModel must hold anyway
        settle()

        assertEquals("never saved\n", vm.currentDocument.value?.content)
        assertEquals("the pending open was dropped", bUri, vm.pendingOpenUri.value)
        // The early return's own observable: the cheaper IF_CLEAN guard would also keep the
        // document, so only this line can tell whether the open was started at all.
        assertNull("the open was started for a document with nothing to save", repo.reads[bUri])
    }

    // ── M-152 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a keystroke during save-then-open keeps the document dirty when the open then fails`() {
        val repo = GatedRepo(mapOf(aUri to "alpha\n")) // B cannot be read
        val vm = vm(repo)
        vm.openAndAwait(aUri)
        vm.typeAndAwait("alpha, saved\n")
        vm.openFromIntent(bUri)
        awaitTrue("the dirty document did not ask") { vm.pendingOpenUri.value == bUri }
        repo.saveGate = CompletableDeferred()

        vm.saveAndOpenPending()
        awaitTrue("the save never started") { vm.fileLoadState.value == FileLoadState.Saving }
        vm.typeAndAwait("alpha, saved, and then one more keystroke\n")
        repo.saveGate!!.complete(Unit)
        awaitTrue("the save never finished") { repo.saves[aUri] != null && !vm.writeInFlight.value }
        settle()

        assertEquals("alpha, saved\n", repo.saves[aUri])
        assertEquals("alpha, saved, and then one more keystroke\n", vm.currentDocument.value?.content)
        assertTrue("an unwritten keystroke was marked saved (M-152)", vm.currentDocument.value?.dirty == true)
    }

    @Test
    fun `a keystroke during save-then-open brings the open prompt back instead of dropping it`() {
        val repo = defaultRepo()
        val vm = vm(repo)
        vm.openAndAwait(aUri)
        vm.typeAndAwait("alpha, saved\n")
        vm.openFromIntent(bUri)
        awaitTrue("the dirty document did not ask") { vm.pendingOpenUri.value == bUri }
        repo.saveGate = CompletableDeferred()

        vm.saveAndOpenPending()
        awaitTrue("the save never started") { vm.fileLoadState.value == FileLoadState.Saving }
        vm.typeAndAwait("alpha, saved, plus one\n")
        repo.saveGate!!.complete(Unit)
        awaitTrue("the open prompt did not come back") { vm.pendingOpenUri.value == bUri && repo.reads[bUri] == 1 }

        assertEquals("the edited document was replaced", aUri, vm.currentDocument.value?.uri)
        assertEquals("alpha, saved, plus one\n", vm.currentDocument.value?.content)
    }

    // ── saveAndClose, and the close split ─────────────────────────────────────────────────────

    @Test
    fun `a keystroke during save-then-close keeps the document open, reports Saved and asks`() {
        val repo = defaultRepo()
        val vm = vm(repo)
        vm.openAndAwait(aUri)
        vm.typeAndAwait("alpha, saved\n")
        repo.saveGate = CompletableDeferred()

        vm.saveAndClose()
        awaitTrue("the save never started") { vm.fileLoadState.value == FileLoadState.Saving }
        vm.typeAndAwait("alpha, saved, plus one\n")
        repo.saveGate!!.complete(Unit)
        awaitTrue("the close prompt was not raised") { vm.closeNeedsConfirm.value }

        assertEquals("the document was closed over an unwritten keystroke", aUri, vm.currentDocument.value?.uri)
        assertEquals("alpha, saved\n", repo.saves[aUri])
        assertEquals("the write succeeded, so it must say so", FileLoadState.SaveSuccess, vm.fileLoadState.value)
    }

    @Test
    fun `close refuses an edited document and asks, and discardAndClose closes it`() {
        val vm = vm(defaultRepo())
        vm.openAndAwait(aUri)
        vm.typeAndAwait("alpha, edited\n")

        vm.closeFile()
        awaitTrue("the close prompt was not raised") { vm.closeNeedsConfirm.value }
        assertEquals("closeFile discarded unsaved edits", aUri, vm.currentDocument.value?.uri)

        vm.consumeCloseNeedsConfirm()
        vm.discardAndClose()
        awaitTrue("discardAndClose did not close") { vm.currentDocument.value == null }
        assertFalse(vm.closeNeedsConfirm.value)
    }

    // ── The restore is cancelled, not joined, and its reset cannot land on a newer load ────────

    @Test
    fun `an explicit open does not wait for the restore's read, and the restore cannot end its Loading`() {
        val repo = defaultRepo().apply {
            // The restore's read cannot be interrupted, like a blocking ContentResolver read.
            uninterruptibleReadGates[bUri] = CompletableDeferred()
            readGates[cUri] = CompletableDeferred()
        }
        runBlocking { storage.saveLastFileUri(bUri) }
        val vm = vm(repo)
        awaitTrue("the restore never started reading") { repo.reads[bUri] == 1 }

        vm.loadFile(cUri)
        // Joining the restore would park C behind the restore's read, which is never released
        // before this line: C's own read starting is the proof that C did not wait.
        awaitTrue("the explicit open waited for the restore's read") { repo.reads[cUri] == 1 }

        // The restore now finishes while C is still Loading. Its cancelled load must not end C's
        // Loading: only the load that owns it may.
        repo.uninterruptibleReadGates.getValue(bUri).complete(Unit)
        settle()
        assertEquals(
            "the cancelled restore ended a newer load's Loading",
            FileLoadState.Loading,
            vm.fileLoadState.value,
        )

        repo.readGates.getValue(cUri).complete(Unit)
        awaitTrue("C never opened") {
            vm.currentDocument.value?.uri == cUri && vm.fileLoadState.value == FileLoadState.Success
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_ROUNDS = 40
    }
}
