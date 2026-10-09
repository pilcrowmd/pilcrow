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
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import io.github.rosemoe.sora.widget.CodeEditor
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-184, editor side: a headings jump tapped in Editor mode is performed by MainScreen's editor
 * effect, which must acknowledge it. Otherwise it stays pending, and the reader composed when the
 * user switches back to Reader mode runs it a second time.
 *
 * The fixture leaves the editor effect as the ONLY thing that can clear the jump: the document does
 * not change (so the ViewModel's document-change reset cannot fire), and the reader has left
 * composition before the tap (checked: the editor is on screen), so the reader's own acknowledgement
 * cannot answer first. Setup mirrors [StaleEditorWriteTest].
 */
@RunWith(RobolectricTestRunner::class)
class EditorHeadingJumpAckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val docUri = Uri.parse("content://t/doc.md")
    private val docText = "# One\n\nText.\n\n# Two\n\nMore.\n"

    private lateinit var storageScope: CoroutineScope

    @After
    fun tearDown() = storageScope.cancel()

    private val repo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = Result.success(FileText(docText, isUtf8 = true))
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
            tempFolder.newFile("editor_heading_jump_ack.preferences_pb")
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
            compose.waitForIdle()
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
    fun aJumpPerformedInTheEditorIsAcknowledged() {
        val vm = viewModel()
        compose.setContent {
            MaterialTheme {
                MainScreen(viewModel = vm, context = compose.activity, renderer = MarkwonRenderer(compose.activity))
            }
        }
        vm.loadFile(docUri)
        awaitTrue("the document never finished loading") {
            vm.currentDocument.value?.uri == docUri && vm.fileLoadState.value == FileLoadState.Success
        }
        awaitTrue("the headings were never parsed") { vm.headings.value.size == 2 }
        vm.setMode(ViewMode.EDITOR)
        compose.waitForIdle()
        assertNotNull(
            "barrier: the editor is on screen, so the reader has left",
            findEditor(compose.activity.window.decorView),
        )

        vm.jumpToHeading(vm.headings.value.last())
        assertNotNull("the jump is pending before the editor runs it", vm.headingJump.value)

        awaitTrue("the editor performed the jump but never acknowledged it") { vm.headingJump.value == null }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
