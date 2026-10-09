// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.widget.CodeEditor
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * An edit that reaches the ViewModel AFTER another document has been
 * published, but BEFORE the screen has recomposed onto it, must not land on the new document.
 *
 * The editor swaps documents only when `key(id)` recomposes, so for up to one frame after a load
 * publishes B the view still shows A and still carries A's content-change subscription. A change in
 * that window arrives as `updateContent(A's text)`, and the slot wrote it onto whatever document was
 * current — B. The editor then re-seeds from B's model, which now IS A's text, so nothing heals and
 * Save writes A's text into B's file.
 *
 * **The window is held open by pausing the Compose frame clock**, and the test proves it is open
 * before typing: after B's publish the view must still show A. Without the pause the frame runs
 * first, the keystroke lands in B's own editor, and the test passes for a reason unrelated to the
 * guard.
 */
@RunWith(RobolectricTestRunner::class)
class StaleEditorWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val aUri = Uri.parse("content://t/a.md")
    private val bUri = Uri.parse("content://t/b.md")
    private val aDisk = "alpha from A\n"
    private val bDisk = "bravo from B\n"

    private lateinit var storageScope: CoroutineScope

    @After
    fun tearDown() = storageScope.cancel()

    private val repo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            aUri -> Result.success(FileText(aDisk, isUtf8 = true))
            bUri -> Result.success(FileText(bDisk, isUtf8 = true))
            else -> Result.failure(IOException("unreadable: $uri"))
        }
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment!!
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("stale_editor_write.preferences_pb")
        }
        val parse = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = repo,
            storage = LocalStorageManager(context, ds),
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

    private fun findEditor(view: View): CodeEditor? = when (view) {
        is CodeEditor -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditor(view.getChildAt(it)) }
        else -> null
    }

    @Test
    fun anEditArrivingBeforeTheSwapRecomposesDoesNotLandOnTheNewDocument() {
        val vm = viewModel()
        val renderer = warmedMarkwonRenderer(compose.activity)
        compose.setContent {
            MaterialTheme {
                MainScreen(viewModel = vm, context = compose.activity, renderer = renderer)
            }
        }
        vm.loadFile(aUri)
        awaitTrue("A never finished loading") {
            vm.currentDocument.value?.uri == aUri && vm.fileLoadState.value == FileLoadState.Success
        }
        vm.setMode(ViewMode.EDITOR)
        compose.waitForIdle()
        val editor = findEditor(compose.activity.window.decorView)
        assertNotNull("the editor never composed", editor)
        assertEquals("precondition: the editor shows A", aDisk, editor!!.text.toString())

        // Hold the frame: B publishes, but the screen does not recompose onto it yet.
        compose.mainClock.autoAdvance = false
        vm.loadFile(bUri)
        awaitTrue("B never finished loading") {
            vm.currentDocument.value?.uri == bUri && vm.fileLoadState.value == FileLoadState.Success
        }
        assertEquals(
            "barrier: the view must still show A, or the window this test needs is shut",
            aDisk,
            editor.text.toString(),
        )

        // A keystroke in that window, delivered as the event a keystroke raises. The view's own
        // subscription, MainScreen's callback and the ViewModel all run for real; only Sora's text
        // mutation is skipped, so the event carries A's buffer as it stands. A real commitText
        // cannot run under Robolectric: the word-wrap layout task spins forever in the JDK's
        // RuleBasedBreakIterator (the stand-in for Android's ICU) holding the content read lock,
        // so the insert never gets the write lock (seen in a thread dump).
        editor.dispatchEvent(
            ContentChangeEvent(
                editor,
                ContentChangeEvent.ACTION_INSERT,
                CharPosition(0, 0, 0),
                CharPosition(0, 0, 0),
                "",
                false,
            ),
        )

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val b = vm.currentDocument.value!!
        assertEquals("B is still the open document", bUri, b.uri)
        // Exact content, not just "no A text": any other corruption of B fails here too.
        assertEquals("B's model must be exactly B's file", bDisk, b.content)
        assertFalse("B was marked dirty by an edit made on A", b.dirty)
        assertEquals("the editor must show exactly B", bDisk, editor.text.toString())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
