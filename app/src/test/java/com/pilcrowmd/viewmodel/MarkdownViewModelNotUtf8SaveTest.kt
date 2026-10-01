// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.LocalFileRepository
import com.pilcrowmd.repository.NotUtf8Corpus
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream

/**
 * NEW-12: **a file is never written back in a different encoding than it was read in.**
 * A document read from a file that is not valid UTF-8 is shown, but every path that would write over
 * that file refuses — Save, Save-and-close, Save-and-open, and so the pending-save journal never
 * holds it either — and Save-As writes a UTF-8 copy to a NEW file, leaving the original untouched.
 *
 * Runs the REAL [LocalFileRepository] (decode, journal, write) over real files; only the SAF
 * ContentResolver is mocked, to hand out descriptors over those files. Every refusal is asserted
 * byte for byte against the file on disk, which is what the rule is about.
 *
 * Barriers are the `awaitValue` idiom of [MarkdownViewModelRenderModeTest] — poll, idling the
 * Robolectric main looper, no `Dispatchers.setMain` — because the real repository's descriptors need
 * the `testDebugUnitTest` JVM (under `testMainDispatcherDebug` every read fails reflecting into
 * `FileDescriptor.fd`). Each wait is on the state the call itself publishes
 * ([FileLoadState.SaveRefusedNotUtf8] is never an initial value), and the terminal set each wait
 * accepts includes what the call would publish if it did NOT refuse, so removing a refusal fails the
 * assertion instead of hanging (each was removed and seen to fail — see the NEW-12 entry).
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelNotUtf8SaveTest {

    private val vmStore = ViewModelStore()
    private var vmSeq = 0

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private lateinit var walDir: File
    private lateinit var docsDir: File
    private val files = mutableMapOf<Uri, File>()

    /** When set, a "wt" open truncates its file and the write then dies — the WAL's danger window. */
    @Volatile
    private var writesDieAfterTruncating = false

    private val resolver: ContentResolver = mockk {
        every { openFileDescriptor(any(), "r") } answers {
            ParcelFileDescriptor.open(fileFor(firstArg()), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        every { openFileDescriptor(any(), "wt") } answers {
            val file = fileFor(firstArg())
            if (writesDieAfterTruncating) {
                FileOutputStream(file).close()
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }
        }
        every { query(any(), any(), any(), any(), any()) } returns null // name = last path segment
        every { takePersistableUriPermission(any(), any()) } just runs
        every { persistedUriPermissions } answers { files.keys.map(::grant) }
    }

    private val uriA: Uri = Uri.parse("content://test/a.md")
    private val uriB: Uri = Uri.parse("content://test/b.md")
    private val copyUri: Uri = Uri.parse("content://test/copy.md")

    private fun fileFor(uri: Uri): File = checkNotNull(files[uri]) { "no file for $uri" }

    private fun grant(forUri: Uri): UriPermission = mockk {
        every { uri } returns forUri
        every { isReadPermission } returns true
        every { isWritePermission } returns true
    }

    private fun put(uri: Uri, bytes: ByteArray): File =
        File(docsDir, uri.lastPathSegment!!).apply { writeBytes(bytes) }.also { files[uri] = it }

    private fun pendingSlots(): List<File> = File(walDir, "pending_saves").listFiles()?.toList() ?: emptyList()

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        walDir = tempFolder.newFolder("nobackup")
        docsDir = tempFolder.newFolder("docs")
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("not_utf8_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        vmStore.clear() // cancels every viewModelScope
        storageScope.cancel()
    }

    private fun vm(): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = LocalFileRepository(resolver, walDir),
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

    /** Poll [actual] until [done], idling the main looper so `viewModelScope` work can land. */
    private fun <T> awaitValue(what: String, actual: () -> T, done: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val value = actual()
            if (done(value)) return value
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what; last value ${actual()}")
    }

    private fun MarkdownViewModel.awaitOpen(uri: Uri) = awaitValue(
        "$uri to open",
        { currentDocument.value?.uri to fileLoadState.value },
    ) { (openUri, state) -> openUri == uri && state is FileLoadState.Success }

    /** Waits for the outcome of a save: refused, written, failed, or (for a close) closed. */
    private fun MarkdownViewModel.awaitSaveOutcome(): FileLoadState =
        awaitValue("a save outcome", { fileLoadState.value }) {
            it is FileLoadState.SaveRefusedNotUtf8 ||
                it is FileLoadState.SaveRefusedPickNewFile ||
                it is FileLoadState.SaveSuccess ||
                it is FileLoadState.SaveError ||
                it == FileLoadState.Idle
        }

    /** Open [uri] and type, so there is something unsaved that a refusal must not lose. */
    private fun MarkdownViewModel.openAndEdit(uri: Uri): String {
        loadFile(uri)
        awaitOpen(uri)
        val edited = currentDocument.value!!.content + "typed\n"
        updateContent(currentDocument.value!!.id, edited)
        awaitValue("the edit", { currentDocument.value?.content }) { it == edited }
        return edited
    }

    @Test
    fun eachNonUtf8FileOpensFlaggedAndItsSaveIsRefusedWithTheBytesUnchanged() {
        for ((name, bytes) in NotUtf8Corpus.cases) {
            val uri = Uri.parse("content://test/$name")
            val file = put(uri, bytes)
            val vm = vm()
            val edited = vm.openAndEdit(uri)
            assertTrue("$name opens flagged as not UTF-8", vm.currentDocument.value!!.notUtf8)

            vm.saveFile()

            assertEquals(name, FileLoadState.SaveRefusedNotUtf8, vm.awaitSaveOutcome())
            assertArrayEquals("$name: not one byte may change", bytes, file.readBytes())
            assertEquals(uri, vm.currentDocument.value?.uri)
            assertEquals("the edits stay on screen", edited, vm.currentDocument.value?.content)
            assertTrue("and stay unsaved", vm.currentDocument.value!!.dirty)
            assertTrue("nothing was staged for the journal to replay", pendingSlots().isEmpty())
        }
    }

    @Test
    fun saveAndCloseIsRefusedAndTheDocumentStaysOpenAndDirty() {
        val bytes = NotUtf8Corpus.cases.getValue("windows-1252.md")
        val file = put(uriA, bytes)
        val vm = vm()
        val edited = vm.openAndEdit(uriA)

        vm.saveAndClose()

        assertEquals(FileLoadState.SaveRefusedNotUtf8, vm.awaitSaveOutcome())
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("the close did not happen", uriA, vm.currentDocument.value?.uri)
        assertEquals(edited, vm.currentDocument.value?.content)
        assertTrue(vm.currentDocument.value!!.dirty)
    }

    @Test
    fun saveAndOpenPendingIsRefusedAndTheSwitchDoesNotHappen() {
        val bytes = NotUtf8Corpus.cases.getValue("latin-1.md")
        val file = put(uriA, bytes)
        put(uriB, "# B\n".toByteArray())
        val vm = vm()
        val edited = vm.openAndEdit(uriA)
        vm.openFromIntent(uriB)
        assertEquals("fixture: the dirty document raises the prompt", uriB, vm.pendingOpenUri.value)

        vm.saveAndOpenPending()

        // Not refused, the save would succeed silently and the switch would publish B's load.
        val outcome = awaitValue("the save-and-open outcome", { vm.fileLoadState.value }) {
            it is FileLoadState.SaveRefusedNotUtf8 ||
                it is FileLoadState.SaveError ||
                (it is FileLoadState.Success && vm.currentDocument.value?.uri == uriB)
        }
        assertEquals(FileLoadState.SaveRefusedNotUtf8, outcome)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("the switch did not happen", uriA, vm.currentDocument.value?.uri)
        assertEquals(edited, vm.currentDocument.value?.content)
        assertTrue(vm.currentDocument.value!!.dirty)
        assertEquals("the prompt stays, so the user can still choose", uriB, vm.pendingOpenUri.value)
    }

    /**
     * The journal: a save that reached the repository would be staged BEFORE the target is touched,
     * and here every write dies after truncating, so a slot would remain and the next launch's
     * recovery would write the UTF-8 text over the file. The refusal comes first, so nothing is
     * staged, recovery finds nothing, and the original bytes survive a "next launch".
     */
    @Test
    fun aRefusedSaveLeavesNothingForCrashRecoveryToWrite() {
        val bytes = NotUtf8Corpus.cases.getValue("utf-16le-bom.md")
        val file = put(uriA, bytes)
        writesDieAfterTruncating = true
        val vm = vm()
        vm.openAndEdit(uriA)

        vm.saveFile()
        assertEquals(FileLoadState.SaveRefusedNotUtf8, vm.awaitSaveOutcome())

        assertTrue("nothing staged", pendingSlots().isEmpty())
        writesDieAfterTruncating = false
        val recovered = runBlocking { LocalFileRepository(resolver, walDir).recoverPendingSaves() }.getOrThrow()
        assertEquals(0, recovered)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun saveAsWritesAUtf8CopyAndLeavesTheOriginalUnchanged() {
        val bytes = NotUtf8Corpus.cases.getValue("truncated-utf-8.md")
        val original = put(uriA, bytes)
        val copy = put(copyUri, ByteArray(0)) // what the picker creates
        val vm = vm()
        val edited = vm.openAndEdit(uriA)

        vm.saveActiveDocumentAs(copyUri)

        assertEquals(FileLoadState.SaveSuccess, vm.awaitSaveOutcome())
        assertArrayEquals("the original is never touched", bytes, original.readBytes())
        assertArrayEquals(edited.toByteArray(Charsets.UTF_8), copy.readBytes())
        val doc = vm.currentDocument.value!!
        assertEquals("the document is now the copy", copyUri, doc.uri)
        assertFalse("the copy is UTF-8, so it saves in place from here", doc.notUtf8)
    }

    /**
     * A picker that hands back the original itself would make Save-As an in-place save. The file is
     * emptied after opening, so the empty-target check would let the write through: only the
     * same-URI check can refuse it.
     */
    @Test
    fun saveAsOntoTheOriginalItselfIsRefused() {
        val file = put(uriA, NotUtf8Corpus.cases.getValue("windows-1252.md"))
        val vm = vm()
        vm.openAndEdit(uriA)
        file.writeBytes(ByteArray(0))

        vm.saveActiveDocumentAs(uriA)

        assertEquals(FileLoadState.SaveRefusedNotUtf8, vm.awaitSaveOutcome())
        assertEquals("nothing was written", 0L, file.length())
        assertTrue(vm.currentDocument.value!!.notUtf8)
        assertTrue(vm.currentDocument.value!!.dirty)
    }

    /**
     * The picker can hand back the original under ANOTHER URI: open the file from "Recent", then in
     * Save As pick the same file in Downloads and confirm "Overwrite". A URI comparison cannot see
     * that, so the copy may only go to a new, empty file (seen overwriting the original on a device).
     */
    @Test
    fun saveAsOntoTheOriginalUnderAnotherUriIsRefused() {
        val bytes = NotUtf8Corpus.cases.getValue("windows-1252.md")
        val file = put(uriA, bytes)
        val sameFileElsewhere = Uri.parse("content://other.provider/document/a")
        files[sameFileElsewhere] = file
        val vm = vm()
        vm.openAndEdit(uriA)

        vm.saveActiveDocumentAs(sameFileElsewhere)

        assertEquals("the user is told to pick a new file", FileLoadState.SaveRefusedPickNewFile, vm.awaitSaveOutcome())
        assertArrayEquals("the original was overwritten through another URI", bytes, file.readBytes())
        assertEquals("the document stays the original", uriA, vm.currentDocument.value!!.uri)
        assertTrue(vm.currentDocument.value!!.dirty)
    }

    /**
     * A stranded slot's "Save a copy" goes through the same picker, so it too can be handed a file
     * that is not UTF-8 (here the open original, under another URI). It is refused and the slot
     * kept; a new, empty file still takes the rescue.
     */
    @Test
    fun rescueOntoAFileThatIsNotUtf8IsRefusedAndTheSlotKept() {
        val bytes = NotUtf8Corpus.cases.getValue("windows-1252.md")
        val file = put(uriA, bytes)
        val sameFileElsewhere = Uri.parse("content://other.provider/document/a")
        files[sameFileElsewhere] = file
        val slotDir = File(walDir, "pending_saves").apply { mkdirs() }
        File(slotDir, "k1.content").writeText("B, rescued\n")
        File(slotDir, "k1.uri").writeText(uriB.toString())
        File(slotDir, "k1.committing").createNewFile()
        val vm = vm()
        vm.openAndEdit(uriA)

        vm.rescueStrandedSlot(sameFileElsewhere, "k1")

        assertEquals(FileLoadState.SaveRefusedPickNewFile, vm.awaitSaveOutcome())
        assertArrayEquals("the rescue wrote over a file that is not UTF-8", bytes, file.readBytes())
        assertTrue("the slot is kept", File(slotDir, "k1.content").exists())

        val copy = put(copyUri, ByteArray(0))
        vm.resetSaveState()
        vm.rescueStrandedSlot(copyUri, "k1")
        val outcome = awaitValue("the second rescue", { vm.fileLoadState.value }) { it != FileLoadState.Idle }
        assertEquals(FileLoadState.SaveSuccess, outcome)
        assertEquals("B, rescued\n", copy.readText())
    }

    /** The flag is derived from the file on every load, so the startup restore brings it back. */
    @Test
    fun theStartupRestoreFlagsTheFileAgain() {
        put(uriA, NotUtf8Corpus.cases.getValue("latin-1.md"))
        vm().apply {
            loadFile(uriA)
            awaitOpen(uriA)
        }
        awaitValue("the file to be remembered", { runBlocking { storage.lastFileUri.first() } }) { it == uriA }

        val restored = vm() // a fresh process: the restore reopens the last file
        restored.awaitOpen(uriA)

        assertTrue("the restored document is flagged again", restored.currentDocument.value!!.notUtf8)
    }

    /** Control: valid UTF-8 with a BOM and CRLF endings is not flagged and saves back byte-identical. */
    @Test
    fun aUtf8FileWithABomAndCrlfStillSavesByteIdentical() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "# Title\r\n\r\nCafé, 5 €\r\n".toByteArray(Charsets.UTF_8)
        val file = put(uriA, bytes)
        val vm = vm()
        vm.loadFile(uriA)
        vm.awaitOpen(uriA)
        assertFalse(vm.currentDocument.value!!.notUtf8)
        file.writeBytes("SENTINEL".toByteArray()) // a save that never wrote would otherwise pass

        vm.saveFile()

        assertEquals(FileLoadState.SaveSuccess, vm.awaitSaveOutcome())
        assertArrayEquals(bytes, file.readBytes())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 1L
    }
}
