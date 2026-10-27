// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.di.Clock
import com.pilcrowmd.di.InstallSource
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.NoteEntry
import com.pilcrowmd.domain.folders.SeenRecord
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.repository.AddFolderResult
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.FolderListing
import com.pilcrowmd.repository.NotesFolder
import com.pilcrowmd.repository.NotesFolderRepository
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.NoteMarksStore
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StoredFolder
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.NotesFoldersViewModel
import com.pilcrowmd.viewmodel.WelcomePromptsViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import org.robolectric.shadows.ShadowDialog
import java.io.IOException

/**
 * S8-A38: with the real [MainScreen], a note opened from My folders closes back to My folders, and a
 * note opened any other way closes to Welcome. The pure rule is in FilesReturnTest; this is the
 * wiring: that the pick is recorded, that the close is seen, and that Welcome is not shown instead.
 *
 * My folders is told apart from Welcome by "Add folder", which only it has. Every step waits on the
 * state it publishes (the open document, the load state), never on the line after the call.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenFilesReturnTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val tree = Uri.parse("content://docs/tree/Notes")
    private val note = DocumentsContract.buildDocumentUriUsingTree(tree, "plan-id")
    private val other = DocumentsContract.buildDocumentUriUsingTree(tree, "other-id")

    /** While set, other.md cannot be read: the open of a note picked in My folders fails. */
    private var otherUnreadable = false

    private lateinit var storageScope: CoroutineScope
    private lateinit var storage: LocalStorageManager

    @After
    fun tearDown() {
        val storageJob = storageScope.coroutineContext.job
        storageJob.cancel()
        awaitTrue("the storage scope never settled") { storageJob.isCompleted }
    }

    private val fileRepo = object : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> = when (uri) {
            note -> Result.success(FileText(PLAN_TEXT, isUtf8 = true))
            other -> if (otherUnreadable) {
                Result.failure(IOException("unreadable: $uri"))
            } else {
                Result.success(FileText("# Other\n\nbody\n", isUtf8 = true))
            }
            else -> Result.failure(IOException("unreadable: $uri"))
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

    /** One added folder holding plan.md and other.md. */
    private val folderRepo = object : NotesFolderRepository {
        override val folders = MutableStateFlow(listOf(NotesFolder(tree, "Notes", null, available = true)))
        override suspend fun add(treeUri: Uri) = AddFolderResult.NotAFolder
        override suspend fun remove(treeUri: Uri) = Unit
        override suspend fun findAgain(old: Uri, picked: Uri) = AddFolderResult.NotAFolder
        override suspend fun list(treeUri: Uri) = FolderListing.Listed(
            FolderEntry(
                "root",
                "Notes",
                emptyList(),
                listOf(NoteEntry("plan-id", "plan.md", null, null), NoteEntry("other-id", "other.md", null, null)),
            ),
        )
        override fun changes(treeUri: Uri, folderIds: Set<String>): Flow<Unit> = emptyFlow()
        override fun noteUri(treeUri: Uri, documentId: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        override fun documentIdOf(documentUri: Uri): String? =
            runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
        override suspend fun noteStats(documentUri: Uri): DocumentStats? = null
    }

    private val folderStore = object : NotesFolderStore {
        override val folders = MutableStateFlow(listOf(StoredFolder(tree, "Notes", null)))
        override val selectedFolder = MutableStateFlow<Uri?>(tree)
        override val expanded = MutableStateFlow<Set<String>>(emptySet())
        override suspend fun setFolders(folders: List<StoredFolder>) = Unit
        override suspend fun setSelectedFolder(uri: Uri?) = Unit
        override suspend fun setExpanded(keys: Set<String>) = Unit
    }

    private val marksStore = object : NoteMarksStore {
        override suspend fun get(treeUri: Uri): Map<String, SeenRecord>? = null
        override suspend fun put(treeUri: Uri, records: Map<String, SeenRecord>) = Unit
        override suspend fun drop(treeUri: Uri) = Unit
    }

    private fun viewModel(): MarkdownViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("files_return.preferences_pb")
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

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(message, condition())
    }

    private fun showMainScreen(vm: MarkdownViewModel) {
        compose.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = vm,
                    prompts = remember { prompts() },
                    context = compose.activity,
                    renderer = MarkwonRenderer(compose.activity),
                    mermaidRenderer = NullMermaidRenderer,
                    notesFoldersViewModel = remember { NotesFoldersViewModel(folderRepo, folderStore, marksStore) },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun awaitNoteOpen(vm: MarkdownViewModel, uri: Uri = note) {
        awaitTrue("$uri never finished loading") {
            vm.currentDocument.value?.uri == uri && vm.fileLoadState.value == FileLoadState.Success
        }
        compose.waitForIdle()
    }

    /** Welcome's row to My folders, then the note's row in the list. */
    private fun openFromMyFolders(vm: MarkdownViewModel, name: String, uri: Uri) {
        compose.onNodeWithText("My folders").performClick()
        compose.waitForIdle()
        pickInList(name)
        awaitNoteOpen(vm, uri)
    }

    private fun pickInList(name: String) {
        awaitTrue("$name never appeared in My folders") {
            compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
    }

    /** The reader's own way to My folders, with a note open. */
    private fun openMyFoldersFromReader() {
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("My folders").performClick()
        compose.waitForIdle()
    }

    private fun makeDirty(vm: MarkdownViewModel) {
        vm.updateContent(vm.currentDocument.value!!.id, "$PLAN_TEXT\nunsaved\n")
        awaitTrue("the edit never made the note dirty") { vm.currentDocument.value?.dirty == true }
    }

    /** Typing the edit away again: the baseline is what counts, so the note is clean. */
    private fun makeClean(vm: MarkdownViewModel) {
        vm.updateContent(vm.currentDocument.value!!.id, PLAN_TEXT)
        awaitTrue("the note never became clean again") { vm.currentDocument.value?.dirty == false }
    }

    /** The screen's reaction to a failed open: the toast, which is what clears the pick. */
    private fun awaitLoadFailedToast() {
        awaitTrue("the failed open never showed") {
            compose.onAllNodesWithText("Couldn't open that file").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun awaitPrompt() {
        awaitTrue("the unsaved-changes prompt never appeared") {
            compose.onAllNodesWithText("Unsaved changes").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The prompt's own dismissal (system Back, or a tap outside): the dialog's onDismissRequest. */
    private fun dismissPrompt(vm: MarkdownViewModel) {
        compose.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        awaitTrue("the prompt never went away") { vm.pendingOpenUri.value == null }
        compose.waitForIdle()
    }

    private fun awaitNoteClosed(vm: MarkdownViewModel) {
        awaitTrue("plan.md never closed") { vm.currentDocument.value == null }
        compose.waitForIdle()
    }

    private fun assertMyFoldersShown(shown: Boolean) {
        compose.onAllNodesWithContentDescription("Add folder").assertCountEquals(if (shown) 1 else 0)
    }

    @Test
    fun aNoteOpenedFromMyFoldersClosesBackToMyFolders() {
        val vm = viewModel()
        showMainScreen(vm)
        compose.onNodeWithText("My folders").performClick() // Welcome's row
        compose.waitForIdle()
        awaitTrue("the note never appeared in My folders") {
            compose.onAllNodesWithContentDescription("plan.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("plan.md").performClick()
        awaitNoteOpen(vm)
        assertMyFoldersShown(false)

        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertEquals(null, vm.currentDocument.value)
        assertMyFoldersShown(true)
    }

    @Test
    fun systemBackFromANoteOpenedFromMyFoldersReturnsToMyFolders() {
        val vm = viewModel()
        showMainScreen(vm)
        compose.onNodeWithText("My folders").performClick()
        compose.waitForIdle()
        awaitTrue("the note never appeared in My folders") {
            compose.onAllNodesWithContentDescription("plan.md").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("plan.md").performClick()
        awaitNoteOpen(vm)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        awaitNoteClosed(vm)
        assertMyFoldersShown(true)
    }

    @Test
    fun aNoteOpenedAnyOtherWayClosesToWelcome() {
        val vm = viewModel()
        showMainScreen(vm)
        vm.loadFile(note)
        awaitNoteOpen(vm)

        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(false)
        compose.onNodeWithText("My folders").assertExists() // Welcome's row
    }

    @Test
    fun aDirtyNoteSavedForAPickedNoteReturnsToMyFoldersFromThePickedNote() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)
        makeDirty(vm)

        openMyFoldersFromReader()
        pickInList("other.md")
        awaitPrompt()
        compose.onNodeWithText("Save").performClick()
        awaitNoteOpen(vm, other)

        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(true)
    }

    @Test
    fun aDirtyNoteDiscardedForAPickedNoteReturnsToMyFoldersFromThePickedNote() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)
        makeDirty(vm)

        openMyFoldersFromReader()
        pickInList("other.md")
        awaitPrompt()
        compose.onNodeWithText("Discard").performClick()
        awaitNoteOpen(vm, other)

        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(true)
    }

    @Test
    fun aDismissedPromptLeavesTheOpenNoteReturningToMyFolders() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)
        makeDirty(vm)
        openMyFoldersFromReader()
        pickInList("other.md")
        awaitPrompt()
        dismissPrompt(vm)
        assertEquals(note, vm.currentDocument.value?.uri)

        makeClean(vm)
        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(true)
    }

    @Test
    fun aDismissedPickIsNotRememberedWhenThatNoteOpensAnotherWay() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)
        makeDirty(vm)
        openMyFoldersFromReader()
        pickInList("other.md")
        awaitPrompt()
        dismissPrompt(vm)
        makeClean(vm)

        vm.loadFile(other) // from Recent, say
        awaitNoteOpen(vm, other)
        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(false)
        compose.onNodeWithText("My folders").assertExists() // Welcome's row
    }

    @Test
    fun aPickThatFailsToLoadLeavesTheOpenNoteReturningToMyFolders() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)

        otherUnreadable = true
        openMyFoldersFromReader()
        pickInList("other.md")
        awaitLoadFailedToast()
        assertEquals(note, vm.currentDocument.value?.uri)

        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(true)
    }

    @Test
    fun aPickThatFailedToLoadIsNotRememberedWhenThatNoteOpensAnotherWay() {
        val vm = viewModel()
        showMainScreen(vm)
        openFromMyFolders(vm, "plan.md", note)
        otherUnreadable = true
        openMyFoldersFromReader()
        pickInList("other.md")
        awaitLoadFailedToast()

        otherUnreadable = false
        vm.loadFile(other) // from Recent, say
        awaitNoteOpen(vm, other)
        compose.onNodeWithContentDescription("Close file").performClick()
        awaitNoteClosed(vm)
        assertMyFoldersShown(false)
    }

    private companion object {
        const val PLAN_TEXT = "# Plan\n\nbody\n"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
