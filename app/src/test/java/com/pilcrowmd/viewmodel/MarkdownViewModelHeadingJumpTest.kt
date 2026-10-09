// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-184: a headings jump is a one-shot command for the document it was tapped in. Before the fix it
 * stayed in [MarkdownViewModel.headingJump] for ever, so the next freshly composed reader (the same
 * file re-opened, another file, or the reader coming back from the editor) saw an "unhandled" jump
 * and ran it again.
 *
 * Fixture and [awaitValue] barrier mirror [MarkdownViewModelSearchResetTest]. Every "cleared"
 * assertion waits on `headingJump` turning NULL from a jump the test set itself and checked was
 * set, so the barrier is a change, never the flow's initial value.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelHeadingJumpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("heading_jump_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val contents: Map<Uri, String>) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(contents.getValue(uri), isUtf8 = true))
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

    private val uriA = Uri.parse("content://test/a.md")
    private val uriB = Uri.parse("content://test/b.md")

    private fun vm(): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(mapOf(uriA to "# One\n\nText.\n\n# Two\n\nMore.\n", uriB to "# Other\n\nB.\n")),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
        )
    }

    /** See [MarkdownViewModelRenderModeTest.awaitValue]: same barrier, same caveats. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    private fun MarkdownViewModel.loadAndAwait(uri: Uri) {
        loadFile(uri)
        awaitValue(uri, "document loaded") { currentDocument.value?.uri }
    }

    /** Document A open, the "Two" heading (block 2) jumped to and still pending. */
    private fun vmJumpedInA(): MarkdownViewModel {
        val vm = vm()
        vm.loadAndAwait(uriA)
        vm.jumpToHeading(HeadingNode(level = 1, text = "Two", adapterPosition = 2))
        assertNotNull("the jump is pending before the document changes", vm.headingJump.value)
        return vm
    }

    @Test
    fun openingAnotherDocumentDropsThePendingJump() {
        val vm = vmJumpedInA()

        vm.loadAndAwait(uriB)
        awaitValue(null, "no jump pending after opening B") { vm.headingJump.value }
    }

    /** The reported path: close the file, open it again. */
    @Test
    fun closingAndReopeningTheSameFileDropsThePendingJump() {
        val vm = vmJumpedInA()

        vm.closeFile()
        awaitValue(null, "document closed") { vm.currentDocument.value }
        vm.loadAndAwait(uriA)
        awaitValue(null, "no jump pending after re-opening A") { vm.headingJump.value }
    }

    /** Opening A again while A is on screen (from Recents): a new document instance, so no jump. */
    @Test
    fun reopeningTheOpenFileDropsThePendingJump() {
        val vm = vmJumpedInA()
        val before = vm.currentDocument.value!!.id

        vm.loadFile(uriA)
        awaitValue(true, "A re-published as a new instance") {
            vm.currentDocument.value?.id.let { it != null && it != before }
        }
        awaitValue(null, "no jump pending after re-opening A") { vm.headingJump.value }
    }

    /**
     * The acknowledge path: the surface that performed a jump reports its seq and the jump is gone.
     * A late report for an OLDER tap must not drop a newer jump the reader has not performed yet.
     * Synchronous calls on both sides, so the values read are the ones the calls left.
     */
    @Test
    fun aHandledJumpIsDroppedButAStaleAckKeepsANewerOne() {
        val vm = vmJumpedInA()
        val first = vm.headingJump.value!!.seq
        vm.jumpToHeading(HeadingNode(level = 1, text = "One", adapterPosition = 0))
        val second = vm.headingJump.value!!
        assertNotEquals("the second tap has its own seq", first, second.seq)

        vm.onHeadingJumpHandled(first)
        assertEquals("a stale ack keeps the newer jump", second, vm.headingJump.value)

        vm.onHeadingJumpHandled(second.seq)
        assertNull("the performed jump is dropped", vm.headingJump.value)
    }
}
