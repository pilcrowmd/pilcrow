// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.di.DefaultAppContainer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import com.pilcrowmd.viewmodel.MarkdownViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * **M-139.** The launch intent must be handled once, not once per Activity. `onCreate` used to run
 * `handleIntentFile(intent)` on every recreation, and `getIntent()` survives recreation, so a
 * rotation re-published the launch file and — on a clean document — loaded it over whatever the
 * user had opened since, with no dialog.
 *
 * The fixture's open document (B) is deliberately a DIFFERENT file from the intent file (A):
 * `openFromIntent` returns early when the intent URI is already open, so with A on screen that
 * no-op would answer before the intent handling under test is ever reached, and the test would
 * pass for a reason unrelated to what it claims to check.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityIntentReplayTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fileA = Uri.parse("content://m139/a.md")
    private val fileB = Uri.parse("content://m139/b.md")
    private val fileC = Uri.parse("content://m139/c.md")

    private lateinit var repo: CountingRepo
    private lateinit var storageScope: CoroutineScope

    /** Serves any URI and counts reads per URI, so a replayed load is observable. */
    private class CountingRepo : FileRepository {
        val reads = ConcurrentHashMap<Uri, AtomicInteger>()
        fun readsOf(uri: Uri) = reads[uri]?.get() ?: 0

        override suspend fun readFile(uri: Uri): Result<String> {
            reads.getOrPut(uri) { AtomicInteger() }.incrementAndGet()
            return Result.success("# ${uri.lastPathSegment}\n")
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri): Result<Unit> = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri): Boolean = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean = true
        override suspend fun strandedSlots() = Result.success(emptyList<StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<PilcrowApplication>()
        repo = CountingRepo()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val storage: StorageManager = LocalStorageManager(
            app,
            PreferenceDataStoreFactory.create(scope = storageScope) {
                tempFolder.newFile("m139_test.preferences_pb")
            },
        )
        val real = DefaultAppContainer(app)
        app.container = object : AppContainer by real {
            override val fileRepository: FileRepository = repo
            override val storageManager: StorageManager = storage
        }
    }

    @After
    fun tearDown() = storageScope.cancel()

    // setDataAndType, not setType: Intent.setType() CLEARS the data URI, which silently turns the
    // launch into a plain start with no file to open.
    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/markdown")

    private fun ActivityController<MainActivity>.viewModel(): MarkdownViewModel {
        val container = ApplicationProvider.getApplicationContext<PilcrowApplication>().container
        // Same default key as Compose's viewModel(), so this returns the instance MainScreen uses.
        return ViewModelProvider(get(), MarkdownViewModel.provideFactory(container))[MarkdownViewModel::class.java]
    }

    /**
     * Advance the main looper by one frame. A bare `idle()` does not move the clock, and Compose's
     * recomposer runs on Choreographer frame callbacks, so without this `PilcrowApp`'s
     * LaunchedEffect — the only consumer of the intent — never runs at all.
     */
    private fun frame() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            frame()
            if (actual() == expected) return
            Thread.sleep(POLL_MS)
        }
        assertEquals(message, expected, actual())
    }

    /** Idle long enough for a replayed publish -> LaunchedEffect -> loadFile to have landed. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            frame()
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun `a recreation does not re-open the launch file over the document the user opened since`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, viewIntent(fileA)).setup()
        awaitValue(fileA, "launch file A opened") { controller.viewModel().currentDocument.value?.uri }

        controller.viewModel().loadFile(fileB)
        awaitValue(fileB, "B opened from inside the app") { controller.viewModel().currentDocument.value?.uri }

        controller.recreate()
        settle()

        assertEquals(
            "a recreation replayed the launch intent and replaced B with A",
            fileB,
            controller.viewModel().currentDocument.value?.uri,
        )
        assertEquals("A must be read exactly once", 1, repo.readsOf(fileA))
    }

    @Test
    fun `a new Open-with after a recreation still opens`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, viewIntent(fileA)).setup()
        awaitValue(fileA, "launch file A opened") { controller.viewModel().currentDocument.value?.uri }

        controller.recreate()
        settle()
        controller.newIntent(viewIntent(fileC))

        awaitValue(fileC, "C, delivered by onNewIntent after a recreation, must open") {
            controller.viewModel().currentDocument.value?.uri
        }
    }

    @Test
    fun `a recreation before the launch intent is consumed still opens the launch file`() {
        // create() only: the content is never composed, so PilcrowApp's LaunchedEffect never runs
        // and the intent is published but NOT consumed when the Activity is recreated.
        val controller = Robolectric.buildActivity(MainActivity::class.java, viewIntent(fileA)).create()
        controller.recreate()
        controller.start().resume().visible()

        awaitValue(fileA, "a cold Open-with recreated before consumption must still open A") {
            controller.viewModel().currentDocument.value?.uri
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val FRAME_MS = 17L
        const val SETTLE_ROUNDS = 100
    }
}
