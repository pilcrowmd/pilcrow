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
import com.pilcrowmd.repository.FileTooLargeException
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.RecentFile
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Crash-loop guard for the cold-start restore. A remembered file whose load or first draw kills the
 * process used to be reopened at every start, so the app could never be reached to close it. Now a
 * durable "restore in progress" flag is written BEFORE the file is read and cleared once the
 * document has actually been shown; a start that finds the flag still set does not reopen the file.
 *
 * Every wait below is on state the call under test publishes, never on a value that already equals
 * what is expected (the project rule on tests that cannot fail): the "not reopened" flag starts
 * false, and the marker is waited on while it is true.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelRestoreGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val remembered = Uri.parse("content://t/b.md")
    private val opened = Uri.parse("content://t/a.md")

    /**
     * Records every read, and whether the restore flag was already on disk at that moment. A read of
     * [gated] waits on [gate]; a read of [failing] fails with [failure].
     */
    private inner class Repo(
        private val gated: Uri? = null,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val failing: Uri? = null,
        private val failure: Throwable = java.io.IOException("unreadable"),
    ) : FileRepository {
        val reads = CopyOnWriteArrayList<Uri>()
        val flagAtRead = CopyOnWriteArrayList<Boolean>()
        val readStarted = CompletableDeferred<Unit>()

        override suspend fun readFile(uri: Uri): Result<FileText> {
            reads += uri
            flagAtRead += storage.isRestoreInProgress()
            readStarted.complete(Unit)
            if (uri == gated) gate.await()
            if (uri == failing) return Result.failure(failure)
            return Result.success(FileText("# ${uri.lastPathSegment}\n", isUtf8 = true))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("restore_guard_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
        runBlocking {
            storage.saveLastFileUri(remembered)
            storage.addRecent(RecentFile(remembered, "b.md", 1L))
        }
    }

    @After
    fun tearDown() = storageScope.cancel()

    /**
     * The real storage, except that the restore flag's writes (and the pointer's clear) fail like a
     * DataStore write on a full disk. Reads of the flag stay real, so a test can still plant one.
     */
    private inner class FailingWrites(
        private val failFlagWrites: Boolean = true,
        private val failPointerClear: Boolean = false,
    ) : StorageManager by storage {
        override suspend fun setRestoreInProgress(inProgress: Boolean) {
            if (failFlagWrites) throw java.io.IOException("disk full")
            storage.setRestoreInProgress(inProgress)
        }

        override suspend fun clearLastFileUri() {
            if (failPointerClear) throw java.io.IOException("disk full")
            storage.clearLastFileUri()
        }
    }

    private fun vmWith(repo: FileRepository, store: StorageManager = storage): MarkdownViewModel {
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = store,
            parseHeadingsUseCase = parse,
            searchUseCase = SearchMarkdownUseCase(parse),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            cpuDispatcher = Dispatchers.Unconfined,
        )
    }

    /** Polls while idling the main looper. Real wall-clock deadline: Robolectric does not fake it here. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(message, expected, actual())
    }

    /** Idle for a fixed spell, so work that is still able to run gets every chance to run. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun persisted(): Uri? = runBlocking { storage.lastFileUri.first() }
    private fun marker(): Boolean = runBlocking { storage.isRestoreInProgress() }
    private fun recents(): List<Uri> = runBlocking { storage.recentFiles.first().map { it.uri } }

    @Test
    fun `a restore left in progress is not reopened, the pointer is dropped and the file stays in Recent`() {
        runBlocking { storage.setRestoreInProgress(true) }
        val repo = Repo()
        val vm = vmWith(repo)

        awaitValue(true, "the start screen was never told the last file was not reopened") {
            vm.lastFileNotReopened.value
        }
        settle()

        assertTrue("the remembered file was read although the last restore never finished", repo.reads.isEmpty())
        assertEquals("nothing may be open", null, vm.currentDocument.value)
        assertEquals("the pointer must be dropped, or the next start repeats this", null, persisted())
        assertEquals("the flag must be cleared, or the next start refuses again", false, marker())
        assertEquals("the file must stay in Recent", listOf(remembered), recents())
    }

    @Test
    fun `a normal start sets the flag before the read and showing the document clears it`() {
        val repo = Repo()
        val vm = vmWith(repo)

        awaitValue(remembered, "the restore never opened the remembered file") { vm.currentDocument.value?.uri }
        settle()

        assertEquals("the flag must be on disk before the file is read", listOf(true), repo.flagAtRead.toList())
        assertEquals("the flag must stay until the UI has shown the document", true, marker())
        assertEquals("nothing was skipped, so nothing may be said", false, vm.lastFileNotReopened.value)

        vm.onRestoredDocumentShown()

        awaitValue(false, "showing the document never cleared the flag") { marker() }
        assertEquals("the pointer must stay for the next start", remembered, persisted())
    }

    @Test
    fun `an open the user starts during the restore clears the flag`() {
        val repo = Repo(gated = remembered, gate = CompletableDeferred())
        val vm = vmWith(repo)
        awaitValue(true, "the restore never reached the read") { repo.readStarted.isCompleted }
        assertEquals("the flag must be on while the restore is reading", true, marker())

        vm.openFromIntent(opened)

        awaitValue(opened, "the opened file never appeared") { vm.currentDocument.value?.uri }
        awaitValue(false, "an explicit open left the flag set, so the next start would refuse its file") { marker() }
        repo.gate.complete(Unit)
        settle()
        assertEquals("the cancelled restore must not have set it again", false, marker())
    }

    @Test
    fun `a restore whose read fails clears the flag`() {
        val repo = Repo(failing = remembered)
        val vm = vmWith(repo)
        awaitValue(true, "the restore never reached the read") { repo.readStarted.isCompleted }
        // The flag is false before and after a restore, so it only proves anything once seen on.
        assertEquals("the flag must be on disk before the read", listOf(true), repo.flagAtRead.toList())

        awaitValue(false, "a failed restore left the flag set") { marker() }
        assertTrue("the failure must be reported", vm.fileLoadState.value is FileLoadState.Error)
        assertEquals("the pointer stays: only a crash drops it", remembered, persisted())
    }

    @Test
    fun `a remembered file over the read limit is reported as too large`() {
        val repo = Repo(failing = remembered, failure = FileTooLargeException("too big"))
        val vm = vmWith(repo)

        awaitValue(true, "the load never failed") { vm.fileLoadState.value is FileLoadState.Error }

        assertEquals(true, (vm.fileLoadState.value as FileLoadState.Error).tooLarge)
    }

    @Test
    fun `a restore whose flag cannot be written still opens the file and does not crash`() {
        val vm = vmWith(Repo(), FailingWrites())

        awaitValue(remembered, "the failed flag write stopped the restore from opening the file") {
            vm.currentDocument.value?.uri
        }
        settle()

        assertEquals("the pointer stays", remembered, persisted())
    }

    @Test
    fun `a restore that fails to read and cannot clear its flag does not crash`() {
        val vm = vmWith(Repo(failing = remembered), FailingWrites())

        awaitValue(true, "the load never failed") { vm.fileLoadState.value is FileLoadState.Error }
        settle()

        assertEquals("the pointer stays: only a crash drops it", remembered, persisted())
    }

    @Test
    fun `a skipped restore whose pointer and flag cannot be cleared still tells the start screen`() {
        runBlocking { storage.setRestoreInProgress(true) }
        val repo = Repo()
        val vm = vmWith(repo, FailingWrites(failPointerClear = true))

        awaitValue(true, "the start screen was never told the last file was not reopened") {
            vm.lastFileNotReopened.value
        }
        settle()

        assertTrue("the remembered file was read although the last restore never finished", repo.reads.isEmpty())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_ROUNDS = 60
    }
}
