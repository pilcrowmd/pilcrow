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
import com.pilcrowmd.repository.ByteFingerprint
import com.pilcrowmd.repository.Draft
import com.pilcrowmd.repository.DraftRepository
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.LocalDraftRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.testing.MainDispatcherSuite
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crash recovery end to end through the real ViewModel (byte fidelity, the stale-file rule and
 * clearing). A real [LocalDraftRepository] over a real folder; the file is a fake that records
 * every save.
 *
 * **"Kill" is a second ViewModel over the same draft folder and the same fake file.** The first one
 * is simply left behind; its state is gone, only what it wrote to the draft folder is left.
 *
 * **Clock.** Main is a test dispatcher on a scheduler of its own that only moves when a test moves it,
 * so the 1.5 s debounce NEVER fires by itself here: a draft that exists was written by `flushDraft()`
 * (the ON_STOP path) or by a test that advanced the clock on purpose. [debounceAloneWritesTheDraftInRealTime]
 * is the one test that waits real time for it.
 *
 * **Barriers.** Every wait is on state the operation publishes (the offer, the document, the save
 * outcome, the draft file appearing or disappearing), and each is a state that does not hold before
 * the operation: the offer starts null, the draft file starts absent or present as the test arranges
 * it, the mode starts as the reader.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Category(MainDispatcherSuite::class)
class MarkdownViewModelDraftRecoveryTest {

    private val vmStore = ViewModelStore()
    private var vmSeq = 0

    /** Registers [vm] so teardown cancels its `viewModelScope` before `resetMain()` (see the neighbours). */
    private fun track(vm: MarkdownViewModel): MarkdownViewModel {
        vmStore.put("vm-${vmSeq++}", vm)
        return vm
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storageScope: CoroutineScope
    private lateinit var draftDir: File
    private lateinit var scheduler: TestCoroutineScheduler
    private var realMain: java.util.concurrent.ExecutorService? = null
    private var storageSeq = 0
    private val files = FakeFiles()

    private val uri = Uri.parse("content://test/notes.md")

    @Before
    fun setup() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        draftDir = tempFolder.newFolder("nobackup")
    }

    @After
    fun tearDown() {
        vmStore.clear() // cancels every viewModelScope BEFORE the global is released
        storageScope.cancel()
        Dispatchers.resetMain()
        realMain?.shutdownNow()
    }

    /** The file as the fake repository holds it, and every save it was asked for. */
    private class FakeFiles : FileRepository {
        val texts = ConcurrentHashMap<Uri, String>()
        val saves = CopyOnWriteArrayList<Pair<Uri, String>>()

        /** Files that were deleted or moved: they no longer open. */
        val missing: MutableSet<Uri> = ConcurrentHashMap.newKeySet()

        @Volatile
        var saveFails = false

        override suspend fun readFile(uri: Uri): Result<FileText> = if (uri in missing) {
            Result.failure(IOException("no such file (test)"))
        } else {
            Result.success(FileText(texts[uri] ?: "", isUtf8 = true))
        }

        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            if (saveFails) return Result.failure(IOException("disk full (test)"))
            saves += uri to content
            texts[uri] = content
            return Result.success(Unit)
        }

        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    /** The real store, counting the calls that put text on disk. */
    private class CountingDrafts(private val inner: DraftRepository) : DraftRepository by inner {
        val writes = AtomicInteger()
        val detached = AtomicInteger()

        override suspend fun write(draft: Draft): Result<Unit> {
            writes.incrementAndGet()
            return inner.write(draft)
        }

        override fun writeDetached(draft: Draft) {
            detached.incrementAndGet()
            inner.writeDetached(draft)
        }
    }

    /** The real store, but the first lookup of [slowUri]'s draft waits at a gate this test opens. */
    private class GatedDrafts(private val inner: DraftRepository, private val slowUri: Uri) : DraftRepository by inner {
        val lookupStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val lookupFinished = CompletableDeferred<Unit>()

        override suspend fun read(uri: Uri): Draft? {
            if (uri != slowUri || lookupStarted.isCompleted) return inner.read(uri)
            lookupStarted.complete(Unit)
            gate.await()
            return inner.read(uri).also { lookupFinished.complete(Unit) }
        }
    }

    private fun newStorage(): LocalStorageManager {
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("prefs-${storageSeq++}.preferences_pb")
        }
        return LocalStorageManager(context, dataStore)
    }

    /** A new "process": its own draft repository over the shared folder, its own ViewModel. */
    private fun newVm(
        drafts: DraftRepository = LocalDraftRepository(draftDir),
        storage: LocalStorageManager = newStorage(),
    ): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return track(
            MarkdownViewModel(
                repository = files,
                storage = storage,
                parseHeadingsUseCase = parseHeadings,
                searchUseCase = SearchMarkdownUseCase(parseHeadings),
                pdfExporter = mockk(relaxed = true),
                appInfo = object : AppInfo {
                    override val versionName = "test"
                },
                cpuDispatcher = UnconfinedTestDispatcher(scheduler),
                drafts = drafts,
            ),
        )
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun draftFiles(): List<File> =
        File(draftDir, "drafts").listFiles { f -> f.name.endsWith(".draft") }.orEmpty().toList()

    /** A test body on the real clock, bounded so a barrier that never opens fails instead of hanging the build. */
    private fun inRealTime(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000, body) }

    /** Wait (real time, bounded) for [condition]; fails with [what] if it never holds. */
    private suspend fun poll(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out after $timeoutMs ms waiting for $what" }
            delay(20)
        }
    }

    private suspend fun MarkdownViewModel.awaitOpen(target: Uri = uri): Document =
        currentDocument.first { it?.uri == target }!!

    private suspend fun MarkdownViewModel.awaitSaveSuccess() {
        fileLoadState.first { it is FileLoadState.SaveSuccess }
    }

    /** Type [text] into the open document of [vm] without a pause, as the editor would. */
    private fun MarkdownViewModel.type(text: String) {
        updateContent(currentDocument.value!!.id, text)
    }

    /**
     * The first process: open [target], type [text], and go to the background, which is what writes the
     * draft. Waits for the draft file to appear. `flushDraft()` is repeated until it does because the
     * keeper holds the file while it checks whether a draft is already stored (a no-op, by design),
     * and nothing is published when that check finds none.
     */
    private suspend fun keepDraft(target: Uri, text: String): MarkdownViewModel {
        val vm = newVm()
        vm.loadFile(target)
        vm.awaitOpen(target)
        vm.type(text)
        val before = draftFiles().size
        poll("the draft of $target to be written") {
            vm.flushDraft()
            draftFiles().size > before
        }
        return vm
    }

    /** The second process: opens [target] and waits for the offer, which starts out null. */
    private suspend fun reopenWithOffer(
        target: Uri = uri,
        vm: MarkdownViewModel = newVm(),
    ): Pair<MarkdownViewModel, RecoveryOffer> {
        vm.loadFile(target)
        val offer = vm.recoveryOffer.first { it != null }!!
        return vm to offer
    }

    // ── (a) the main path ──────────────────────────────────────────────────────────────────────

    @Test
    fun `after a kill the draft is offered and restored, and the file is untouched until Save`() = inRealTime {
        files.texts[uri] = "original\n"
        val typed = "original\ntyped after the last save\n"
        keepDraft(uri, typed)
        assertTrue("the file was not written by keeping a draft", files.saves.isEmpty())

        val (vm, offer) = reopenWithOffer()

        assertFalse("the file is what the edits were typed on", offer.diskChanged)
        assertEquals(uri, offer.uri)
        val onScreen = vm.currentDocument.value
        assertEquals("the document on screen is the file as it is on disk", "original\n", onScreen?.content)
        assertFalse(vm.currentDocument.value!!.dirty)

        vm.restoreDraft()
        vm.recoveryOffer.first { it == null }
        vm.mode.first { it == ViewMode.EDITOR }

        val restored = vm.currentDocument.value!!
        assertEquals("the restored text is what was typed", typed, restored.content)
        assertEquals(sha256(typed), sha256(restored.content))
        assertTrue("restored text is unsaved", restored.dirty)
        assertTrue("Restore wrote nothing to the file", files.saves.isEmpty())
        assertEquals("and the file still holds its own text", "original\n", files.texts[uri])
        assertEquals("the draft stays until the user saves or discards", 1, draftFiles().size)

        vm.saveFile()
        vm.awaitSaveSuccess()

        assertEquals(listOf(uri to typed), files.saves.toList())
        poll("the draft to be removed by the save") { draftFiles().isEmpty() }
    }

    @Test
    fun `a cold start restores the last file and offers its draft`() = inRealTime {
        // What really happens after a kill: nobody taps the file, the startup restore opens it.
        files.texts[uri] = "original\n"
        val storage = newStorage()
        val first = newVm(storage = storage)
        first.loadFile(uri)
        first.awaitOpen()
        first.type("original\ntyped\n")
        poll("the draft to be written") {
            first.flushDraft()
            draftFiles().isNotEmpty()
        }
        // The last-file pointer is written after the load, so wait for it.
        withTimeout(5_000) { storage.lastFileUri.first { it == uri } }

        val second = newVm(storage = storage)
        val offer = second.recoveryOffer.first { it != null }!!

        assertEquals(uri, offer.uri)
        assertFalse(offer.diskChanged)
        assertEquals(uri, second.currentDocument.value?.uri)
    }

    @Test
    fun `the draft is written by the debounce alone, 1500 ms after the last keystroke, in real time`() {
        // The only test that waits real time. Main is a real single thread with a real clock, and
        // nothing calls flushDraft(), so the file can only appear because the keeper's delay ran out.
        val executor = Executors.newSingleThreadScheduledExecutor()
        realMain = executor
        Dispatchers.setMain(executor.asCoroutineDispatcher())
        files.texts[uri] = "original\n"

        inRealTime {
            val vm = MarkdownViewModel(
                repository = files,
                storage = newStorage(),
                parseHeadingsUseCase = ParseMarkdownHeadingsUseCase(),
                searchUseCase = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()),
                pdfExporter = mockk(relaxed = true),
                appInfo = object : AppInfo {
                    override val versionName = "test"
                },
                drafts = LocalDraftRepository(draftDir),
            ).also { track(it) }
            vm.loadFile(uri)
            vm.awaitOpen()
            assertTrue(draftFiles().isEmpty())

            val typedAt = System.nanoTime()
            vm.type("original\nstill typing\n")
            delay(300)
            assertTrue("300 ms of quiet is not enough", draftFiles().isEmpty())
            poll("the debounce to write the draft", timeoutMs = 4_000) { draftFiles().isNotEmpty() }
            val waitedMs = (System.nanoTime() - typedAt) / 1_000_000

            assertTrue("written after the quiet period, not at once (waited $waitedMs ms)", waitedMs >= 1_000)
            assertTrue("and the file was never written", files.saves.isEmpty())
        }
    }

    // ── (b) discard ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Discard removes the draft and leaves the document clean`() = inRealTime {
        files.texts[uri] = "original\n"
        keepDraft(uri, "original\ntyped\n")
        assertEquals(1, draftFiles().size)
        val (vm, _) = reopenWithOffer()

        vm.discardDraft()
        vm.recoveryOffer.first { it == null }

        assertTrue("the draft is gone", draftFiles().isEmpty())
        assertEquals("original\n", vm.currentDocument.value?.content)
        assertFalse(vm.currentDocument.value!!.dirty)
        assertTrue(files.saves.isEmpty())
    }

    // ── (c) save a copy ────────────────────────────────────────────────────────────────────────

    @Test
    fun `Save a copy writes the kept text to the new file with the line ending the file had`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        files.texts[uri] = "one\r\ntwo\r\n"
        keepDraft(uri, "one\ntwo\nthree\n")
        val (vm, _) = reopenWithOffer()
        assertEquals("loaded as LF", "one\ntwo\n", vm.currentDocument.value?.content)

        vm.saveDraftAsCopy(target)
        vm.awaitSaveSuccess()

        assertEquals(listOf(target to "one\r\ntwo\r\nthree\r\n"), files.saves.toList())
        assertTrue("the draft is gone once the copy is safe", draftFiles().isEmpty())
        assertNull(vm.recoveryOffer.value)
        assertEquals("the open document is still the original, clean", "one\ntwo\n", vm.currentDocument.value?.content)
        assertFalse(vm.currentDocument.value!!.dirty)
        assertEquals("the original file was not written", "one\r\ntwo\r\n", files.texts[uri])
    }

    @Test
    fun `a copy that fails to save keeps the draft`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        files.texts[uri] = "original\n"
        keepDraft(uri, "original\ntyped\n")
        val (vm, _) = reopenWithOffer()
        files.saveFails = true

        vm.saveDraftAsCopy(target)
        vm.fileLoadState.first { it is FileLoadState.SaveError }

        assertEquals("the draft is the user's only copy", 1, draftFiles().size)
        assertNotNull("and the question is still open", vm.recoveryOffer.value)
    }

    // ── (d) the file changed meanwhile ─────────────────────────────────────────────────────────

    @Test
    fun `a file changed by another app after the draft is offered with the warning`() = inRealTime {
        // Three different strings: what the file was, what was typed, what the file is now. Only the
        // comparison of the file's bytes with the draft's baseline can say it changed.
        files.texts[uri] = "original\n"
        keepDraft(uri, "original\ntyped\n")
        files.texts[uri] = "changed in another app\n"

        val (vm, offer) = reopenWithOffer()

        assertTrue(offer.diskChanged)
        assertEquals("changed in another app\n", vm.currentDocument.value?.content)
        assertTrue("offering writes nothing", files.saves.isEmpty())
        assertEquals("changed in another app\n", files.texts[uri])
        assertEquals("the draft is kept for the user to decide", 1, draftFiles().size)
    }

    // ── (e) T7 byte fidelity ───────────────────────────────────────────────────────────────────

    private class Fixture(val name: String, val onDisk: String, val typed: String, val savedAs: String)

    @Test
    fun `restored text is the typed text to the byte, and Save keeps the line ending`() = inRealTime {
        val fixtures = listOf(
            Fixture("CRLF file", "one\r\ntwo\r\n", "one\ntwo\nthree\n", "one\r\ntwo\r\nthree\r\n"),
            Fixture("LF file", "one\ntwo\n", "one\ntwo\nthree\n", "one\ntwo\nthree\n"),
            Fixture("UTF-8 BOM", "﻿one\n", "﻿one\ntwo\n", "﻿one\ntwo\n"),
            Fixture("no trailing newline", "one", "one\ntwo", "one\ntwo"),
            Fixture("astral emoji", "a\n", "a 😀 👩‍💻\n", "a 😀 👩‍💻\n"),
            Fixture("lone CR in an LF file", "a\n", "a\rb\n", "a\rb\n"),
            Fixture("lone CR in a CRLF file", "a\r\nb\r\n", "a\rb\nc\n", "a\rb\r\nc\r\n"),
            Fixture("pasted CRLF in an LF file", "a\n", "a\nb\r\nc\n", "a\nb\r\nc\n"),
        )
        fixtures.forEachIndexed { i, f ->
            val target = Uri.parse("content://test/fidelity$i.md")
            files.texts[target] = f.onDisk
            keepDraft(target, f.typed)

            val (vm, _) = reopenWithOffer(target)
            vm.restoreDraft()
            vm.recoveryOffer.first { it == null }

            assertEquals("${f.name}: restored text", sha256(f.typed), sha256(vm.currentDocument.value!!.content))
            assertEquals("${f.name}: restored text, as a string", f.typed, vm.currentDocument.value!!.content)

            files.saves.clear()
            vm.saveFile()
            vm.awaitSaveSuccess()

            assertEquals("${f.name}: bytes saved", sha256(f.savedAs), sha256(files.saves.single().second))
            assertEquals("${f.name}: saved to its own file", target, files.saves.single().first)
        }
    }

    @Test
    fun `the load path keeps a byte order mark as the first character`() = inRealTime {
        // Settles the BOM open point: a BOM read as U+FEFF stays the first character of the
        // editor's text and of the draft, and goes back to disk as the same three bytes.
        files.texts[uri] = "﻿one\n"
        val vm = newVm()
        vm.loadFile(uri)

        assertEquals('﻿', vm.awaitOpen().content.first())
    }

    // ── (f) the stored draft is not overwritten while the user decides ─────────────────────────

    @Test
    fun `typing under the offer writes no draft, and after the decision it does`() = inRealTime {
        files.texts[uri] = "original\n"
        keepDraft(uri, "original\nthe kept text\n")
        val drafts = CountingDrafts(LocalDraftRepository(draftDir))
        val (vm, _) = reopenWithOffer(vm = newVm(drafts = drafts))

        vm.type("original\ntyped while the dialog is open\n")
        scheduler.advanceTimeBy(60_000) // far past the debounce and the cap
        scheduler.runCurrent()
        vm.flushDraft() // and the background path

        assertEquals("no timed write", 0, drafts.writes.get())
        assertEquals("no write on going to the background", 0, drafts.detached.get())
        assertEquals(
            "the stored draft is the one the user is being asked about",
            "original\nthe kept text\n",
            drafts.read(uri)?.text,
        )

        vm.discardDraft()
        vm.recoveryOffer.first { it == null }
        // The release that follows the decision runs a moment after the offer clears, on another thread,
        // so move the clock until the write it schedules has been made.
        poll("typing to be kept again after the decision") {
            scheduler.advanceTimeBy(1_500)
            scheduler.runCurrent()
            drafts.writes.get() > 0
        }

        assertEquals("one write, of the text typed under the dialog", 1, drafts.writes.get())
        poll("that draft to be on disk") { drafts.writes.get() == 1 && draftFiles().isNotEmpty() }
        assertEquals("original\ntyped while the dialog is open\n", drafts.read(uri)?.text)
    }

    @Test
    fun `an offer found for a file that was replaced meanwhile is not shown over the new file`() = inRealTime {
        val fileA = Uri.parse("content://test/a.md")
        files.texts[uri] = "original\n"
        files.texts[fileA] = "the other file\n"
        keepDraft(uri, "original\ntyped\n")
        assertEquals("fixture: a draft is stored for the first file", 1, draftFiles().size)
        val drafts = GatedDrafts(LocalDraftRepository(draftDir), slowUri = uri)
        val vm = newVm(drafts = drafts)

        vm.loadFile(uri)
        withTimeout(5_000) { drafts.lookupStarted.await() } // the lookup for the first file is parked
        vm.loadFile(fileA)
        vm.awaitOpen(fileA)
        assertNull("fixture: no offer yet", vm.recoveryOffer.value)

        drafts.gate.complete(Unit) // the lookup for the first file finishes, after the second is open
        withTimeout(5_000) { drafts.lookupFinished.await() }
        delay(500) // nothing publishes "no offer", so the end of the lookup is waited for in real time

        assertEquals("the second file is the open one", fileA, vm.currentDocument.value?.uri)
        assertNull("no modal for the first file over the second", vm.recoveryOffer.value)
        assertEquals("the first file's draft is still stored", 1, draftFiles().size)
    }

    // ── (g) a draft equal to the file ──────────────────────────────────────────────────────────

    @Test
    fun `a draft equal to the file is removed silently and no offer appears`() = inRealTime {
        files.texts[uri] = "original\n"
        keepDraft(uri, "original\nsaved by another app\n")
        files.texts[uri] = "original\nsaved by another app\n"

        val vm = newVm()
        vm.loadFile(uri)
        poll("the draft that equals the file to be removed") { draftFiles().isEmpty() }

        assertNull(vm.recoveryOffer.value)
        // The hold is released too: typing is kept again (flush is a no-op while the file is held).
        vm.type("original\nsaved by another app\nand more\n")
        poll("typing to be kept again") {
            vm.flushDraft()
            draftFiles().isNotEmpty()
        }
        assertNull(vm.recoveryOffer.value)
    }

    // ── a failed save ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a save that fails keeps the draft, and the save that works removes it`() = inRealTime {
        files.texts[uri] = "original\n"
        val vm = keepDraft(uri, "original\ntyped\n")
        files.saveFails = true

        vm.saveFile()
        vm.fileLoadState.first { it is FileLoadState.SaveError }

        assertEquals("the draft is still there", 1, draftFiles().size)
        assertTrue("and the document is still unsaved", vm.currentDocument.value!!.dirty)

        files.saveFails = false
        vm.saveFile()
        vm.awaitSaveSuccess()
        poll("the draft to be removed by the save that worked") { draftFiles().isEmpty() }
    }

    // ── (h) drafts whose file can no longer be opened (orphans) ────────────────────────────────

    private val gone = Uri.parse("content://test/gone.md")

    /** A fresh process whose start evaluation has run: the orphan list is awaited only where a test expects entries. */
    private fun startedVm(): MarkdownViewModel = newVm().also {
        it.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)
    }

    @Test
    fun `a draft whose file was deleted is listed with its name and time, and the dialog opens`() = inRealTime {
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone

        val vm = startedVm()
        val orphans = vm.orphanDrafts.first { it.isNotEmpty() }

        assertEquals(listOf(gone), orphans.map { it.uri })
        assertEquals(
            "the name comes from the URI when the file is not in the recents",
            "gone.md",
            orphans.single().displayName,
        )
        assertTrue("the time the edits were kept", orphans.single().savedAt > 0)
        assertTrue("the dialog opens on a cold start", vm.strandedDialogVisible.first { it })
        assertEquals("listing writes and removes nothing", 1, draftFiles().size)
    }

    @Test
    fun `a draft whose file still opens is not listed`() = inRealTime {
        files.texts[uri] = "original\n"
        files.texts[gone] = "original\n"
        keepDraft(uri, "original\ntyped\n")
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone

        val vm = startedVm()
        val orphans = vm.orphanDrafts.first { it.isNotEmpty() } // the deleted file's draft is the barrier

        assertEquals("only the deleted file's draft", listOf(gone), orphans.map { it.uri })
        assertEquals("both drafts are still stored", 2, draftFiles().size)
    }

    @Test
    fun `the draft of the open document is not listed even when its file stops opening`() = inRealTime {
        files.texts[uri] = "original\n"
        files.texts[gone] = "original\n"
        keepDraft(uri, "original\ntyped\n")
        keepDraft(gone, "original\ntyped\n")
        val (vm, _) = reopenWithOffer()
        vm.restoreDraft() // the draft stays stored and the document is open and dirty
        vm.recoveryOffer.first { it == null }
        files.missing += uri
        files.missing += gone

        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)
        val orphans = vm.orphanDrafts.first { it.isNotEmpty() }

        assertEquals(listOf(gone), orphans.map { it.uri })
    }

    @Test
    fun `Discard removes an orphan draft from the list and the store`() = inRealTime {
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone
        val vm = startedVm()
        vm.orphanDrafts.first { it.isNotEmpty() }

        vm.discardOrphanDraft(gone)
        vm.orphanDrafts.first { it.isEmpty() }
        vm.strandedDialogVisible.first { !it } // assigned after the list, so it is awaited too

        assertTrue("the draft is gone from the store", draftFiles().isEmpty())
    }

    /**
     * A process whose last open file is [gone]: the file is in the recents and is the remembered last file, so
     * the startup restore tries it and fails. Waits for that failure, which is false until it has happened.
     */
    private suspend fun startedVmWithDeletedLastFile(): Pair<MarkdownViewModel, LocalStorageManager> {
        val storage = newStorage()
        storage.saveLastFileUri(gone)
        storage.addRecent(com.pilcrowmd.storage.RecentFile(gone, "gone.md", 1L))
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone
        val vm = newVm(storage = storage).also { it.evaluateStrandedSlotsOnStart(openingFileFromIntent = false) }
        vm.loadFailedWithNoDocument.first { it }
        vm.orphanDrafts.first { it.isNotEmpty() }
        return vm to storage
    }

    @Test
    fun `Discard of an orphan draft clears the failed-open line and forgets the file in Recent`() = inRealTime {
        val (vm, storage) = startedVmWithDeletedLastFile()

        vm.discardOrphanDraft(gone)
        vm.orphanDrafts.first { it.isEmpty() }

        assertFalse("no red line on the home screen", vm.loadFailedWithNoDocument.value)
        assertTrue("the deleted file is not in Recent", storage.recentFiles.first().none { it.uri == gone })
        assertNull("the deleted file is not the last file", storage.lastFileUri.first())
    }

    @Test
    fun `Save a copy of an orphan draft clears the failed-open line and forgets the file in Recent`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        val (vm, storage) = startedVmWithDeletedLastFile()

        vm.saveOrphanDraftAsCopy(target, gone)
        vm.awaitSaveSuccess()
        vm.orphanDrafts.first { it.isEmpty() }

        assertFalse("no red line on the home screen", vm.loadFailedWithNoDocument.value)
        assertTrue("the deleted file is not in Recent", storage.recentFiles.first().none { it.uri == gone })
        assertNull("the deleted file is not the last file", storage.lastFileUri.first())
    }

    @Test
    fun `a failed copy of an orphan draft keeps the file in Recent`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        val (vm, storage) = startedVmWithDeletedLastFile()
        files.saveFails = true

        vm.saveOrphanDraftAsCopy(target, gone)
        vm.fileLoadState.first { it is FileLoadState.SaveError }

        assertTrue("the draft is kept, so is its Recent entry", storage.recentFiles.first().any { it.uri == gone })
    }

    @Test
    fun `Discard of a row that went stale keeps the draft and drops the row`() = inRealTime {
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone
        val vm = startedVm()
        vm.orphanDrafts.first { it.isNotEmpty() }
        files.missing -= gone // the file opens again (e.g. the provider is back) before the click

        vm.discardOrphanDraft(gone)
        vm.orphanDrafts.first { it.isEmpty() }

        assertEquals("the draft is still stored", 1, draftFiles().size)
    }

    @Test
    fun `Save a copy of a row that went stale writes nothing, keeps the draft and drops the row`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone
        val vm = startedVm()
        vm.orphanDrafts.first { it.isNotEmpty() }
        files.missing -= gone

        vm.saveOrphanDraftAsCopy(target, gone)
        vm.orphanDrafts.first { it.isEmpty() }

        assertEquals("the draft is still stored", 1, draftFiles().size)
        assertTrue("the target got no write", files.saves.none { it.first == target })
    }

    @Test
    fun `Save a copy writes an orphan draft with its line ending, then removes it and its list entry`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        files.texts[gone] = "one\r\ntwo\r\n"
        keepDraft(gone, "one\ntwo\nthree\n")
        files.missing += gone
        val vm = startedVm()
        vm.orphanDrafts.first { it.isNotEmpty() }

        vm.saveOrphanDraftAsCopy(target, gone)
        vm.awaitSaveSuccess()
        vm.orphanDrafts.first { it.isEmpty() }

        assertEquals(listOf(target to "one\r\ntwo\r\nthree\r\n"), files.saves.toList().filter { it.first == target })
        assertTrue("the draft is gone once the copy is safe", draftFiles().isEmpty())
    }

    @Test
    fun `a copy of an orphan draft that fails to save keeps the draft and its list entry`() = inRealTime {
        val target = Uri.parse("content://test/copy.md")
        files.texts[gone] = "original\n"
        keepDraft(gone, "original\ntyped\n")
        files.missing += gone
        val vm = startedVm()
        vm.orphanDrafts.first { it.isNotEmpty() }
        files.saveFails = true

        vm.saveOrphanDraftAsCopy(target, gone)
        vm.fileLoadState.first { it is FileLoadState.SaveError }

        assertEquals("the draft is the user's only copy", 1, draftFiles().size)
        assertEquals(listOf(gone), vm.orphanDrafts.value.map { it.uri })
    }

    @Test
    fun `an orphan draft is not trimmed by the limit of eight drafts`() = inRealTime {
        val store = LocalDraftRepository(draftDir)
        val text = "kept\n"
        // The orphan is the oldest of nine; the other eight are of files that open and have not changed.
        store.write(Draft(gone, "edits\n", "LF", ByteFingerprint.of(text), savedAt = 1))
        files.missing += gone
        repeat(8) { i ->
            val other = Uri.parse("content://test/other-$i.md")
            files.texts[other] = text
            store.write(Draft(other, "edits\n", "LF", ByteFingerprint.of(text), savedAt = 100L + i))
        }
        assertEquals("fixture: nine drafts are stored", 9, draftFiles().size)
        val fresh = Uri.parse("content://test/fresh.md")
        files.texts[fresh] = text
        val vm = newVm()
        vm.loadFile(fresh)
        vm.awaitOpen(fresh)

        vm.type("kept\nand typed\n")
        // The debounced write of the tenth draft is what trims: 10 stored, 2 removed, the oldest that may be.
        poll("the store to be trimmed to eight") {
            scheduler.advanceTimeBy(1_500)
            scheduler.runCurrent()
            draftFiles().size == 8
        }

        assertNotNull("the orphan is the oldest but is protected", store.read(gone))
    }
}
