// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Unit tests for the WAL escape-hatch ViewModel surface: the
 * cold-launch-vs-intent dialog gating, `rescueStrandedSlot` (raw bytes, never touches the open
 * document, success discards), `discardStrandedSlot`, and N-slot independence.
 *
 * Same harness as the Save-As tests: real StorageManager + a configurable fake repository whose
 * suspend fns return inline, so viewModelScope launches (Robolectric Main) complete synchronously.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelStrandedSlotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("stranded_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(
        private val readContent: String = "doc\n",
        val slots: MutableList<StrandedSlot> = mutableListOf(),
        private val rescueSucceeds: Boolean = true,
        private val takePermSucceeds: Boolean = true,
    ) : FileRepository {
        val rescuedTargets = linkedMapOf<String, Uri>() // slotKey -> targetUri written
        var takePermCalls = 0

        override suspend fun readFile(uri: Uri) = Result.success(readContent)
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)

        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> {
            takePermCalls++
            return if (takePermSucceeds) Result.success(Unit) else Result.failure(SecurityException("no"))
        }

        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true

        override suspend fun strandedSlots() = Result.success(slots.toList())

        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri): Result<Unit> =
            if (rescueSucceeds) {
                rescuedTargets[slotKey] = targetUri
                slots.removeAll { it.key == slotKey } // success → discard
                Result.success(Unit)
            } else {
                Result.failure(IOException("rescue failed (test)"))
            }

        override suspend fun discardSlot(key: String): Result<Unit> {
            slots.removeAll { it.key == key }
            return Result.success(Unit)
        }
    }

    private fun vmWith(repo: FileRepository): MarkdownViewModel {
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

    private fun slot(n: Int) = StrandedSlot("key$n", Uri.parse("content://test/lost$n.md"), "lost$n.md")

    @Test
    fun coldLauncherStartWithSlotsAutoOpensDialog() = runTest {
        val repo = FakeRepo(slots = mutableListOf(slot(1), slot(2)))
        val vm = vmWith(repo)

        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        assertEquals(2, vm.strandedSlots.value.size)
        assertTrue("launcher start + slots → dialog auto-opens", vm.strandedDialogVisible.value)
    }

    @Test
    fun fileOpenLaunchSurfacesSlotsButNotTheDialog() = runTest {
        val repo = FakeRepo(slots = mutableListOf(slot(1)))
        val vm = vmWith(repo)

        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = true)

        assertEquals("slots still detected", 1, vm.strandedSlots.value.size)
        assertFalse("intent open → no auto-dialog (indicator only)", vm.strandedDialogVisible.value)
    }

    @Test
    fun noSlotsNoDialog() = runTest {
        val vm = vmWith(FakeRepo(slots = mutableListOf()))

        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        assertTrue(vm.strandedSlots.value.isEmpty())
        assertFalse(vm.strandedDialogVisible.value)
    }

    @Test
    fun startEvaluationIsOnceOnly() = runTest {
        // Guards the rotation re-run: after a dismiss, a second evaluation must not re-pop the dialog.
        val vm = vmWith(FakeRepo(slots = mutableListOf(slot(1))))
        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)
        assertTrue(vm.strandedDialogVisible.value)
        vm.dismissStrandedDialog()
        assertFalse(vm.strandedDialogVisible.value)

        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        assertFalse("second evaluation is a no-op (once-only guard)", vm.strandedDialogVisible.value)
    }

    @Test
    fun rescueStrandedSlotNeverTouchesTheOpenDocument() = runTest {
        val docUri = Uri.parse("content://test/open.md")
        val repo = FakeRepo(readContent = "open doc\n", slots = mutableListOf(slot(1)))
        val vm = vmWith(repo)
        vm.loadFile(docUri)
        val before = vm.currentDocument.value!!

        vm.rescueStrandedSlot(Uri.parse("content://test/rescued1.md"), "key1")

        val after = vm.currentDocument.value!!
        assertEquals("open document URI unchanged", before.uri, after.uri)
        assertEquals("open document content unchanged", before.content, after.content)
        assertEquals("open document dirty flag unchanged", before.dirty, after.dirty)
        assertTrue("the slot was rescued", repo.rescuedTargets.containsKey("key1"))
        assertTrue("rescued slot cleared from the list", vm.strandedSlots.value.isEmpty())
    }

    @Test
    fun rescuingOneSlotLeavesOthers() = runTest {
        val repo = FakeRepo(slots = mutableListOf(slot(1), slot(2)))
        val vm = vmWith(repo)
        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        vm.rescueStrandedSlot(Uri.parse("content://test/r1.md"), "key1")

        assertEquals(1, vm.strandedSlots.value.size)
        assertEquals("key2", vm.strandedSlots.value.first().key)
    }

    @Test
    fun rescueProceedsEvenIfTakePersistablePermissionFails() = runTest {
        val repo = FakeRepo(slots = mutableListOf(slot(1)), takePermSucceeds = false)
        val vm = vmWith(repo)
        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        vm.rescueStrandedSlot(Uri.parse("content://test/r1.md"), "key1")

        assertTrue("takePersistableUriPermission was attempted", repo.takePermCalls > 0)
        assertTrue("rescue still wrote despite the permission failure", repo.rescuedTargets.containsKey("key1"))
        assertTrue(vm.strandedSlots.value.isEmpty())
    }

    @Test
    fun discardStrandedSlotRemovesIt() = runTest {
        val repo = FakeRepo(slots = mutableListOf(slot(1), slot(2)))
        val vm = vmWith(repo)
        vm.evaluateStrandedSlotsOnStart(openingFileFromIntent = false)

        vm.discardStrandedSlot("key1")

        assertEquals(1, vm.strandedSlots.value.size)
        assertEquals("key2", vm.strandedSlots.value.first().key)
        assertTrue("discard never rescued/wrote anything", repo.rescuedTargets.isEmpty())
    }
}
