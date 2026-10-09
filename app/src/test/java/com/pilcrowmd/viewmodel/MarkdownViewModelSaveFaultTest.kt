// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.LocalFileRepository
import com.pilcrowmd.repository.SaveFault
import com.pilcrowmd.repository.SaveFaultRig
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

/**
 * Hardening gate (c): save-path fault injection, end to end through the ViewModel (Safeguard 1).
 *
 * The REAL [LocalFileRepository] and journal run under the ViewModel; only the ContentResolver is a
 * strict mock, faulted by [SaveFaultRig]. For every fault and save path the suite asserts:
 *  (i)   the file the next launch would read is the original or the complete edit, never partial;
 *  (ii)  the ViewModel publishes [FileLoadState.SaveError] with a message (MainScreen shows it as the
 *        "Save failed" toast; the message itself is not displayed);
 *  (iii) the document stays open, holds the edit and stays dirty, and a retry once the fault has
 *        passed saves it;
 *  (iv)  nothing escapes: an uncaught exception in viewModelScope surfaces from the looper idle.
 *
 * Barriers: each save is awaited on the terminal outcome it publishes — SaveError or
 * SaveSuccess with the write claim released — never on a state the ViewModel already held. The await
 * idiom is [MarkdownViewModelCloseDuringWriteTest]'s: poll and idle the main looper, no setMain.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelSaveFaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope
    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private lateinit var rig: SaveFaultRig
    private lateinit var fileA: File
    private lateinit var copyFile: File

    private val aUri = Uri.parse("content://t/a.md")
    private val bUri = Uri.parse("content://t/b.md")
    private val copyUri = Uri.parse("content://t/copy.md")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("save_fault_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)

        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
        rig = SaveFaultRig(resolver, walBaseDir, tempFolder.newFolder("provider"))
        fileA = tempFolder.newFile("a.md").apply { writeBytes(ORIGINAL) }
        copyFile = tempFolder.newFile("copy.md") // what a CreateDocument leaves: an empty file
        every { resolver.openFileDescriptor(aUri, "r") } answers { SaveFaultRig.openR(fileA) }
        every { resolver.openFileDescriptor(copyUri, "r") } answers { SaveFaultRig.openR(copyFile) }
        every { resolver.query(any<Uri>(), any(), any<String>(), any(), any<String>()) } returns null
        every { resolver.takePersistableUriPermission(any(), any()) } just runs
        rig.grantReadWrite(aUri)
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = LocalFileRepository(resolver, walBaseDir),
            storage = storage,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(message, condition())
    }

    /** Open A and type into it. Both barriers wait on a state the call publishes, not an initial one. */
    private fun MarkdownViewModel.openAndEdit() {
        loadFile(aUri)
        awaitTrue("A never opened") {
            currentDocument.value?.uri == aUri && fileLoadState.value == FileLoadState.Success
        }
        updateContent(currentDocument.value!!.id, EDITED_TEXT)
        awaitTrue("the edit never made A dirty") { currentDocument.value?.dirty == true }
    }

    /** The terminal outcome of the save just started: SaveError or SaveSuccess, claim released. */
    private fun MarkdownViewModel.awaitSaveOutcome(): FileLoadState {
        awaitTrue("the save never reached a terminal outcome") {
            val state = fileLoadState.value
            (state is FileLoadState.SaveError || state is FileLoadState.SaveSuccess) && !writeInFlight.value
        }
        return fileLoadState.value
    }

    /**
     * Open A, edit, arm [fault] against [faultUri] (backed by [faultFile]), run [save], and assert
     * (i)–(iv). Returns the ViewModel, still on A, for path-specific assertions.
     */
    private fun failedSave(
        fault: SaveFault,
        faultUri: Uri = aUri,
        faultFile: File = fileA,
        save: MarkdownViewModel.() -> Unit = { saveFile() },
    ): MarkdownViewModel {
        val vm = vm()
        vm.openAndEdit()
        val before = faultFile.readBytes()
        rig.arm(fault, faultUri, faultFile, EDITED)

        vm.save()
        val outcome = vm.awaitSaveOutcome()

        assertTrue("$fault: expected SaveError, got $outcome", outcome is FileLoadState.SaveError)
        assertTrue("$fault: SaveError needs a message", (outcome as FileLoadState.SaveError).message.isNotBlank())
        val doc = vm.currentDocument.value
        assertEquals("$fault: the document must stay open", aUri, doc?.uri)
        assertEquals("$fault: the edit must stay in memory", EDITED_TEXT, doc?.content)
        assertTrue("$fault: the document must stay dirty", doc?.dirty == true)
        assertNextLaunchReadsACompleteVersion(fault, faultUri, before)
        return vm
    }

    /** (i): what a fresh repository (the next launch) reads is [before] or the complete edit. */
    private fun assertNextLaunchReadsACompleteVersion(fault: SaveFault, faultUri: Uri, before: ByteArray) {
        val read = runBlocking { LocalFileRepository(resolver, walBaseDir).readFile(faultUri) }.getOrThrow().content
        assertTrue(
            "$fault: the next launch would read ${read.length} chars, neither the original nor the edit",
            read == String(before) || read == EDITED_TEXT,
        )
    }

    /** (iii): once the fault has passed, a plain retry saves the edit in place and clears dirty. */
    private fun MarkdownViewModel.assertRetrySaves() {
        rig.disarm(aUri, fileA)
        saveFile()
        awaitTrue("the retry never saved") {
            fileLoadState.value == FileLoadState.SaveSuccess && !writeInFlight.value
        }
        assertArrayEquals("the retry must land the complete edit", EDITED, fileA.readBytes())
        assertFalse("a saved document is clean", currentDocument.value!!.dirty)
    }

    private fun saveFileFailsSafelyAndRetries(fault: SaveFault) = failedSave(fault).assertRetrySaves()

    // ── saveFile, one test per fault ─────────────────────────────────────────────────────────

    @Test
    fun `saveFile - disk full while staging the journal`() = saveFileFailsSafelyAndRetries(SaveFault.JOURNAL_UNWRITABLE)

    @Test
    fun `saveFile - disk full at the target open`() = saveFileFailsSafelyAndRetries(SaveFault.OPEN_ENOSPC)

    @Test
    fun `saveFile - the target write fails before a byte lands`() =
        saveFileFailsSafelyAndRetries(SaveFault.WRITE_FAILS_AT_BYTE_0)

    @Test
    fun `saveFile - SyncFailedException at the descriptor handover`() =
        saveFileFailsSafelyAndRetries(SaveFault.DESCRIPTOR_SYNC_FAILED)

    @Test
    fun `saveFile - the target write fails after N bytes`() =
        saveFileFailsSafelyAndRetries(SaveFault.WRITE_FAILS_AFTER_N_BYTES)

    @Test
    fun `saveFile - grant revoked between load and save`() = saveFileFailsSafelyAndRetries(SaveFault.OPEN_SECURITY)

    @Test
    fun `saveFile - provider returns a null descriptor`() = saveFileFailsSafelyAndRetries(SaveFault.OPEN_NULL)

    @Test
    fun `saveFile - provider throws FileNotFoundException`() =
        saveFileFailsSafelyAndRetries(SaveFault.OPEN_FILE_NOT_FOUND)

    @Test
    fun `saveFile - provider throws IllegalStateException on open`() =
        saveFileFailsSafelyAndRetries(SaveFault.OPEN_ILLEGAL_STATE)

    @Test
    fun `saveFile - provider throws a RuntimeException on open`() =
        saveFileFailsSafelyAndRetries(SaveFault.OPEN_RUNTIME)

    @Test
    fun `saveFile - provider throws at the descriptor handover`() =
        saveFileFailsSafelyAndRetries(SaveFault.DESCRIPTOR_ILLEGAL_STATE)

    @Test
    fun `saveFile - descriptor close throws after a full write`() =
        saveFileFailsSafelyAndRetries(SaveFault.CLOSE_THROWS_AFTER_FULL_WRITE)

    // ── the other save paths, one pre-truncation and one mid-write fault each ───────────────

    @Test
    fun `saveAndClose - grant revoked keeps the document open`() = saveAndCloseKeepsTheDocument(SaveFault.OPEN_SECURITY)

    @Test
    fun `saveAndClose - the write fails mid-way keeps the document open`() =
        saveAndCloseKeepsTheDocument(SaveFault.WRITE_FAILS_AFTER_N_BYTES)

    private fun saveAndCloseKeepsTheDocument(fault: SaveFault) {
        val vm = failedSave(fault, save = { saveAndClose() })
        assertFalse("$fault: a failed save must not raise the close prompt", vm.closeNeedsConfirm.value)
        vm.assertRetrySaves()
    }

    @Test
    fun `saveAndOpenPending - grant revoked keeps A and the pending open`() =
        saveAndOpenPendingKeepsBoth(SaveFault.OPEN_SECURITY)

    @Test
    fun `saveAndOpenPending - the write fails mid-way keeps A and the pending open`() =
        saveAndOpenPendingKeepsBoth(SaveFault.WRITE_FAILS_AFTER_N_BYTES)

    private fun saveAndOpenPendingKeepsBoth(fault: SaveFault) {
        val vm = failedSave(
            fault,
            save = {
                openFromIntent(bUri) // A is dirty, so B waits behind the Save/Discard prompt
                assertEquals("fixture: B must be pending", bUri, pendingOpenUri.value)
                saveAndOpenPending()
            },
        )
        assertEquals("$fault: the pending open must survive the failed save", bUri, vm.pendingOpenUri.value)
        verify(exactly = 0) { resolver.openFileDescriptor(bUri, any()) }
        vm.assertRetrySaves()
    }

    @Test
    fun `saveActiveDocumentAs - grant refused on the copy keeps A and never writes it`() =
        saveAsKeepsTheSource(SaveFault.OPEN_SECURITY)

    @Test
    fun `saveActiveDocumentAs - the write fails mid-way on the copy keeps A and never writes it`() =
        saveAsKeepsTheSource(SaveFault.WRITE_FAILS_AFTER_N_BYTES)

    private fun saveAsKeepsTheSource(fault: SaveFault) {
        every { resolver.takePersistableUriPermission(copyUri, any()) } throws
            SecurityException("No persistable permission grants found for $copyUri")
        val vm = failedSave(
            fault,
            faultUri = copyUri,
            faultFile = copyFile,
            save = { saveActiveDocumentAs(copyUri) },
        )
        verify(exactly = 0) { resolver.openFileDescriptor(aUri, "wt") }
        assertArrayEquals("$fault: Save-As must never touch the source", ORIGINAL, fileA.readBytes())
        vm.assertRetrySaves()
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val ORIGINAL_TEXT = "# A\n\nThe file as it was on disk.\n"

        // Larger than any pipe buffer, so a pipe-backed write really has to fail (FailingPipe).
        val EDITED_TEXT = "# A, edited\n\n" + (1..20_000).joinToString("") { "Edited line $it.\n" }
        val ORIGINAL = ORIGINAL_TEXT.toByteArray(Charsets.UTF_8)
        val EDITED = EDITED_TEXT.toByteArray(Charsets.UTF_8)
    }
}
