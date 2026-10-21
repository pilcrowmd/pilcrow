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
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Edit to Read with the keyboard open: the ViewModel works out which reader block the editor caret
 * sat in and publishes it as a one-shot [CaretReveal] for the reader to bring above the keyboard.
 *
 * Fixture and [awaitValue] barrier mirror [MarkdownViewModelHeadingJumpTest]. The block is found on
 * [kotlinx.coroutines.Dispatchers.Default], so every "published" assertion waits on `caretReveal`
 * turning non-null (it starts null), and every "cleared" one on it turning null from a value the
 * test saw published. The one absence check (plain text) polls for a bounded time, because there is
 * nothing to wait FOR; it is proven by watching it fail with the render-mode guard removed.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelCaretRevealTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: StorageManager
    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("caret_reveal_test.preferences_pb")
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

    private val markdown = "# One\n\nText.\n\n# Two\n\nMore.\n" // blocks: 0 One, 1 Text, 2 Two, 3 More
    private val uriMd = Uri.parse("content://test/a.md")
    private val uriTxt = Uri.parse("content://test/a.txt")
    private val uriOtherMd = Uri.parse("content://test/b.md")

    private fun vm(
        cpu: CoroutineDispatcher = Dispatchers.Default,
        markdownCeilingChars: Int = Int.MAX_VALUE,
    ): MarkdownViewModel {
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        return MarkdownViewModel(
            repository = FakeRepo(mapOf(uriMd to markdown, uriTxt to markdown, uriOtherMd to markdown)),
            storage = storage,
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            cpuDispatcher = cpu,
            prepareReaderModel = PrepareReaderModelUseCase(
                parseHeadings::parseTree,
                parseHeadings,
                markdownCeilingChars = markdownCeilingChars,
            ),
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

    private fun MarkdownViewModel.switchTo(mode: ViewMode) {
        setMode(mode)
        awaitValue(mode, "mode switched") { this.mode.value }
    }

    private fun MarkdownViewModel.awaitReveal(): CaretReveal {
        awaitValue(true, "a caret reveal was published") { caretReveal.value != null }
        return caretReveal.value!!
    }

    /**
     * A CPU dispatcher whose work, while [holding], waits in [held] until the test runs it: the
     * block computation is then genuinely in flight, and the test decides when it finishes.
     */
    private class HoldingDispatcher : CoroutineDispatcher() {
        @Volatile var holding = false
        val held = LinkedBlockingQueue<Runnable>()
        val finished = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held.add(block) else Dispatchers.Default.dispatch(context, block)
        }

        /** Run the oldest held task on a worker thread; [finished] counts it once it has returned. */
        fun releaseNext() {
            val block = held.take()
            Dispatchers.Default.dispatch(EmptyCoroutineContext) {
                block.run()
                finished.incrementAndGet()
            }
        }
    }

    /** [uri] open in the editor with the caret on [marker]. */
    private fun vmEditingAt(
        uri: Uri,
        marker: String,
        cpu: CoroutineDispatcher = Dispatchers.Default,
        markdownCeilingChars: Int = Int.MAX_VALUE,
    ): MarkdownViewModel {
        val vm = vm(cpu, markdownCeilingChars)
        vm.loadAndAwait(uri)
        // The opening model first: until it lands, a switch can queue its preparation on [cpu] next to
        // the caret's block. Once it has, the text is unchanged, so Edit to Read reuses it, and the
        // caret's block is the only CPU work a switch queues (the held-task counts below rely on it).
        awaitValue(true, "reader model prepared") { vm.readerModel.value != null }
        vm.switchTo(ViewMode.EDITOR)
        vm.updateEditorCursor(markdown.indexOf(marker))
        return vm
    }

    @Test
    fun `switching to the reader publishes the block the caret sat in`() {
        val vm = vmEditingAt(uriMd, "More.")
        vm.switchTo(ViewMode.READER)
        assertEquals(3, vm.awaitReveal().blockIndex)
    }

    @Test
    fun `acknowledging clears the reveal and only the matching seq does`() {
        val vm = vmEditingAt(uriMd, "More.")
        vm.switchTo(ViewMode.READER)
        val reveal = vm.awaitReveal()

        vm.onCaretRevealHandled(reveal.seq + 1)
        assertEquals(reveal, vm.caretReveal.value)

        vm.onCaretRevealHandled(reveal.seq)
        assertNull(vm.caretReveal.value)
    }

    @Test
    fun `a second switch publishes a new seq for the new caret`() {
        val vm = vmEditingAt(uriMd, "More.")
        vm.switchTo(ViewMode.READER)
        val first = vm.awaitReveal()
        vm.onCaretRevealHandled(first.seq)

        vm.switchTo(ViewMode.EDITOR)
        vm.updateEditorCursor(markdown.indexOf("Text."))
        vm.switchTo(ViewMode.READER)
        val second = vm.awaitReveal()

        assertEquals(1, second.blockIndex)
        assertNotEquals(first.seq, second.seq)
    }

    @Test
    fun `leaving the reader drops a reveal the reader never acknowledged`() {
        val vm = vmEditingAt(uriMd, "More.")
        vm.switchTo(ViewMode.READER)
        vm.awaitReveal()

        vm.switchTo(ViewMode.EDITOR)

        awaitValue(null, "reveal dropped on leaving the reader") { vm.caretReveal.value }
    }

    @Test
    fun `a document shown as plain text publishes no reveal`() {
        val vm = vmEditingAt(uriTxt, "More.")
        vm.switchTo(ViewMode.READER)

        val deadline = System.currentTimeMillis() + ABSENCE_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("plain text has no blocks to reveal", vm.caretReveal.value)
            Thread.sleep(10L)
        }
    }

    @Test
    fun `a document past the Markdown ceiling publishes no reveal`() {
        // Shown as plain text by length (M-179), so the caret's block must not be looked for: that
        // parse is the one the ceiling exists to avoid on a file too large to parse.
        val vm = vmEditingAt(uriMd, "More.", markdownCeilingChars = markdown.length - 1)
        vm.switchTo(ViewMode.READER)

        val deadline = System.currentTimeMillis() + ABSENCE_WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("a document past the ceiling has no blocks to reveal", vm.caretReveal.value)
            Thread.sleep(10L)
        }
    }

    /** Release the oldest held computation and let its result reach the main thread. */
    private fun HoldingDispatcher.finishNext() {
        val done = finished.get()
        releaseNext()
        awaitValue(done + 1, "the released computation returned") { finished.get() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a result from a switch that a newer switch has replaced is dropped`() {
        val cpu = HoldingDispatcher()
        val vm = vmEditingAt(uriMd, "More.", cpu)
        cpu.holding = true
        // The mode flips only after the landing is worked out, so a held computation leaves it on EDITOR.
        vm.setMode(ViewMode.READER) // computation 1, caret on block 3, held
        awaitValue(1, "computation 1 in flight") { cpu.held.size }
        vm.updateEditorCursor(markdown.indexOf("Text."))
        vm.setMode(ViewMode.READER) // supersedes 1; computation 2, caret on block 1, held
        awaitValue(2, "computation 2 in flight") { cpu.held.size }

        cpu.finishNext() // the OLD one returns first

        assertNull("the stale block 3 must not be published", vm.caretReveal.value)
        cpu.finishNext()
        awaitValue(1, "the newer switch's block is published") { vm.caretReveal.value?.blockIndex }
        awaitValue(ViewMode.READER, "the newer switch flipped the mode") { vm.mode.value }
    }

    @Test
    fun `a result for a document that has since been replaced is dropped`() {
        val cpu = HoldingDispatcher()
        val vm = vmEditingAt(uriMd, "More.", cpu)
        cpu.holding = true
        vm.setMode(ViewMode.READER) // computation for document A, held
        awaitValue(1, "computation in flight") { cpu.held.size }
        cpu.holding = false
        // Document B replaces A while A's block is still being worked out. B is Markdown too: were it
        // plain text, the render mode it brings would already keep A's block from being published.
        vm.loadAndAwait(uriOtherMd)

        cpu.finishNext()

        assertNull("a block of document A must not be published over document B", vm.caretReveal.value)
    }

    private companion object {
        const val ABSENCE_WAIT_MILLIS = 400L
    }
}
