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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * M-177's "documents opened" rule: every open the user starts that reaches
 * a loaded document counts — picker, "Open with", recents (all [MarkdownViewModel.loadFile] or
 * [MarkdownViewModel.openPickedFile]) and Create. A failed load does not, and neither does the
 * cold-start restore.
 *
 * **The "does not count" cases read [CountingStorage.calls], never the stored count.** The stored
 * count is written through DataStore on another thread, so "still 0" there proves only "not yet" —
 * the first version of this suite read it and passed against code that counted failures (mutation
 * `failed-load-counts`). The spy records the CALL, which happens on Main in the same uninterrupted
 * slice as the state the test waits on, so once that state is visible the call has either happened
 * or never will.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelOpenCountTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: CountingStorage
    private lateinit var storageScope: CoroutineScope

    private val good = Uri.parse("content://test/good.md")
    private val other = Uri.parse("content://test/other.md")
    private val broken = Uri.parse("content://test/broken.md")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        storage = CountingStorage(
            LocalStorageManager(
                context,
                PreferenceDataStoreFactory.create(scope = storageScope) { tempFolder.newFile("opens.preferences_pb") },
            ),
        )
    }

    @After
    fun tearDown() = storageScope.cancel()

    /**
     * Counts increment calls as they are made, and marks each finished recents write — the last step
     * of every load — so a test can wait for a load, restore included, to be entirely over.
     */
    private class CountingStorage(private val inner: StorageManager) : StorageManager by inner {
        @Volatile var calls = 0

        /** When set, every count write throws after being recorded, as a failing DataStore edit would. */
        @Volatile var countWriteThrows = false
        val recentsWritten = mutableListOf<Uri>()
        override suspend fun incrementDocumentsOpened() {
            calls++
            if (countWriteThrows) throw IOException("count write failed")
            inner.incrementDocumentsOpened()
        }
        override suspend fun addRecent(file: RecentFile) {
            inner.addRecent(file)
            recentsWritten += file.uri
        }
    }

    private class Repo(private val failing: Uri) : FileRepository {
        /** When set, the NEXT read parks here until the test completes it; later reads do not. */
        @Volatile var nextReadGate: CompletableDeferred<Unit>? = null

        @Volatile var reads = 0

        /** Reads that have come back from their gate (or had none). */
        @Volatile var readsReturned = 0

        override suspend fun readFile(uri: Uri): Result<FileText> {
            reads++
            nextReadGate?.also { nextReadGate = null }?.await()
            readsReturned++
            return if (uri == failing) {
                Result.failure(IOException("unreadable"))
            } else {
                Result.success(FileText("# doc\n", isUtf8 = true))
            }
        }
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun vm(repo: FileRepository = Repo(failing = broken)): MarkdownViewModel {
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

    private fun opens() = runBlocking { storage.documentsOpened.first() }

    /** Same polling barrier as [MarkdownViewModelRenderModeTest.awaitValue]. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    @Test
    fun `a loaded open counts, and re-opening the same file counts again`() {
        val vm = vm()
        vm.loadFile(good)
        awaitValue(1, "first open counted") { opens() }
        vm.loadFile(other)
        awaitValue(2, "second open counted") { opens() }
        vm.loadFile(good)
        awaitValue(3, "re-opening counts again") { opens() }
    }

    @Test
    fun `a picked file counts`() {
        vm().openPickedFile(good)
        awaitValue(1, "picker open counted") { opens() }
    }

    @Test
    fun `Create counts`() {
        vm().newDocument()
        awaitValue(1, "Create counted") { opens() }
    }

    @Test
    fun `a failed load does not count`() {
        val vm = vm()
        vm.loadFile(broken)
        awaitValue(true, "the failure has landed") { vm.fileLoadState.value is FileLoadState.Error }
        assertEquals("a failed load was counted", 0, storage.calls)
    }

    /**
     * A double tap on a recent: two opens of the same file, the second finishing first. The first is
     * superseded and must not count, although the file it asked for is on screen and loaded — which
     * is exactly what a check of the screen's state after the load would see.
     */
    @Test
    fun `a superseded open of the same file does not count`() {
        val repo = Repo(failing = broken).apply { nextReadGate = CompletableDeferred() }
        val gate = repo.nextReadGate!!
        val vm = vm(repo)
        vm.loadFile(good) // parks in its read
        awaitValue(1, "the first open never started reading") { repo.reads }
        vm.loadFile(good) // started later, finishes first
        awaitValue(1, "the second open was not counted") { storage.calls }

        gate.complete(Unit)
        awaitValue(2, "the first open's read never came back") { repo.readsReturned }
        // Only ever used to show that something did NOT happen: let the first load run to its end.
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertEquals("the superseded open was counted", 1, storage.calls)
    }

    /**
     * The restore's recents write is its last step, and nothing suspends between it returning and
     * where a count would be made — so once it is recorded the restore is over, counted or not.
     */
    @Test
    fun `the cold-start restore does not count`() {
        runBlocking { storage.saveLastFileUri(good) }
        vm()
        awaitValue(true, "the restore finished") { good in storage.recentsWritten }
        assertEquals("the restore was counted", 0, storage.calls)
    }

    /**
     * The count is the last write of an open, so a count write that throws cannot cost the user the
     * recents entry. Waits on the recents write itself: with the count written first, the throw ends
     * the load before it and the entry never appears.
     */
    @Test
    fun `a throwing count write still records the recent`() {
        storage.countWriteThrows = true
        vm().loadFile(good)
        awaitValue(true, "the recents entry was not written") { good in storage.recentsWritten }
        assertEquals("the open was not counted", 1, storage.calls)
    }

    private companion object {
        const val SETTLE_ROUNDS = 40
    }
}
