// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * S8-A43: the Settings switch "Full-screen reading" reaches the ViewModel, and from there the
 * reader. S8-A41 shipped with the screen, the policy and the stored value each tested alone and
 * nothing joining them, so a switch wired to nothing passed the whole gate.
 *
 * With the real [MainScreen]: switch on in Settings, back out, open a note. The ViewModel must say
 * on, and the top bar (found by its "More actions" menu) must be gone from the reader. Both waits
 * are on state the switch publishes (the stored value, the open document), never on the line after.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenFullScreenSwitchTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val note = Uri.parse("content://docs/note")

    private lateinit var storageScope: CoroutineScope

    @After
    fun tearDown() {
        val storageJob = storageScope.coroutineContext.job
        storageJob.cancel()
        awaitTrue("the storage scope never settled") { storageJob.isCompleted }
    }

    private val fileRepo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            note -> Result.success(FileText("# Plan\n\nbody\n", isUtf8 = true))
            else -> Result.failure(IOException("unreadable"))
        }
        override suspend fun saveFile(uri: Uri, content: String): Result<Unit> = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String = "plan.md"
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    private lateinit var storage: LocalStorageManager

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("full_screen_switch.preferences_pb")
        }
        val parse = ParseMarkdownHeadingsUseCase()
        storage = LocalStorageManager(context, ds)
        return MarkdownViewModel(
            repository = fileRepo,
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

    private fun moreActionsShown() =
        compose.onAllNodesWithContentDescription("More actions").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theSettingsSwitchReachesTheViewModelAndHidesTheReadersTopBar() {
        val vm = viewModel()
        compose.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = vm,
                    prompts = remember {
                        WelcomePromptsViewModel(
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
                    },
                    context = compose.activity,
                    renderer = MarkwonRenderer(compose.activity),
                    mermaidRenderer = NullMermaidRenderer,
                )
            }
        }
        compose.waitForIdle()
        assertEquals("precondition: the switch starts off", false, vm.fullScreenReading.value)

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Full-screen reading").performScrollTo().performClick()
        awaitTrue("the switch never reached the ViewModel") { vm.fullScreenReading.value }

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // leave Settings
        compose.waitForIdle()
        vm.loadFile(note)
        awaitTrue("the note never finished loading") {
            vm.currentDocument.value?.uri == note && vm.fileLoadState.value == FileLoadState.Success
        }
        compose.waitForIdle()
        assertTrue("the reader still shows the top bar with the switch on", !moreActionsShown())
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
