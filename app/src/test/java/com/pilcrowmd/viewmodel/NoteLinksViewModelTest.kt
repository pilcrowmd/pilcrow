// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.repository.LocalNoteLinkRepository
import com.pilcrowmd.repository.NotesTreeFixture
import com.pilcrowmd.repository.NotesTreeFixture.PROJECTS
import com.pilcrowmd.repository.NotesTreeFixture.ROOT
import com.pilcrowmd.repository.NotesTreeFixture.inNotes
import com.pilcrowmd.repository.NotesTreeFixture.notesTree
import com.pilcrowmd.repository.NotesTreeFixture.plain
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.storage.StorageManager
import com.pilcrowmd.ui.screen.BackIntent
import com.pilcrowmd.ui.screen.BackNavState
import com.pilcrowmd.ui.screen.dropFailedLinkOpens
import com.pilcrowmd.ui.screen.followLinkBack
import com.pilcrowmd.ui.screen.forwardBareLinks
import com.pilcrowmd.ui.screen.handleNoteLinkEvent
import com.pilcrowmd.ui.screen.resolveBackIntent
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-160: following links between notes with the real reader ViewModel, through the same calls the
 * screen makes ([handleNoteLinkEvent], [resolveBackIntent], [followLinkBack]).
 *
 * Notes-folder spec section 5, where each case is tested:
 * - 1-14, 18-23, 26: NoteLinkResolverTest (PR2).
 * - 15: NoteListingTest (PR1).
 * - 16, 17: here. 25: here and LocalNoteLinkRepositoryTest.
 * - 24: N/A on Android (no symlinks in a SAF folder).
 * - 27-35: NoteMarksTest (PR3).
 *
 * Every wait is on state the call under test publishes, never on a value already in place:
 * [awaitValue] polls and idles the main looper, as MarkdownViewModelSearchResetTest does.
 */
@RunWith(RobolectricTestRunner::class)
class NoteLinksViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope
    private val collectScope = CoroutineScope(Dispatchers.Main + Job())

    // Index opened from Recents (a plain document URI, case 25); plan as a followed link opens it.
    private val index = plain("$ROOT/index.md")
    private val plan = inNotes("$PROJECTS/plan.md")
    private val two = inNotes("$ROOT/Note 2.md")
    private val contents = mapOf(
        index to "# Index\n\nSee [plan](projects/plan.md).\n",
        plan to "# Plan\n\n## Goals\n",
        two to "# Two\n\nSee [itself](Note 2.md).\n",
    )

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("note_links_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        collectScope.cancel()
        gated.shutdown()
        storageScope.cancel()
    }

    private val unreadable = java.util.concurrent.CopyOnWriteArraySet<Uri>()

    private inner class FakeRepo : FileRepository {
        override suspend fun readFile(uri: Uri) = if (uri in unreadable) {
            Result.failure(java.io.IOException("unreadable"))
        } else {
            Result.success(FileText(contents.getValue(uri), isUtf8 = true))
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

    /** Runs the reader's off-main work on one thread that waits at [gate] while it is closed. */
    private class GatedDispatcher {
        @Volatile var gate: java.util.concurrent.CountDownLatch? = null
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                executor.execute {
                    gate?.await()
                    block.run()
                }
            }
        }

        fun shutdown() = executor.shutdownNow()
    }

    private val gated = GatedDispatcher()

    private fun reader(
        prepare: ((ParseMarkdownHeadingsUseCase) -> PrepareReaderModelUseCase)? = null,
    ): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            cpuDispatcher = gated.dispatcher,
            prepareReaderModel = prepare?.invoke(parseHeadings)
                ?: PrepareReaderModelUseCase(parseHeadings::parseTree, parseHeadings),
        )
    }

    private fun links() = NoteLinksViewModel(
        LocalNoteLinkRepository(NotesTreeFixture.Tree(), NotesTreeFixture.Folders(NotesTreeFixture.folder(notesTree))),
    )

    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    /** What the screen does: report each shown document, carry out each event. */
    private class Screen(
        val reader: MarkdownViewModel,
        val links: NoteLinksViewModel,
        scope: CoroutineScope,
        val context: Context = ApplicationProvider.getApplicationContext(),
    ) {
        val messages = mutableListOf<String>()
        var events = 0

        init {
            scope.launch {
                links.events.collect { event ->
                    events++
                    handleNoteLinkEvent(event, reader, context, { _, _ -> true }) { messages += it }
                }
            }
            scope.launch { reader.currentDocument.collect { links.onDocumentShown(it?.uri, it?.content) } }
            scope.launch { forwardBareLinks(links, reader) }
            scope.launch { dropFailedLinkOpens(links, reader) }
        }
    }

    private fun screenWithIndexOpen(): Screen {
        val screen = Screen(reader(), links(), collectScope)
        screen.reader.loadFile(index)
        awaitValue(index, "index.md shown") { screen.reader.currentDocument.value?.uri }
        awaitValue(true, "index.md is in the added folder") { screen.links.folder.value != null }
        return screen
    }

    @Test
    fun case16_following_a_link_from_an_unsaved_note_asks_first() {
        val screen = screenWithIndexOpen()
        val doc = screen.reader.currentDocument.value!!
        screen.reader.updateContent(doc.id, doc.content + "edit\n")
        awaitValue(true, "index.md has unsaved edits") { screen.reader.currentDocument.value?.dirty }

        screen.links.follow(index, "projects/plan.md")
        awaitValue(plan, "the open waits on the unsaved-changes prompt") { screen.reader.pendingOpenUri.value }
        assertEquals("index.md is still the note shown", index, screen.reader.currentDocument.value?.uri)
        assertTrue("its edits are still there", screen.reader.currentDocument.value!!.dirty)

        screen.reader.discardAndOpenPending()
        awaitValue(plan, "Discard opens plan.md") { screen.reader.currentDocument.value?.uri }
        awaitValue(true, "Back now returns to index.md") { screen.links.canGoBack.value }
        assertEquals(NoteLanding(plan, null), screen.links.landing.value)
    }

    @Test
    fun case16_cancelling_the_prompt_drops_the_follow() {
        val screen = screenWithIndexOpen()
        val doc = screen.reader.currentDocument.value!!
        screen.reader.updateContent(doc.id, doc.content + "edit\n")
        awaitValue(true, "index.md has unsaved edits") { screen.reader.currentDocument.value?.dirty }
        screen.links.follow(index, "projects/plan.md")
        awaitValue(plan, "prompt shown") { screen.reader.pendingOpenUri.value }

        screen.reader.cancelPendingOpen()
        screen.links.cancelPending()
        // plan.md then opened some other way (Files), through the same prompt: not a followed link.
        screen.reader.loadFile(plan)
        awaitValue(plan, "prompt shown again") { screen.reader.pendingOpenUri.value }
        screen.reader.discardAndOpenPending()
        awaitValue(plan, "plan.md opened from Files") { screen.reader.currentDocument.value?.uri }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("no Back to index.md", screen.links.canGoBack.value)
        assertNull("no landing: plan.md opens at its saved place", screen.links.landing.value)
    }

    @Test
    fun case17_back_returns_to_the_block_the_previous_note_showed() {
        val screen = screenWithIndexOpen()
        val anchor = ScrollAnchor(index = 5, offset = 0)
        screen.reader.updatePreviewScroll(anchor)
        awaitValue(anchor, "index.md's place is saved") { runBlocking { storage.getScrollPosition(index) } }

        screen.links.follow(index, "projects/plan.md#goals")
        awaitValue(plan, "plan.md opened") { screen.reader.currentDocument.value?.uri }
        awaitValue(true, "Back is a link Back") { screen.links.canGoBack.value }
        assertEquals(NoteLanding(plan, "goals"), screen.links.landing.value)

        val state = BackNavState(
            showFiles = false,
            searchVisible = false,
            drawerOpen = false,
            showLicenses = false,
            showSettings = false,
            hasDocument = true,
            isSaving = false,
            documentDirty = false,
            exitArmed = false,
            canGoBackLink = screen.links.canGoBack.value,
        )
        assertEquals(BackIntent.LinkBack, resolveBackIntent(state))
        followLinkBack(screen.links, screen.reader, screen.reader.currentDocument.value?.uri)

        awaitValue(index, "Back opens index.md") { screen.reader.currentDocument.value?.uri }
        assertEquals("at the block it was showing", anchor, screen.reader.previewScroll.value)
        awaitValue(false, "the history is used up") { screen.links.canGoBack.value }
        assertNull("Back restores the saved place, it does not land on a heading", screen.links.landing.value)
    }

    @Test
    fun bare_links_are_worked_out_for_the_note_shown_and_tied_to_it() {
        val screen = screenWithIndexOpen()
        awaitValue(NoteBareLinks(index, setOf("projects/plan.md")), "index.md's bare links") {
            screen.links.bareLinks.value
        }
    }

    @Test
    fun a_link_to_the_note_already_shown_lands_on_its_heading_without_reopening() {
        // index.md is shown through a plain URI (Recents); the link resolves to its tree-based URI.
        val screen = screenWithIndexOpen()
        assertTrue(screen.links.follow(index, "index.md#sec"))
        awaitValue(NoteLanding(index, "sec"), "lands on the heading in the note shown") { screen.links.landing.value }
        assertEquals("no reopen", 0, screen.events)
        assertEquals(index, screen.reader.currentDocument.value?.uri)
        assertNull(screen.reader.pendingOpenUri.value)
        assertFalse("no Back to the same note", screen.links.canGoBack.value)
    }

    @Test
    fun web_addresses_and_pathless_links_are_not_taken() {
        val screen = screenWithIndexOpen()
        assertFalse(screen.links.follow(index, "www.example.com"))
        assertFalse(screen.links.follow(index, "?q=1"))
        assertTrue("control: a path is taken", screen.links.follow(index, "projects/plan.md"))
    }

    @Test
    fun a_web_address_written_without_a_scheme_is_left_to_the_browser_and_note_names_still_follow() {
        val screen = screenWithIndexOpen()
        assertFalse("example.com/page opens in the browser as before", screen.links.follow(index, "example.com/page"))
        assertTrue("a note with spaces", screen.links.follow(index, "Note 2.md"))
        assertTrue("a note in a folder", screen.links.follow(index, "projects/plan.md"))
        screen.links.follow(index, "No such note.md")
        awaitValue(listOf("Note not found: No such note.md"), "a missing note is still a K7 message") {
            screen.messages.toList()
        }
    }

    @Test
    fun k7_and_k8_reach_the_screen_as_messages() {
        val screen = screenWithIndexOpen()
        screen.links.follow(index, "No%20such.md")
        awaitValue(listOf("Note not found: No such.md"), "K7 message") { screen.messages.toList() }
        screen.links.follow(index, "projects/img.png")
        awaitValue(2, "K8 handed to the system, no message") { screen.events }
        assertEquals(1, screen.messages.size)
    }

    /** The destinations of the links in the reader's current model, or null while there is no model. */
    private fun linksIn(model: ReaderModel?): List<String>? = model?.let {
        val found = mutableListOf<String>()
        it.blocks.forEach { block ->
            block.accept(object : org.commonmark.node.AbstractVisitor() {
                override fun visit(link: org.commonmark.node.Link) {
                    found += link.destination
                }
            })
        }
        found
    }

    @Test
    fun bare_links_arriving_give_a_new_model_of_the_same_text_and_the_same_place() {
        val reader = reader()
        reader.loadFile(two)
        awaitValue(two, "Note 2.md shown") { reader.currentDocument.value?.uri }
        awaitValue(emptyList<String>(), "its model, built without bare links") { linksIn(reader.readerModel.value) }
        val first = reader.readerModel.value!!
        val place = ScrollAnchor(index = 3, offset = 12)
        reader.updatePreviewScroll(place)
        gated.gate = java.util.concurrent.CountDownLatch(1) // holds the rebuild below

        reader.setBareLinks(NoteBareLinks(two, setOf("Note 2.md")))
        awaitValue(true, "the old model stays on screen while the new one is built") {
            reader.readerModel.value === first
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("still the old model, never blank", reader.readerModel.value === first)
        gated.gate!!.countDown()

        awaitValue(listOf("Note 2.md"), "the model built with the bare link") { linksIn(reader.readerModel.value) }
        assertTrue("a new model", reader.readerModel.value !== first)
        assertTrue("of the same text", reader.readerModel.value!!.content === first.content)
        assertEquals("the saved place is untouched", place, reader.previewScroll.value)
    }

    @Test
    fun another_notes_bare_links_are_not_applied() {
        val reader = reader()
        reader.setBareLinks(NoteBareLinks(index, setOf("Note 2.md")))
        reader.loadFile(two)
        awaitValue(two, "Note 2.md shown") { reader.currentDocument.value?.uri }
        awaitValue(true, "its model is ready") {
            reader.readerModel.value?.content === reader.currentDocument.value?.content
        }
        assertEquals(emptyList<String>(), linksIn(reader.readerModel.value))
    }

    @Test
    fun the_screen_hands_the_worked_out_bare_links_to_the_reader_so_the_note_shows_them_as_links() {
        val screen = Screen(reader(), links(), collectScope)
        screen.reader.loadFile(two)
        awaitValue(listOf("Note 2.md"), "Note 2.md's model, built with its bare link") {
            linksIn(screen.reader.readerModel.value)
        }
    }

    @Test
    fun the_bare_links_of_a_note_stay_while_the_editor_has_no_text_to_work_from() {
        val links = links()
        links.onDocumentShown(two, contents.getValue(two))
        awaitValue(NoteBareLinks(two, setOf("Note 2.md")), "Note 2.md's bare links") { links.bareLinks.value }

        links.onDocumentShown(two, null) // the editor is open
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(NoteBareLinks(two, setOf("Note 2.md")), links.bareLinks.value)
    }

    @Test
    fun a_note_opened_by_a_link_comes_back_at_the_next_start_through_the_restore_guard() {
        val screen = screenWithIndexOpen()
        screen.links.follow(index, "projects/plan.md")
        awaitValue(plan, "plan.md opened by the link") { screen.reader.currentDocument.value?.uri }
        awaitValue(plan, "it is the file the next start reopens") { runBlocking { storage.lastFileUri.first() } }

        // The process dies here; the next start restores the note the way it restores any other.
        val cold = reader()
        awaitValue(plan, "the cold start reopens plan.md") { cold.currentDocument.value?.uri }
        assertTrue(
            "the restore flag stays on until the note has been shown",
            runBlocking { storage.isRestoreInProgress() },
        )
        cold.onRestoredDocumentShown()
        awaitValue(false, "showing the note clears the flag") { runBlocking { storage.isRestoreInProgress() } }
    }

    @Test
    fun opening_a_note_parses_it_once_although_the_empty_bare_link_set_arrives_during_the_parse() {
        val parses = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val screen = Screen(
            reader { headings ->
                PrepareReaderModelUseCase(
                    parse = { text ->
                        // The first parse is held, running, while the bare-link set for the note arrives.
                        if (parses.incrementAndGet() == 1) release.await()
                        headings.parseTree(text)
                    },
                    headings = headings,
                )
            },
            links(),
            collectScope,
        )
        screen.reader.loadFile(plan)
        awaitValue(plan, "plan.md shown") { screen.reader.currentDocument.value?.uri }
        awaitValue(NoteBareLinks(plan, emptySet()), "the screen handed the reader plan.md's (empty) set") {
            screen.links.bareLinks.value
        }
        awaitValue(1, "the held parse has started") { parses.get() }
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }

        release.countDown()
        awaitValue(true, "plan.md's model is published") {
            screen.reader.readerModel.value?.content === screen.reader.currentDocument.value?.content
        }
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertEquals("one parse for one version of the text", 1, parses.get())
    }

    @Test
    fun a_link_that_failed_to_open_leaves_no_trace_when_the_note_is_opened_another_way() {
        val screen = screenWithIndexOpen()
        unreadable += plan
        screen.links.follow(index, "projects/plan.md")
        awaitValue(true, "the link's open failed") { screen.reader.fileLoadState.value is FileLoadState.Error }
        assertEquals("index.md is still the note shown", index, screen.reader.currentDocument.value?.uri)

        unreadable -= plan
        screen.reader.loadFile(plan) // from Recents, not from the link
        awaitValue(plan, "plan.md opened from Recents") { screen.reader.currentDocument.value?.uri }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("no Back to index.md: this open was not a link", screen.links.canGoBack.value)
        assertNull("no heading landing: plan.md opens at its saved place", screen.links.landing.value)
    }

    private companion object {
        const val SETTLE_ROUNDS = 30
    }
}
