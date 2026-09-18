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
import com.pilcrowmd.storage.RecentFile
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * **M-90** (open documents in edit mode, by setting) and **M-91** (a blank document created from
 * the welcome screen). They are one test class because they are one piece of work — that was
 * declared in public — and because the two interact: a new document opens in the editor
 * *regardless* of M-90's setting, which only a test holding both can state.
 *
 * Mirrors [MarkdownViewModelRenderModeTest]'s fixture (real Robolectric DataStore + inline fake
 * repository) and its [awaitValue] barrier, for the same reason: the flows under test cross real
 * dispatchers, so a single main-looper idle is not a completion barrier.
 *
 * **Every assertion here was watched failing before the code existed.** The ones worth naming are
 * [newDocumentHasNoUriAndOpensInTheEditor] (the whole of M-91's state) and
 * [savingANewDocumentWritesOnlyToThePickedUri] (the data-loss question: a new document must never
 * acquire a file the user did not choose).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelNewDocumentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: RecordingStorage
    private lateinit var storageScope: CoroutineScope

    /**
     * Records the two URI-keyed writes a never-saved document must never make, by delegation so
     * every other operation behaves exactly as the real [LocalStorageManager].
     *
     * **Why a spy rather than reading the resulting state.** The property is "this call never
     * happens", and reading DataStore afterwards can only ever say "it has not happened *yet*" —
     * which is how an earlier version of this test passed against a `newDocument` that clobbered
     * the last-file URI a few milliseconds after the assertion looked. A recorded call is a fact
     * that cannot un-happen, so the assertion stops depending on when it is made.
     */
    private class RecordingStorage(delegate: StorageManager) : StorageManager by delegate {
        val recentAdds = mutableListOf<RecentFile>()
        val lastFileWrites = mutableListOf<Uri>()
        private val inner = delegate
        override suspend fun addRecent(file: RecentFile) {
            recentAdds += file
            inner.addRecent(file)
        }
        override suspend fun saveLastFileUri(uri: Uri) {
            lastFileWrites += uri
            inner.saveLastFileUri(uri)
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("newdoc_test.preferences_pb")
        }
        storage = RecordingStorage(LocalStorageManager(context, dataStore))
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val readContent: String = "# existing\n") : FileRepository {
        val capturedSaves = linkedMapOf<Uri, String>()
        override suspend fun readFile(uri: Uri) = Result.success(readContent)
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
            capturedSaves[uri] = content
            return Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun vmWith(repo: FakeRepo = FakeRepo()): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    /**
     * Block until the operation under test has actually FINISHED, by awaiting the terminal
     * `FileLoadState.Success` that both `loadFile` and `newDocument` emit as their last action.
     *
     * **This replaced a wall-clock "hold the assertion open for 400ms" helper, and the reason is
     * worth keeping.** That helper was sound in intent — a "must never happen" property cannot be
     * established by one sample — but it proved the property only for as long as the window
     * happened to be, so on a loaded CI runner the offending write could land after the window
     * closed. A barrier on the terminal state is not a guess: the writes being excluded all
     * happen before it.
     *
     * **It is only a barrier where `Success` is not already the current value**, which is why
     * every caller starts from a fresh ViewModel (state `Idle`). Awaiting a value that is already
     * current is the vacuous form this repo has been bitten by repeatedly.
     */
    private fun MarkdownViewModel.awaitSettled(what: String) {
        awaitValue(FileLoadState.Success, "$what settled") { fileLoadState.value }
    }

    /** See [MarkdownViewModelRenderModeTest.awaitValue] — same barrier, same caveats. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    private val mdUri = Uri.parse("content://test/readme.md")
    private val targetUri = Uri.parse("content://test/chosen.md")

    /** Barrier on the document identity, which only becomes non-null once the load completes. */
    private fun MarkdownViewModel.loadAndAwait(uri: Uri) {
        loadFile(uri)
        awaitValue(uri, "document loaded") { currentDocument.value?.uri }
    }

    // ── M-90: open in edit mode, by setting ──────────────────────────────────────────

    /**
     * The setting is OFF by default, so this is also the guard that M-90 changes nothing for
     * anyone who does not ask for it.
     */
    @Test
    fun defaultOffMeansADocumentStillOpensInTheReader() {
        val vm = vmWith()
        vm.loadFile(mdUri)
        // Barrier on the TERMINAL state, not on the document identity. `loadFile` emits the
        // identity BEFORE it decides the view mode, so a test that waited on the identity read
        // `mode` mid-load — measured: an earlier version of this test PASSED against a `loadFile`
        // that opened every document in the editor unconditionally (mutation M8).
        vm.awaitSettled("load")
        assertEquals("default must remain the reader", ViewMode.READER, vm.mode.value)
    }

    @Test
    fun settingOnOpensTheDocumentInTheEditor() {
        val vm = vmWith()
        runBlocking { storage.setOpenInEditMode(true) }
        awaitValue(true, "setting reaches the ViewModel") { vm.openInEditMode.value }

        vm.loadAndAwait(mdUri)
        awaitValue(ViewMode.EDITOR, "document opens in the editor when the setting is on") { vm.mode.value }
    }

    /**
     * The one-directional rule, stated as a test because it is the part a reader would most
     * plausibly "simplify" into a symmetric `_mode.emit(if (on) EDITOR else READER)`.
     *
     * With the setting OFF, opening a file must leave the mode **exactly as it was** — the
     * pre-existing behaviour of the mode being sticky within a session. Forcing READER would be a
     * second behaviour change nobody asked for, and it would silently kick a user out of the
     * editor every time they opened a file.
     */
    @Test
    fun settingOffLeavesTheCurrentModeAlone() {
        val vm = vmWith()
        vm.setMode(ViewMode.EDITOR)
        awaitValue(ViewMode.EDITOR, "precondition: sitting in the editor") { vm.mode.value }

        vm.loadFile(mdUri)
        // Same terminal barrier, same reason as [defaultOffMeansADocumentStillOpensInTheReader]:
        // the property is "the load never touches the mode", and a sample taken before the load
        // reaches its mode decision cannot say that.
        vm.awaitSettled("load")
        assertEquals(
            "with the setting off the mode is left alone, not forced back to the reader",
            ViewMode.EDITOR,
            vm.mode.value,
        )
    }

    // ── M-91: a blank document with no file ──────────────────────────────────────────

    @Test
    fun newDocumentHasNoUriAndOpensInTheEditor() {
        val vm = vmWith()
        vm.newDocument()
        awaitValue(ViewMode.EDITOR, "a new document opens in the editor") { vm.mode.value }

        val doc = vm.currentDocument.value
        assertNotNull("a document is open", doc)
        assertNull("it has NO file on disk yet", doc!!.uri)
        assertTrue("isUnsaved says so", doc.isUnsaved)
        assertEquals("it starts empty", "", doc.content)
        assertFalse("an untouched new document is not dirty", doc.dirty)
        assertEquals("it is named for the Save-As dialog", NEW_DOCUMENT_NAME, doc.displayName)
    }

    /**
     * A new document opens in the editor **even with M-90 off**, and this is not redundant with
     * [newDocumentHasNoUriAndOpensInTheEditor]: that test would pass for an implementation that
     * merely read the setting, because the setting happens to be off there too. Here the setting
     * is explicitly off and the expectation is still EDITOR, so only an unconditional choice
     * satisfies it. A blank document in the reader is an empty screen.
     */
    @Test
    fun newDocumentOpensInTheEditorEvenWhenTheSettingIsOff() {
        val vm = vmWith()
        runBlocking { storage.setOpenInEditMode(false) }
        awaitValue(false, "setting is off") { vm.openInEditMode.value }

        vm.newDocument()
        awaitValue(ViewMode.EDITOR, "still the editor - a blank reader shows nothing") { vm.mode.value }
    }

    /**
     * **Safeguard 1.** `saveFile()` is an in-place write, and a document with no file has no place
     * to write to. It must do nothing rather than invent a target — the View routes this case to
     * Save-As, and this is the ViewModel refusing to rely on that routing being correct.
     */
    @Test
    fun savingInPlaceIsRefusedWhileThereIsNoFile() {
        val repo = FakeRepo()
        val vm = vmWith(repo)
        vm.newDocument()
        awaitValue(ViewMode.EDITOR, "new document open") { vm.mode.value }
        vm.updateContent("some writing the user would hate to lose\n")
        awaitValue(true, "content registered as dirty") { vm.currentDocument.value?.dirty }

        vm.saveFile()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50L)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("no write may happen without a chosen target", repo.capturedSaves.isEmpty())
        assertTrue("the edits stay in memory", vm.currentDocument.value!!.dirty)
        assertNull("and the document still has no file", vm.currentDocument.value!!.uri)
    }

    /**
     * The ending M-91 is built around: Save-As gives the new document a real file, using the
     * unchanged existing path. The assertion is on the **captured URI**, not merely that a save
     * happened — writing the right bytes to the wrong file is the failure being excluded.
     */
    @Test
    fun savingANewDocumentWritesOnlyToThePickedUri() {
        val repo = FakeRepo()
        val vm = vmWith(repo)
        vm.newDocument()
        awaitValue(ViewMode.EDITOR, "new document open") { vm.mode.value }
        vm.updateContent("# first note\n")
        awaitValue(true, "dirty") { vm.currentDocument.value?.dirty }

        vm.saveActiveDocumentAs(targetUri)
        awaitValue(targetUri, "the document adopts the picked file") { vm.currentDocument.value?.uri }

        assertEquals("exactly one file was written", 1, repo.capturedSaves.size)
        assertEquals("and it is the one the user picked", targetUri, repo.capturedSaves.keys.first())
        assertEquals("with the text the user typed", "# first note\n", repo.capturedSaves[targetUri])
        assertFalse("it is no longer unsaved", vm.currentDocument.value!!.isUnsaved)
    }

    /**
     * Recents and last-file are both keyed by URI. A document with no file must write to neither,
     * or the welcome screen would offer to reopen something that does not exist and the next
     * launch would restore it over the user's real document.
     *
     * **Asserted on RECORDED CALLS, not on the resulting DataStore state.** Reading the state
     * afterwards can only ever say "it has not happened *yet*" — which is how an earlier version
     * of this test passed against a `newDocument` that clobbered the last-file URI a few
     * milliseconds after the assertion looked. A recorded call is a fact that cannot un-happen.
     *
     * **The barrier is `Success`, and that is sound HERE for a reason that does not generalise.**
     * `newDocument` emits `Success` as its final action, after everything it could possibly write.
     * `loadFile` does the opposite — it emits `Success` **before** its recents/last-file writes, on
     * purpose, so the load outcome does not wait on DataStore I/O. So "await Success" is **not** a
     * universal completion barrier in this ViewModel; it is one for this method only. The positive
     * control below is what keeps that from being a silent assumption.
     *
     * **The positive control matters more than the assertions above it.** Two empty lists prove
     * nothing on their own — they would look identical if the spy simply never recorded. So the
     * same test then performs a Save-As and waits for the recorder to fire, demonstrating in this
     * very run that the recorder was live while the negative assertions were made.
     */
    @Test
    fun aNewDocumentWritesNothingKeyedByUri() {
        val vm = vmWith()
        vm.newDocument()
        vm.awaitSettled("newDocument")

        assertTrue(
            "an unsaved document must not be added to recents, but addRecent was called with " +
                storage.recentAdds,
            storage.recentAdds.isEmpty(),
        )
        assertTrue(
            "nor written as the last file - that would restore it over the user's real document, " +
                "but saveLastFileUri was called with " + storage.lastFileWrites,
            storage.lastFileWrites.isEmpty(),
        )

        // POSITIVE CONTROL, in-run: give the document a file and the recorder must fire. Without
        // this, both assertions above would pass against a spy that records nothing at all.
        vm.saveActiveDocumentAs(targetUri)
        awaitValue(targetUri, "control: saving records the chosen file as recent") {
            storage.recentAdds.firstOrNull()?.uri
        }
        awaitValue(listOf(targetUri), "control: and as the last file") { storage.lastFileWrites }
    }
}
