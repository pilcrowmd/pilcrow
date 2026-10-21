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
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
import com.pilcrowmd.domain.usecase.ReadEditLanding
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Issue #15 (M-388, NEW-105): a Read to Edit or Edit to Read switch lands on the same text. Read to
 * Edit puts the caret on the line in the middle of the Read screen when the reader moved (or the
 * document is new to the session); Edit to Read puts the caret's block a third of the way down the
 * screen when the caret moved; otherwise each mode keeps its own place. A file opened again in the
 * same session gets its caret back.
 *
 * Setup and the [awaitValue] barrier mirror [MarkdownViewModelCaretRevealTest]: the landing is worked
 * out off Main before the mode flips, so every assertion waits on the state the call publishes (the
 * new mode, the new scroll anchor, the opened document), never on a value that already holds.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelReadEditLandingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("read_edit_landing_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private class FakeRepo(private val contents: Map<Uri, String>) : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(contents.getValue(uri), isUtf8 = true))
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

    /** 60 blocks, each a paragraph of two lines, so block n is "Paragraph n first line" and one more. */
    private fun doc(label: String) = (0 until 60).joinToString("\n\n", postfix = "\n") { n ->
        "Paragraph $n first line of $label\nParagraph $n second line"
    }

    /** [doc] with an extra blank line before every third block, so a guess from the blocks' sizes is off. */
    private val jagged = (0 until 60).joinToString("", postfix = "\n") { n ->
        val separator = when {
            n == 0 -> ""
            n % 3 == 0 -> "\n\n\n"
            else -> "\n\n"
        }
        separator + "Paragraph $n first line of j\nParagraph $n second line"
    }

    private fun startOfJagged(n: Int) = jagged.indexOf("Paragraph $n first line")

    private val markdown = doc("a")
    private val uriA = Uri.parse("content://test/a.md")
    private val uriB = Uri.parse("content://test/b.md")
    private val uriC = Uri.parse("content://test/c.md")
    private val uriJagged = Uri.parse("content://test/j.md")
    private val uriCode = Uri.parse("content://test/code.md")

    /** Ten one-line paragraphs set apart by two blank lines (a guess from sizes is off), then a code block. */
    private val codeNote = (0 until 10).joinToString("\n\n\n", postfix = "\n\n\n") { "Paragraph $it." } +
        "```text\n" + (1..30).joinToString("") { "code $it\n" } + "```\n\nAfter the code.\n"

    private fun startOfBlock(n: Int) = markdown.indexOf("Paragraph $n first line")

    /** Holds the work handed to it while [holding], as `MarkdownViewModelCaretRevealTest` does. */
    private class HoldingDispatcher : CoroutineDispatcher() {
        @Volatile var holding = false
        val held = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held.add(block) else Dispatchers.Default.dispatch(context, block)
        }

        /**
         * Lets every held task run. The landing's 200 ms wait is on Robolectric's paused clock, which
         * does not pass while a test polls, so a held search ends only when it is released.
         */
        fun releaseAll() {
            while (true) {
                val block = held.poll() ?: return
                Dispatchers.Default.dispatch(EmptyCoroutineContext, block)
            }
        }
    }

    private fun vm(
        cpu: CoroutineDispatcher = Dispatchers.Default,
        parseHeadings: ParseMarkdownHeadingsUseCase = ParseMarkdownHeadingsUseCase(),
    ): MarkdownViewModel {
        return MarkdownViewModel(
            repository = FakeRepo(
                mapOf(uriA to markdown, uriB to doc("b"), uriC to doc("c"), uriJagged to jagged, uriCode to codeNote),
            ),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            cpuDispatcher = cpu,
            prepareReaderModel = PrepareReaderModelUseCase(parseHeadings::parseTree, parseHeadings),
        )
    }

    /** See [MarkdownViewModelRenderModeTest.awaitValue]: same barrier, same caveats. */
    private fun <T> awaitValue(expected: T, message: String, actual: () -> T) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (actual() == expected) return
            Thread.sleep(10L)
        }
        assertEquals(message, expected, actual())
    }

    private fun MarkdownViewModel.loadAndAwait(uri: Uri) {
        loadFile(uri)
        awaitValue(uri, "document loaded") { currentDocument.value?.uri }
    }

    /** [uri] open in the reader with its model prepared (a switch lands on the model's blocks). */
    private fun MarkdownViewModel.openInReader(uri: Uri) {
        loadAndAwait(uri)
        // The model of THIS text: a reopened document first shows the previous document's model.
        awaitValue(true, "reader model prepared") {
            readerModel.value?.content === currentDocument.value?.content
        }
        awaitValue(ViewMode.READER, "opens in the reader") { mode.value }
    }

    private fun MarkdownViewModel.switchTo(mode: ViewMode) {
        setMode(mode)
        awaitValue(mode, "mode switched") { this.mode.value }
    }

    @Test
    fun `after the reader moved, Edit opens on the first line of the block in the middle of the screen`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.onReaderMoved()

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfBlock(20), vm.editorCursor.value)
    }

    @Test
    fun `the first Edit after opening a document lands on the middle of the screen with no move reported`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfBlock(20), vm.editorCursor.value)
    }

    @Test
    fun `when the reader did not move since the last switch, Edit keeps the caret`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.onReaderMoved()
        vm.switchTo(ViewMode.EDITOR)
        assertEquals(startOfBlock(20), vm.editorCursor.value)
        vm.switchTo(ViewMode.READER)

        vm.updateReaderMiddle(40, 0f, 2000) // a layout report, not a move by the user
        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfBlock(20), vm.editorCursor.value)
    }

    @Test
    fun `when the caret moved, Read puts its block a third of the way down the screen`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.switchTo(ViewMode.EDITOR)
        vm.updateEditorCursor(markdown.indexOf("Paragraph 30 second line") + 3)

        vm.setMode(ViewMode.READER)

        awaitValue(ScrollAnchor(30, 2000 / 3), "the caret's block is put a third down") { vm.previewScroll.value }
        awaitValue(ViewMode.READER, "mode switched") { vm.mode.value }
    }

    @Test
    fun `when the caret did not move, Read keeps its scroll`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.updatePreviewScroll(ScrollAnchor(7, 33))
        awaitValue(ScrollAnchor(7, 33), "scroll set") { vm.previewScroll.value }
        vm.switchTo(ViewMode.EDITOR)

        vm.switchTo(ViewMode.READER)

        assertEquals(ScrollAnchor(7, 33), vm.previewScroll.value)
    }

    @Test
    fun `a file opened again in the same session gets its caret back, a new one starts at 0`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.switchTo(ViewMode.EDITOR)
        vm.updateEditorCursor(123)
        vm.loadAndAwait(uriB)
        assertEquals("another file starts at 0", 0, vm.editorCursor.value)

        vm.loadAndAwait(uriA)

        assertEquals(123, vm.editorCursor.value)
        vm.loadAndAwait(uriC)
        assertEquals("a file never opened starts at 0", 0, vm.editorCursor.value)
    }

    @Test
    fun `a file opened again keeps its caret on the first Edit even if the reader reports a middle elsewhere`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.switchTo(ViewMode.EDITOR)
        vm.updateEditorCursor(123)
        vm.switchTo(ViewMode.READER)
        vm.onReaderMoved() // the flag is up when the file is replaced, so only the reopening can lower it
        vm.loadAndAwait(uriB)
        vm.openInReader(uriA)
        assertEquals("precondition: the kept caret is back", 123, vm.editorCursor.value)
        vm.updateReaderMiddle(40, 0f, 2000) // a layout report, not a move by the user

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(123, vm.editorCursor.value)
    }

    /** Polls for [millis], failing the moment [ok] is false: there is nothing to wait FOR. */
    private fun assertHolds(millis: Long, message: String, ok: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(message, ok())
            Thread.sleep(10L)
        }
    }

    @Test
    fun `a switch to Edit held on its landing is dropped when the document is replaced meanwhile`() {
        val cpu = HoldingDispatcher()
        val vm = vm(cpu)
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.onReaderMoved()
        cpu.holding = true
        vm.setMode(ViewMode.EDITOR) // the search for the middle line is held
        awaitValue(1, "the landing search is in flight") { cpu.held.size }
        cpu.holding = false
        vm.openInReader(uriB) // replaces A while the switch still waits for A's line
        vm.updateReaderMiddle(20, 0f, 2000)

        // The search for A's line now ends, and the switch finds another document.
        assertHolds(SETTLE_MILLIS, "the dropped switch must not change the mode") {
            cpu.releaseAll()
            vm.mode.value == ViewMode.READER
        }
        // The new document's flags were not touched by it: its first Edit still lands in the middle.
        vm.switchTo(ViewMode.EDITOR)
        assertEquals(doc("b").indexOf("Paragraph 20 first line"), vm.editorCursor.value)
    }

    @Test
    fun `a second tap on a switch still working reverses it`() {
        val cpu = HoldingDispatcher()
        val vm = vm(cpu)
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.onReaderMoved()
        cpu.holding = true
        vm.setMode(ViewMode.EDITOR) // heading for Edit, its search held
        awaitValue(1, "the landing search is in flight") { cpu.held.size }

        vm.toggleMode() // from where the first is heading: back to Read

        // Everything held ends: the first switch's search (cancelled), and any the second started.
        assertHolds(SETTLE_MILLIS, "the reversed switch ends in the reader") {
            cpu.releaseAll()
            vm.mode.value == ViewMode.READER
        }
    }

    @Test
    fun `a landing search that runs out of time still moves the caret to the nearest line it found`() {
        val vm = vm()
        vm.readEditBudgetNs = 1L // out of time before the first probe
        vm.openInReader(uriA)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()

        vm.switchTo(ViewMode.EDITOR)

        val caret = vm.editorCursor.value
        assertTrue("the caret left the top: $caret", caret > 0)
        assertEquals("a line start", '\n', markdown[caret - 1])
        val block = ParseMarkdownHeadingsUseCase().readerBlockForOffset(markdown, caret)!!
        assertTrue("block $block is near the middle block 40", block in 38..42)
    }

    /** What a switch settles for with no search at all, for the jagged note's block [block]. */
    private fun MarkdownViewModel.guessFor(block: Int): Int {
        val model = readerModel.value as com.pilcrowmd.domain.markdown.ReaderModel.Markdown
        val landing = ReadEditLanding(ParseMarkdownHeadingsUseCase())
        return landing.estimatedOffsetForBlock(jagged, false, ReadEditLanding.ReaderPoint(block, 0f), model.blocks)!!
    }

    @Test
    fun `a landing worked out when the reader stopped is used at the tap even with no time left`() {
        val vm = vm()
        vm.readEditBudgetNs = 1L // the search at the tap would have no time at all
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()
        assertTrue(
            "precondition: the guess from the blocks' sizes is not the line",
            vm.guessFor(40) != startOfJagged(40),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))
        awaitValue(true, "the landing is worked out") { vm.landingReady }

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfJagged(40), vm.editorCursor.value)
    }

    @Test
    fun `a wait that runs out still moves the caret to the guess, not leaves it where it was`() {
        val cpu = HoldingDispatcher()
        val vm = vm(cpu)
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()
        cpu.holding = true
        vm.setMode(ViewMode.EDITOR) // its search is held, so the wait runs out
        awaitValue(1, "the landing search is in flight") { cpu.held.size }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MILLIS))

        awaitValue(ViewMode.EDITOR, "the switch went ahead") { vm.mode.value }
        assertEquals(vm.guessFor(40), vm.editorCursor.value)
    }

    @Test
    fun `the caret is landed before the editor mode is published`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.updateReaderMiddle(20, 0f, 2000)
        vm.onReaderMoved()
        var caretWhenEditorShown = -1
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.mode.collect { if (it == ViewMode.EDITOR) caretWhenEditorShown = vm.editorCursor.value }
        }

        vm.switchTo(ViewMode.EDITOR)
        watcher.cancel()

        assertEquals("the editor gets the landed caret", startOfBlock(20), caretWhenEditorShown)
    }

    @Test
    fun `a file never edited, opened again, lands its first Edit on the middle of the reader`() {
        val vm = vm()
        vm.openInReader(uriA)
        vm.loadAndAwait(uriB) // A was never opened in Edit: leaving it keeps no caret
        vm.openInReader(uriA)
        vm.updateReaderMiddle(40, 0f, 2000)

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfBlock(40), vm.editorCursor.value)
    }

    @Test
    fun `a search ahead that fails changes nothing, and the tap still lands`() {
        val spy = io.mockk.spyk(ParseMarkdownHeadingsUseCase())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        io.mockk.every { spy.offsetOfReaderBlock(any(), any(), any(), any()) } answers {
            if (calls.getAndIncrement() == 0) error("boom") else callOriginal()
        }
        // A coroutine that throws and is not caught reaches the thread's handler, which would crash the app.
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread.currentThread()
        val previous = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught.set(e) }
        val vm = vm(parseHeadings = spy)
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))
        awaitValue(true, "the search ahead ran and failed") { calls.get() >= 1 }
        assertHolds(SETTLE_MILLIS, "a failed search ahead leaves no landing and the reader in place") {
            !vm.landingReady && vm.mode.value == ViewMode.READER
        }
        thread.uncaughtExceptionHandler = previous
        assertNull("the failure must not escape", uncaught.get())

        vm.switchTo(ViewMode.EDITOR)

        assertEquals("the search at the tap found the line", startOfJagged(40), vm.editorCursor.value)
    }

    @Test
    fun `a search ahead that finishes after the mode flipped caches nothing`() {
        val cpu = HoldingDispatcher()
        val vm = vm(cpu)
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()
        cpu.holding = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))
        awaitValue(1, "the search ahead is in flight") { cpu.held.size }
        vm.setMode(ViewMode.EDITOR)
        awaitValue(2, "the tap's own search is in flight") { cpu.held.size }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MILLIS)) // the tap gives up waiting
        awaitValue(ViewMode.EDITOR, "the switch went ahead") { vm.mode.value }

        assertHolds(SETTLE_MILLIS, "the late search ahead must not be cached") {
            cpu.releaseAll()
            !vm.landingReady
        }
    }

    @Test
    fun `a document too long to be worth it is not searched ahead`() {
        val vm = vm()
        vm.precomputeMaxChars = 100
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))

        assertHolds(SETTLE_MILLIS, "nothing is searched ahead") { !vm.landingReady }
    }

    @Test
    fun `a landing worked out ahead is used for a code block when the middle moved a little`() {
        val vm = vm()
        vm.readEditBudgetNs = 1L
        vm.openInReader(uriCode)
        vm.updateReaderMiddle(10, 0.5f, 2000)
        vm.onReaderMoved()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))
        awaitValue(true, "the landing is worked out") { vm.landingReady }
        vm.updateReaderMiddle(10, 0.51f, 2000) // a layout report, one pixel on

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(codeNote.indexOf("code 16\n"), vm.editorCursor.value)
    }

    @Test
    fun `a landing worked out ahead is used for a prose block whatever the fraction`() {
        val vm = vm()
        vm.readEditBudgetNs = 1L
        vm.openInReader(uriJagged)
        vm.updateReaderMiddle(40, 0f, 2000)
        vm.onReaderMoved()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(READER_SETTLE_MILLIS + 50))
        awaitValue(true, "the landing is worked out") { vm.landingReady }
        vm.updateReaderMiddle(40, 0.4f, 2000)

        vm.switchTo(ViewMode.EDITOR)

        assertEquals(startOfJagged(40), vm.editorCursor.value)
    }

    private companion object {
        const val SETTLE_MILLIS = 500L
    }
}
