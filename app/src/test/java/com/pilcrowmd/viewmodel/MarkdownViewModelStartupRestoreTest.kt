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

/**
 * **M-145.** On a cold start the ViewModel restores the remembered file B while the file the user
 * just opened, A, loads too. Both loads publish and persist, so whichever finishes last wins, and
 * on a real device the restore finished last every time: the user tapped A and was shown B.
 *
 * The fixture forces that order and nothing else decides the outcome. B's read is parked on a gate
 * until A is fully open, then released. Neither of `openFromIntent`'s guards can answer first:
 * nothing is open when A arrives, so the same-file check and the unsaved-edits check both pass
 * straight through to the load.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelStartupRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val remembered = Uri.parse("content://t/b.md")
    private val opened = Uri.parse("content://t/a.md")

    /**
     * Reads of [gated] wait on [restoreGate]; every other read waits on [openGate], which is open
     * unless a test closes it to hold the explicit open mid-load.
     */
    private class Repo(
        private val gated: Uri,
        val restoreGate: CompletableDeferred<Unit>,
        val openGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : FileRepository {
        val restoreReadStarted = CompletableDeferred<Unit>()

        override suspend fun readFile(uri: Uri): Result<String> {
            if (uri == gated) {
                restoreReadStarted.complete(Unit)
                restoreGate.await()
            } else {
                openGate.await()
            }
            return Result.success("# ${uri.lastPathSegment}\n")
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
            tempFolder.newFile("startup_restore_test.preferences_pb")
        }
        storage = LocalStorageManager(context, ds)
        runBlocking { storage.saveLastFileUri(remembered) }
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vmWith(repo: FileRepository): MarkdownViewModel {
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

    /** Idle for a fixed spell, so a load that is still able to run gets every chance to finish. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun persisted(): Uri? = runBlocking { storage.lastFileUri.first() }

    /**
     * Park the restore inside B's read, open A through [open], wait until A is open and persisted,
     * then let B's read return and give it time to publish. The file on screen and the file the
     * app remembers must both still be A.
     */
    private fun assertOpenBeatsParkedRestore(open: MarkdownViewModel.(Uri) -> Unit) {
        val repo = Repo(gated = remembered, restoreGate = CompletableDeferred())
        val vm = vmWith(repo)
        awaitValue(true, "the startup restore never reached B's read") { repo.restoreReadStarted.isCompleted }

        vm.open(opened)
        awaitValue(opened, "A never opened") { vm.currentDocument.value?.uri }
        awaitValue(opened, "A was never persisted as the last file") { persisted() }

        repo.restoreGate.complete(Unit)
        settle()

        assertEquals("the restore replaced the file the user opened", opened, vm.currentDocument.value?.uri)
        assertEquals("the restore overwrote the remembered file", opened, persisted())
    }

    @Test
    fun `an Open with that arrives during the startup restore is the file that stays open`() {
        assertOpenBeatsParkedRestore { openFromIntent(it) }
    }

    @Test
    fun `a file picked during the startup restore is the file that stays open`() {
        assertOpenBeatsParkedRestore { openPickedFile(it) }
    }

    /**
     * Why the restore is JOINED, not just cancelled. The cancelled restore's handler resets `Loading`
     * to `Idle`. Without the join, that reset runs after the explicit open has set `Loading`, and
     * the welcome screen, which disables its actions while `Loading`, re-enables them in the middle
     * of the load. A's read is held open here so that the middle of the load is observable.
     */
    @Test
    fun `stopping the restore does not reset the state of the open that stopped it`() {
        val repo = Repo(gated = remembered, restoreGate = CompletableDeferred(), openGate = CompletableDeferred())
        val vm = vmWith(repo)
        awaitValue(true, "the startup restore never reached B's read") { repo.restoreReadStarted.isCompleted }

        vm.openFromIntent(opened)
        settle()

        assertEquals(
            "the cancelled restore reset Loading to Idle while A was still loading",
            FileLoadState.Loading,
            vm.fileLoadState.value,
        )
        repo.openGate.complete(Unit)
        awaitValue(opened, "A never opened") { vm.currentDocument.value?.uri }
    }

    /** Control: when the restore has already finished, opening A simply replaces B, before and after the fix. */
    @Test
    fun `an Open with after the restore has finished replaces the restored file`() {
        val repo = Repo(gated = remembered, restoreGate = CompletableDeferred(Unit))
        val vm = vmWith(repo)
        awaitValue(remembered, "the startup restore never opened B") { vm.currentDocument.value?.uri }

        vm.openFromIntent(opened)
        awaitValue(opened, "A never opened") { vm.currentDocument.value?.uri }
        awaitValue(opened, "A was never persisted as the last file") { persisted() }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_ROUNDS = 60
    }
}
