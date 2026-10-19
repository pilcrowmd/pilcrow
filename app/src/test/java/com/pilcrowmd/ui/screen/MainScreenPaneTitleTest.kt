// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.di.Clock
import com.pilcrowmd.di.InstallSource
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import com.pilcrowmd.viewmodel.WelcomePromptsViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * M-309: the screen TalkBack announces as the document opens is the pane title on MainScreen's
 * content box: "Reader, <file>" or "Editor, <file>", and none while no document is open (the
 * Welcome screen names itself). Composed with the real [MainScreen], so a title placed on the wrong
 * node, or left on after the document closes, fails it.
 *
 * The load is awaited on the state it publishes, not read on the line after.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenPaneTitleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val aUri = Uri.parse("content://t/a.md")

    private lateinit var storageScope: CoroutineScope
    private lateinit var storage: LocalStorageManager

    // Wait for the scope, not just cancel it: the TemporaryFolder rule deletes the folder after @After.
    @After
    fun tearDown() {
        val storageJob = storageScope.coroutineContext.job
        storageJob.cancel()
        awaitTrue("the storage scope never settled") { storageJob.isCompleted }
    }

    private val repo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            aUri -> Result.success(FileText("# Alpha\n\nbody\n", isUtf8 = true))
            else -> Result.failure(IOException("unreadable: $uri"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment!!
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private fun prompts() = WelcomePromptsViewModel(
        storage,
        object : InstallSource {
            override fun isPlayInstall() = false
            override fun firstInstallTimeMillis(): Long? = null
        },
        object : AppInfo {
            override val versionName = "test"
        },
        Clock { 0L },
    )

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("pane_title.preferences_pb")
        }
        val parse = ParseMarkdownHeadingsUseCase()
        storage = LocalStorageManager(context, ds)
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

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(message, condition())
    }

    // Material3's drawer sheet is on every screen, closed or not, and names itself "Navigation menu";
    // that is not the screen's pane. Ours ("Table of contents") is there only while the drawer opens.
    private fun paneTitles(): List<String> = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .map { it.config[SemanticsProperties.PaneTitle] }
        .filterNot { it == "Navigation menu" }

    private fun composeMainScreen(vm: MarkdownViewModel) {
        compose.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = vm,
                    prompts = remember { prompts() },
                    context = compose.activity,
                    renderer = MarkwonRenderer(compose.activity),
                    mermaidRenderer = NullMermaidRenderer,
                )
            }
        }
    }

    @Test
    fun thePaneFollowsTheOpenDocumentAndMode() {
        val vm = viewModel()
        composeMainScreen(vm)
        compose.waitForIdle()
        assertEquals("no document: only the Welcome screen names itself", listOf("PilcrowMD"), paneTitles())

        vm.loadFile(aUri)
        awaitTrue("A never finished loading") {
            vm.currentDocument.value?.uri == aUri && vm.fileLoadState.value == FileLoadState.Success
        }
        compose.waitForIdle()
        assertEquals(listOf("Reader, a.md"), paneTitles())

        vm.setMode(ViewMode.EDITOR)
        awaitTrue("the editor mode never published") { vm.mode.value == ViewMode.EDITOR }
        compose.waitForIdle()
        assertEquals(listOf("Editor, a.md"), paneTitles())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
