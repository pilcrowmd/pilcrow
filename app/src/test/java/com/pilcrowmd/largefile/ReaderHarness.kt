// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.largefile

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.di.Clock
import com.pilcrowmd.di.InstallSource
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.FolderAccessRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.ui.screen.MainScreen
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.WelcomePromptsViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.rules.TemporaryFolder
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The app's real reader path for M-172 / M-179 tests: a real [MarkdownViewModel] (production
 * factory wiring, only the file source is faked) driving the real [MainScreen].
 *
 * Deliberately NOT `MarkdownPreview` directly: the seam is the one both the unfixed and the fixed
 * code share — open a file, let the screen show it — so the SAME test decides both, and no test
 * helper can quietly do the parsing the production path is supposed to do.
 */
internal class ReaderHarness(
    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    tempFolder: TemporaryFolder,
) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storageScope = CoroutineScope(Dispatchers.IO + Job())
    val storage = LocalStorageManager(
        context,
        PreferenceDataStoreFactory.create(scope = storageScope) {
            File(tempFolder.newFolder(), "reader.preferences_pb")
        },
    )

    /** Main-thread time spent inside looper/compose work while [awaitReader] waited. */
    var mainThreadBusyMs = 0L
        private set

    fun close() = storageScope.cancel()

    /**
     * The ViewModel as `provideFactory` builds it, over a repository that serves [content]. Built
     * through the factory, not the constructor, so a dependency the fix adds is wired exactly as in
     * production.
     */
    fun viewModel(content: String): MarkdownViewModel {
        val container = object : com.pilcrowmd.di.AppContainer {
            override val fileRepository: FileRepository = FakeRepo(mapOf(), content)
            override val storageManager = storage
            override val draftRepository = com.pilcrowmd.repository.DraftRepository.None
            override val folderAccessRepository: FolderAccessRepository = mockk(relaxed = true)
            override val markwonRenderer by lazy { renderer }
            override val mermaidRenderer: MermaidDiagramRenderer = NullMermaidRenderer
            override val pdfExporter: com.pilcrowmd.export.PdfExporter = mockk(relaxed = true)
            override val parseMarkdownHeadingsUseCase = ParseMarkdownHeadingsUseCase()
            override val searchMarkdownUseCase = SearchMarkdownUseCase(parseMarkdownHeadingsUseCase)
            override val appInfo = object : AppInfo {
                override val versionName = "test"
            }
            override val installSource = object : InstallSource {
                override fun isPlayInstall() = false
                override fun firstInstallTimeMillis(): Long? = null
            }
            override val clock = Clock { 0L }
        }
        this.container = container
        return MarkdownViewModel.provideFactory(container).create(MarkdownViewModel::class.java)
    }

    private var container: com.pilcrowmd.di.AppContainer? = null
    private val renderer by lazy { MarkwonRenderer(context) }

    /**
     * A ViewModel whose reader model is built with [parse] instead of the reader's own — for a test
     * that must hold a parse open. Everything else is the production wiring; [contents] maps each
     * URI to the text the fake repository serves.
     */
    fun viewModel(contents: Map<Uri, String>, parse: (String) -> org.commonmark.node.Node): MarkdownViewModel {
        val headings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(contents, ""),
            storage = storage,
            parseHeadingsUseCase = headings,
            searchUseCase = SearchMarkdownUseCase(headings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            prepareReaderModel = com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase(
                parse = parse,
                headings = headings,
                inlineText = renderer::readerInlineText,
            ),
        )
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

    fun show(vm: MarkdownViewModel) {
        composeRule.setContent {
            MainScreen(
                viewModel = vm,
                prompts = remember { prompts() },
                context = LocalContext.current,
                renderer = remember { renderer },
                mermaidRenderer = NullMermaidRenderer,
            )
        }
    }

    /** The reader's list, if it is on screen. */
    fun readerList(): RecyclerView? = find(composeRule.activity.window.decorView)

    /**
     * Pump the main thread until [condition] holds, timing only the pumping (not the sleeps between
     * polls, which is where off-main work gets to run). Fails after [timeoutMs].
     */
    fun awaitReader(what: String, timeoutMs: Long = AWAIT_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val start = System.nanoTime()
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            mainThreadBusyMs += (System.nanoTime() - start) / NANOS_PER_MS
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out after $timeoutMs ms waiting for: $what")
    }

    /** The editor, if it is on screen. */
    fun editor(): io.github.rosemoe.sora.widget.CodeEditor? = findEditor(composeRule.activity.window.decorView)

    private fun findEditor(view: View): io.github.rosemoe.sora.widget.CodeEditor? = when (view) {
        is io.github.rosemoe.sora.widget.CodeEditor -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findEditor(view.getChildAt(it)) }
        else -> null
    }

    /** True once the reader list shows at least one block. */
    fun readerShowsBlocks(): Boolean = (readerList()?.adapter?.itemCount ?: 0) > 0

    private fun find(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
        else -> null
    }

    private class FakeRepo(private val contents: Map<Uri, String>, private val default: String) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(contents[uri] ?: default, isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = uri.lastPathSegment ?: "doc.md"
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    companion object {
        const val AWAIT_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 20L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
