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
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.StatsAnswer
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.ScrollAnchor
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8-A90 live reload (S8-A46 before it): an open file that changes outside the app is read again
 * by itself when it has no unsaved edits, and shows the reload banner when it has; unsaved edits
 * are never reloaded over without asking, and the app's own save is not a change. The provider
 * answers on another thread and the checks wait on the main looper's clock, so every assertion
 * waits for a value the ViewModel publishes.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelChangedOutsideTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    // A note of a Drive folder, as the Files screen opens it, and a file from another app.
    private val note = Uri.parse(
        "content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3D9/document/acc%3D1%3Bdoc%3D42",
    )
    private val shared = Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D42")

    /** The provider: the file's text and its date and size, which a test moves as Drive would. */
    private inner class Provider : FileRepository {
        val contents = ConcurrentHashMap<Uri, String>()
        val stats = ConcurrentHashMap<Uri, DocumentStats>()
        val statsAsked = AtomicInteger()

        /** What a save in place does to the stats: Drive's new size at once, its old date. */
        var onSave: (Uri, String) -> Unit = { uri, text ->
            stats[uri] = stats.getValue(uri).copy(size = text.length.toLong())
        }

        /** No answer at all, as a cloud provider with no network. */
        @Volatile var offline = false

        /** What the provider says: the values, gone when it has none, nothing when [offline]. */
        suspend fun answerOf(uri: Uri): StatsAnswer = withContext(Dispatchers.IO) {
            val now = stats[uri]
            val answer = when {
                offline -> StatsAnswer.Unknown
                now == null -> StatsAnswer.Gone
                else -> StatsAnswer.Known(now)
            }
            // Counted after the read, so a test that waits on the count sees the values it read.
            answer.also { statsAsked.incrementAndGet() }
        }

        val reads = AtomicInteger()

        /** The next read parks here, after counting itself, until the test completes it. */
        @Volatile var readGate: CompletableDeferred<Unit>? = null

        override suspend fun readFile(uri: Uri): Result<FileText> = withContext(Dispatchers.IO) {
            reads.incrementAndGet()
            readGate?.also { readGate = null }?.await()
            contents[uri]?.let { Result.success(FileText(it, isUtf8 = true)) }
                ?: Result.failure(IOException("unreadable"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = withContext(Dispatchers.IO) {
            contents[uri] = content
            onSave(uri, content)
            Result.success(Unit)
        }
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = "note.md"
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private val provider = Provider()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("changed_outside.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
        for (uri in listOf(note, shared)) {
            provider.contents[uri] = "old\n"
            provider.stats[uri] = DocumentStats(lastModified = 1_000L, size = 4L)
        }
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = provider,
            storage = storage,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            changeCheck = OpenFileChangeCheck(provider::answerOf, intervalMs = INTERVAL_MS),
        )
    }

    /** Polls, moving the main looper's clock on, so the checks' waits run out. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (actual() == expected) return
            Thread.sleep(5L)
        }
        assertEquals(message, expected, actual())
    }

    /** Opens [uri] and waits until it is on screen and its check has asked the provider once. */
    private fun MarkdownViewModel.opened(uri: Uri) {
        val asked = provider.statsAsked.get()
        loadFile(uri)
        awaitValue("old\n", "the file is open") { currentDocument.value?.content }
        awaitValue(true, "the check has its baseline") { provider.statsAsked.get() > asked }
    }

    /** The file changed on another device: Drive has fetched the new version. */
    private fun changeOnAnotherDevice() {
        provider.contents[note] = "new line from the Mac\n"
        provider.stats[note] = DocumentStats(lastModified = 2_000L, size = 22L)
    }

    /**
     * Runs the main looper's clock on by [CHECKS] checks, one at a time, leaving each one's provider
     * query time to answer, so every check that would run has run.
     */
    private fun windowRunsOut() {
        repeat(CHECKS + 3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(INTERVAL_MS))
            Thread.sleep(30L)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `with no unsaved edits a change is read again by itself, and the notice says so`() {
        val vm = vm()
        vm.opened(note)
        windowRunsOut() // long after the open
        assertEquals(false, vm.updatedOutside.value)

        changeOnAnotherDevice()

        awaitValue("new line from the Mac\n", "the new text, with no tap") { vm.currentDocument.value?.content }
        awaitValue(true, "the notice shows") { vm.updatedOutside.value }
        assertEquals("no banner", false, vm.changedOutside.value)
        vm.closeUpdatedNotice()
        assertEquals(false, vm.updatedOutside.value)
    }

    @Test
    fun `a reload by itself keeps the reading position`() {
        val vm = vm()
        vm.opened(note)
        val anchor = ScrollAnchor(index = 7, offset = 31)
        vm.updatePreviewScroll(anchor)
        awaitValue(anchor, "the position is stored") { runBlocking { storage.getScrollPosition(note) } }

        changeOnAnotherDevice()

        awaitValue(true, "reloaded") { vm.updatedOutside.value }
        assertEquals("the same place in the new text", anchor, vm.previewScroll.value)
    }

    @Test
    fun `it goes on after a reload, so a second change is read too`() {
        val vm = vm()
        vm.opened(note)
        changeOnAnotherDevice()
        awaitValue(true, "reloaded once") { vm.updatedOutside.value }

        provider.contents[note] = "and again\n"
        provider.stats[note] = DocumentStats(lastModified = 3_000L, size = 10L)

        awaitValue("and again\n", "reloaded twice") { vm.currentDocument.value?.content }
    }

    @Test
    fun `a file deleted by another app shows a notice, and its text stays`() {
        val vm = vm()
        vm.opened(note)

        provider.stats.remove(note)
        provider.contents.remove(note)

        awaitValue(true, "the notice shows") { vm.goneOutside.value }
        assertEquals("old\n", vm.currentDocument.value?.content)
        vm.closeGoneNotice()
        assertEquals(false, vm.goneOutside.value)
    }

    /**
     * Measured on the S24+ (S8-A46 step 2): with the change made on another device minutes before
     * the open, Drive already reports the new date and size, but the open still reads its old copy.
     * Nothing moves after the open, so only the size read against the size stated can tell.
     */
    @Test
    fun `a copy read at another size than the provider states shows the banner at once`() {
        provider.stats[note] = DocumentStats(lastModified = 2_000L, size = 22L) // the new version's
        val vm = vm()

        vm.opened(note) // reads "old\n", 4 bytes

        awaitValue(true, "read again by itself, on the first check") { vm.updatedOutside.value }
        assertEquals("one read at the open, one by itself", 2, provider.reads.get())
        windowRunsOut()
        assertEquals("Drive's old copy again is not taken for a second change", 2, provider.reads.get())
    }

    @Test
    fun `with unsaved edits nothing is reloaded, and Keep mine keeps them`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")

        changeOnAnotherDevice()

        awaitValue(true, "the banner shows") { vm.changedOutside.value }
        windowRunsOut()
        assertEquals("the edits stay", "mine\n", vm.currentDocument.value?.content)
        assertTrue(vm.currentDocument.value!!.dirty)
        vm.keepMyVersion()
        awaitValue(false, "the banner goes") { vm.changedOutside.value }
        assertEquals("mine\n", vm.currentDocument.value?.content)
        assertTrue("the next Save asks first", vm.otherVersion.value)

        vm.saveFile()
        awaitValue(OverwriteFor.SAVE, "Save asks first") { vm.overwriteQuestion.value }
        windowRunsOut()
        assertEquals("nothing written while it asks", "new line from the Mac\n", provider.contents[note])

        vm.saveFile(overwriteConfirmed = true) // Overwrite
        awaitValue("mine\n", "written over") { provider.contents[note] }
        awaitValue(false, "answered") { vm.otherVersion.value }
    }

    @Test
    fun `a pending overwrite question does not survive a switch to another file`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        changeOnAnotherDevice()
        awaitValue(true, "the banner shows") { vm.changedOutside.value }
        vm.saveFile()
        awaitValue(OverwriteFor.SAVE, "Save asks first") { vm.overwriteQuestion.value }

        vm.loadFile(shared) // unsaved edits: its Save/Discard prompt
        awaitValue(shared, "the prompt asks") { vm.pendingOpenUri.value }
        vm.discardAndOpenPending()
        awaitValue(shared, "the other file opens") { vm.currentDocument.value?.uri }

        awaitValue(null, "the question went with the file") { vm.overwriteQuestion.value }
    }

    @Test
    fun `while the banner shows, Save asks too`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        assertEquals("nothing to ask about yet", false, vm.otherVersion.value)

        changeOnAnotherDevice()

        awaitValue(true, "the banner shows") { vm.changedOutside.value }
        assertTrue("Save asks before writing over", vm.otherVersion.value)
    }

    @Test
    fun `after Keep mine the check goes on, and a second change shows the banner again`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        changeOnAnotherDevice()
        awaitValue(true, "the banner shows") { vm.changedOutside.value }
        vm.keepMyVersion()
        assertEquals(false, vm.changedOutside.value)

        provider.stats[note] = DocumentStats(lastModified = 3_000L, size = 40L)

        awaitValue(true, "the banner shows again") { vm.changedOutside.value }
        assertEquals("mine\n", vm.currentDocument.value?.content)
    }

    @Test
    fun `a reload by itself keeps the reader in the reader, with open in edit mode on`() {
        runBlocking { storage.setOpenInEditMode(true) }
        val vm = vm()
        vm.opened(note)
        vm.setMode(ViewMode.READER)
        awaitValue(ViewMode.READER, "reading") { vm.mode.value }

        changeOnAnotherDevice()

        awaitValue(true, "reloaded") { vm.updatedOutside.value }
        assertEquals(ViewMode.READER, vm.mode.value)
    }

    @Test
    fun `text typed while a reload by itself reads the file is never dropped`() {
        val vm = vm()
        vm.opened(note)
        val gate = CompletableDeferred<Unit>().also { provider.readGate = it }
        val readsBefore = provider.reads.get()

        changeOnAnotherDevice()
        awaitValue(true, "the reload is reading") { provider.reads.get() > readsBefore }
        vm.updateContent(vm.currentDocument.value!!.id, "typed meanwhile\n")
        gate.complete(Unit)

        awaitValue(note, "the Save/Discard prompt asks instead") { vm.pendingOpenUri.value }
        assertEquals("the typed text stays", "typed meanwhile\n", vm.currentDocument.value?.content)
        assertTrue("and Save asks before writing over the other version", vm.otherVersion.value)
    }

    @Test
    fun `saving over the changed file is the answer, and the banner goes`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        changeOnAnotherDevice()
        awaitValue(true, "the banner shows") { vm.changedOutside.value }

        vm.saveFile(overwriteConfirmed = true)

        awaitValue("mine\n", "saved") { provider.contents[note] }
        awaitValue(false, "the banner goes") { vm.changedOutside.value }
    }

    @Test
    fun `the Save of the close prompt asks too, and writes only on Overwrite`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        changeOnAnotherDevice()
        awaitValue(true, "the banner shows") { vm.changedOutside.value }

        vm.saveAndClose()

        awaitValue(OverwriteFor.CLOSE, "asks first") { vm.overwriteQuestion.value }
        windowRunsOut()
        assertEquals("nothing written", "new line from the Mac\n", provider.contents[note])
        assertEquals("still open", "mine\n", vm.currentDocument.value?.content)

        vm.saveAndClose(overwriteConfirmed = true)
        awaitValue("mine\n", "written on Overwrite") { provider.contents[note] }
        awaitValue(null, "and closed") { vm.currentDocument.value }
    }

    @Test
    fun `the Save of the open-another prompt asks too, and writes only on Overwrite`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine\n")
        changeOnAnotherDevice()
        awaitValue(true, "the banner shows") { vm.changedOutside.value }
        vm.loadFile(shared) // another file, with unsaved edits: its Save/Discard prompt
        awaitValue(shared, "the prompt asks") { vm.pendingOpenUri.value }

        vm.saveAndOpenPending()

        awaitValue(OverwriteFor.OPEN_PENDING, "asks first") { vm.overwriteQuestion.value }
        windowRunsOut()
        assertEquals("nothing written", "new line from the Mac\n", provider.contents[note])

        vm.saveAndOpenPending(overwriteConfirmed = true)
        awaitValue("mine\n", "written on Overwrite") { provider.contents[note] }
        awaitValue(shared, "and the other file opens") { vm.currentDocument.value?.uri }
    }

    @Test
    fun `an offline provider is never a delete, and the check goes on`() {
        val vm = vm()
        vm.opened(note)

        provider.offline = true
        windowRunsOut()
        windowRunsOut()

        assertEquals("no deleted notice", false, vm.goneOutside.value)
        assertEquals("no banner", false, vm.changedOutside.value)
        provider.offline = false
        changeOnAnotherDevice() // back online, with the change made meanwhile

        awaitValue("new line from the Mac\n", "read by itself once it answers") { vm.currentDocument.value?.content }
    }

    @Test
    fun `the app's own save inside the window is not a change, nor is Drive's later date for it`() {
        val vm = vm()
        vm.opened(note)
        vm.updateContent(vm.currentDocument.value!!.id, "mine, saved\n")

        vm.saveFile()
        awaitValue("mine, saved\n", "saved") { provider.contents[note] }
        awaitValue(false, "clean after the save") { vm.currentDocument.value?.dirty }
        // About 8 s later on Drive: the upload is done, and the date moves; the size stays.
        val asked = provider.statsAsked.get()
        awaitValue(true, "a check after the save") { provider.statsAsked.get() > asked }
        provider.stats[note] = provider.stats.getValue(note).copy(lastModified = 3_000L)

        windowRunsOut()
        assertEquals("no banner", false, vm.changedOutside.value)
    }

    @Test
    fun `a file opened from another app is checked too`() {
        val vm = vm()
        vm.opened(shared)
        provider.contents[shared] = "new\n"
        provider.stats[shared] = DocumentStats(lastModified = 2_000L, size = 4L)

        awaitValue("new\n", "reloaded") { vm.currentDocument.value?.content }
    }

    @Test
    fun `a file whose provider states neither date nor size is not checked, and no error shows`() {
        provider.stats.remove(shared)
        val vm = vm()
        vm.loadFile(shared)
        awaitValue("old\n", "the file is open") { vm.currentDocument.value?.content }
        awaitValue(1, "asked once at the open") { provider.statsAsked.get() }

        windowRunsOut()

        assertEquals("never asked again", 1, provider.statsAsked.get())
        assertEquals(false, vm.goneOutside.value)
    }

    @Test
    fun `going to the background ends the checks`() {
        val vm = vm()
        vm.opened(note)

        vm.stopChangeCheck()
        val asked = provider.statsAsked.get()
        changeOnAnotherDevice()
        windowRunsOut()

        assertEquals("no check after the stop", asked, provider.statsAsked.get())
        assertEquals("old\n", vm.currentDocument.value?.content)

        vm.resumeChangeCheck() // back in the app
        awaitValue("new line from the Mac\n", "the change made while away is read") {
            vm.currentDocument.value?.content
        }
    }

    private companion object {
        const val INTERVAL_MS = 100L
        const val CHECKS = 10
    }
}
