// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.storage.StorageManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **M-127 / M-128: a load prepares every value first and publishes them in one step.** A throw or
 * a cancellation before the publish must leave the open document exactly as it was, and nothing
 * after the publish may be able to fail.
 *
 * **The fixture is built so that every published value differs between the two documents**, so
 * no cheaper coincidence can answer before the property under test. A is a CRLF, read-only `.txt`:
 * line ending CRLF, transient, PLAIN, the plain toggle on, no headings, a saved scroll anchor. B is
 * an LF, writable `.md` with a heading, so each of those values flips if any part of B leaks onto A.
 * A writable A would carry `transient = false`, which is also what a leaked B writes, and the
 * assertion could not tell them apart (the #141 lesson recorded on M-109).
 *
 * **Each test was seen failing against `main`** — the previous `loadDocumentOrThrow`, which wrote
 * B's line ending and baseline before the publish and B's trailing state after it.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLoadPublishOnceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var realStorage: LocalStorageManager
    private lateinit var storageScope: CoroutineScope

    private val aUri = Uri.parse("content://t/a.txt")
    private val bUri = Uri.parse("content://t/b.md")
    private val cUri = Uri.parse("content://t/c.md")
    private val aDisk = "alpha\r\nbeta\r\n"
    private val aInEditor = "alpha\nbeta\n"
    private val bDisk = "# Bee\n\ntext\n"
    private val aScroll = ScrollAnchor(index = 3, offset = 7)

    /** Where B's load can be made to fail. Each is a call the old code made AFTER reading B. */
    private enum class FailAt { DISPLAY_NAME, PERMISSION, RENDER_OVERRIDE, SCROLL }

    private inner class Repo(
        private val failAt: FailAt? = null,
        /** B's `displayName` parks here, so a cancellation can land inside the load. */
        private val displayNameGate: CompletableDeferred<Unit>? = null,
        private val contents: Map<Uri, String> = mapOf(aUri to aDisk, bUri to bDisk),
    ) : FileRepository {
        override suspend fun readFile(uri: Uri): Result<FileText> =
            contents[uri]?.let { Result.success(FileText(it, isUtf8 = true)) }
                ?: Result.failure(IOException("unreadable: $uri"))
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri): String {
            if (uri == bUri) {
                displayNameGate?.await()
                if (failAt == FailAt.DISPLAY_NAME) throw IOException("display name blew up")
            }
            return uri.lastPathSegment!!
        }
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri): Boolean {
            if (uri == bUri && failAt == FailAt.PERMISSION) error("permission lookup blew up")
            return uri != aUri // A is read-only, so it is transient; B is writable
        }
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    /** The real DataStore-backed storage, with B's two per-file reads made to throw on request. */
    private fun storageFailingAt(failAt: FailAt?): StorageManager = object : StorageManager by realStorage {
        override suspend fun getRenderModeOverride(uri: Uri): RenderMode? {
            if (uri == bUri && failAt == FailAt.RENDER_OVERRIDE) throw IOException("DataStore blew up")
            return realStorage.getRenderModeOverride(uri)
        }
        override suspend fun getScrollPosition(uri: Uri): ScrollAnchor {
            if (uri == bUri && failAt == FailAt.SCROLL) throw IOException("DataStore blew up")
            return realStorage.getScrollPosition(uri)
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val ds = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("publish_once_test.preferences_pb")
        }
        realStorage = LocalStorageManager(context, ds)
        runBlocking { realStorage.saveScrollPosition(aUri, aScroll) }
    }

    @After
    fun tearDown() = storageScope.cancel()

    private fun vm(
        repo: FileRepository,
        storage: StorageManager = realStorage,
        parse: ParseMarkdownHeadingsUseCase = ParseMarkdownHeadingsUseCase(),
    ) = MarkdownViewModel(
        repository = repo,
        storage = storage,
        parseHeadingsUseCase = parse,
        searchUseCase = SearchMarkdownUseCase(parse),
        pdfExporter = mockk(relaxed = true),
        appInfo = object : AppInfo {
            override val versionName = "test"
        },
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

    /** Open A and wait for its whole load, not just its document: `Success` comes after the publish. */
    private fun MarkdownViewModel.openA() {
        loadFile(aUri)
        awaitTrue("A never finished loading") {
            currentDocument.value?.uri == aUri && fileLoadState.value == FileLoadState.Success
        }
    }

    /** Everything A published is still A's. The baseline is private, so it is read through dirty. */
    private fun MarkdownViewModel.assertStillA(why: String) {
        assertEquals("$why: the open document changed", aUri, currentDocument.value?.uri)
        assertEquals("$why: A's content changed", aInEditor, currentDocument.value?.content)
        assertEquals("$why: A's line ending was overwritten (M-128, Safeguard 2)", "CRLF", lineEnding.value)
        assertTrue("$why: A's transient flag was overwritten (M-127)", transient.value)
        assertEquals("$why: A's render mode was overwritten (M-127)", RenderMode.PLAIN, renderMode.value)
        assertTrue("$why: A's plain toggle was overwritten (M-127)", plainToggleAvailable.value)
        assertTrue("$why: B's headings leaked onto A (M-127)", headings.value.isEmpty())
        assertEquals("$why: A's scroll was overwritten (M-127)", aScroll, previewScroll.value)
        // Type something, then type A's own text back: dirty clears only if the baseline is still A's.
        updateContent(currentDocument.value!!.id, "x")
        awaitTrue("$why: the probe edit never registered") { currentDocument.value?.dirty == true }
        updateContent(currentDocument.value!!.id, aInEditor)
        awaitTrue("$why: A's dirty baseline was overwritten with B's content (M-128)") {
            currentDocument.value?.dirty == false
        }
    }

    private fun aFailedLoadOfBLeavesAIntact(failAt: FailAt) {
        val vm = vm(Repo(failAt), storageFailingAt(failAt))
        vm.openA()

        vm.loadFile(bUri)
        // Success is A's state, so waiting for Error cannot return before B's load has run.
        awaitTrue("B's load never reported its failure ($failAt)") { vm.fileLoadState.value is FileLoadState.Error }

        vm.assertStillA("a throw at $failAt")
    }

    @Test
    fun `a throw from displayName leaves the open document intact`() = aFailedLoadOfBLeavesAIntact(FailAt.DISPLAY_NAME)

    @Test
    fun `a throw from the permission check leaves the open document intact`() =
        aFailedLoadOfBLeavesAIntact(FailAt.PERMISSION)

    @Test
    fun `a throw from the render-mode override read leaves the open document intact`() =
        aFailedLoadOfBLeavesAIntact(FailAt.RENDER_OVERRIDE)

    @Test
    fun `a throw from the scroll read leaves the open document intact`() = aFailedLoadOfBLeavesAIntact(FailAt.SCROLL)

    @Test
    fun `a startup restore cancelled mid-load leaves the new document's line ending and baseline alone`() {
        // M-128's reachable path, as it exists on `main` since M-145: the restore is the load, an
        // explicit open cancels it while it is inside `displayName`, and the document it would have
        // damaged is the blank one Create MD File put on screen meanwhile (the M-109 window). B is
        // made CRLF so its line ending differs from the new document's LF. C's read fails, so its
        // own load cannot overwrite the damage and hide it.
        val gate = CompletableDeferred<Unit>()
        val crlfB = "one\r\ntwo\r\n"
        runBlocking { realStorage.saveLastFileUri(bUri) }
        val vm = vm(Repo(displayNameGate = gate, contents = mapOf(bUri to crlfB)))
        awaitTrue("the restore never reached Loading") { vm.fileLoadState.value == FileLoadState.Loading }

        vm.newDocument()
        // Wait for newDocument's OWN terminal Success, not just its document: it publishes first and
        // emits Success last, after a heading pass on another thread. Waiting only for the document
        // let that late Success land on top of C's Error below, and the test failed intermittently.
        // Not vacuous: the state before it is the restore's Loading. (A late write-back from a
        // finished operation is the class PR 2 of the file-safety design closes; it is not this
        // test's subject.)
        awaitTrue("the new document never finished opening") {
            vm.currentDocument.value?.isUnsaved == true && vm.fileLoadState.value == FileLoadState.Success
        }
        vm.loadFile(cUri) // cancels and joins the restore parked in displayName (M-145)
        awaitTrue("C's load never reported its failure") { vm.fileLoadState.value is FileLoadState.Error }
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the new document was replaced", vm.currentDocument.value?.isUnsaved == true)
        assertEquals("the restore's line ending landed on the new document (M-128)", "LF", vm.lineEnding.value)
        vm.updateContent(vm.currentDocument.value!!.id, "typed")
        awaitTrue("the probe edit never registered") { vm.currentDocument.value?.dirty == true }
        vm.updateContent(vm.currentDocument.value!!.id, "")
        awaitTrue("the restore's content became the new document's dirty baseline (M-128)") {
            vm.currentDocument.value?.dirty == false
        }
    }

    @Test
    fun `a heading parser that throws still opens the document, without a TOC`() {
        // Safeguard 3. The TOC pass runs in its own coroutine after the document is shown, so a
        // parser throw must degrade to no TOC rather than escape and crash the app.
        val parse = mockk<ParseMarkdownHeadingsUseCase>()
        every { parse.extractHeadings(any()) } throws IllegalStateException("parser blew up")
        val vm = vm(Repo(), parse = parse)

        vm.loadFile(bUri)
        awaitTrue("B never opened") {
            vm.currentDocument.value?.uri == bUri && vm.fileLoadState.value == FileLoadState.Success
        }
        // The TOC starts empty on a publish, so the assertion below means nothing until the TOC pass
        // has actually run into the throw. Its fallback logs; wait for that line.
        awaitTrue("the TOC pass never reached the parser's throw") {
            ShadowLog.getLogsForTag("MarkdownViewModel").any { it.msg.startsWith("heading extraction failed") }
        }
        settleMain()

        assertTrue("a failed heading parse should leave the TOC empty", vm.headings.value.isEmpty())
        assertFalse("B opened as transient although it is writable", vm.transient.value)
    }

    /**
     * The TOC is the one value that follows the document: a full parse, about two seconds on a 25 MB
     * file, that the reader must not wait for. S (`slow.md`) opens with its TOC pass held inside the
     * parser; each test then replaces what S's pass was computed for, releases it, and checks that
     * its headings were dropped.
     */
    private inner class SlowToc {
        val sUri: Uri = Uri.parse("content://t/slow.md")
        private val sDisk = "# Slow\n\ntext\n"
        private val real = ParseMarkdownHeadingsUseCase()
        private val sEntered = CountDownLatch(1)
        private val sRelease = CountDownLatch(1)
        private val sReturned = AtomicBoolean(false)
        private val parse = mockk<ParseMarkdownHeadingsUseCase>().also { parse ->
            every { parse.extractHeadings(any()) } answers {
                val content = firstArg<String>()
                if (content == sDisk) {
                    sEntered.countDown()
                    sRelease.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
                real.extractHeadings(content).also { if (content == sDisk) sReturned.set(true) }
            }
        }
        val vm = vm(Repo(contents = mapOf(sUri to sDisk, bUri to bDisk)), parse = parse)

        fun openS() {
            vm.loadFile(sUri)
            awaitTrue("S never opened with its TOC pass in the parser") {
                vm.currentDocument.value?.uri == sUri &&
                    vm.fileLoadState.value == FileLoadState.Success &&
                    sEntered.count == 0L
            }
        }

        /** Let S's pass finish. Nothing observable changes when a guard works, so then settle. */
        fun releaseS() {
            sRelease.countDown()
            awaitTrue("S's TOC pass never finished") { sReturned.get() }
            settleMain()
        }
    }

    @Test
    fun `a slow TOC pass for an earlier document never lands on the next one`() {
        val f = SlowToc()
        f.openS()
        f.vm.loadFile(bUri)
        awaitTrue("B never opened with its own TOC") {
            f.vm.currentDocument.value?.uri == bUri && f.vm.headings.value.map { it.text } == listOf("Bee")
        }

        f.releaseS()

        assertEquals("S's TOC landed on B", listOf("Bee"), f.vm.headings.value.map { it.text })
    }

    @Test
    fun `a slow TOC pass never lands on a new document that started no pass of its own`() {
        // Create MD File publishes an empty TOC and starts no heading pass, so only the document
        // identity can tell S's late result that it is for a document no longer on screen.
        val f = SlowToc()
        f.openS()
        f.vm.newDocument()
        awaitTrue("the new document never opened") {
            f.vm.currentDocument.value?.isUnsaved == true && f.vm.fileLoadState.value == FileLoadState.Success
        }

        f.releaseS()

        assertTrue("S's TOC landed on the new document", f.vm.headings.value.isEmpty())
    }

    @Test
    fun `a slow TOC pass never lands after the same document is switched to plain`() {
        // Same document, so its identity cannot decide: only the newer pass started by the toggle
        // (plain mode, so an empty TOC at once) can tell S's late result that it is out of date.
        val f = SlowToc()
        f.openS()
        f.vm.toggleRenderMode()
        // The toggle's own pass runs right after it stores the override, with nothing to wait for.
        awaitTrue("the toggle never stored PLAIN for S") {
            runBlocking { realStorage.getRenderModeOverride(f.sUri) } == RenderMode.PLAIN
        }
        settleMain()
        assertEquals("the toggle did not switch S to plain", RenderMode.PLAIN, f.vm.renderMode.value)

        f.releaseS()

        assertTrue("S's Markdown TOC landed on its plain rendering", f.vm.headings.value.isEmpty())
    }

    /** Runs the main looper for a while, for assertions that something did NOT happen. */
    private fun settleMain() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_ROUNDS = 40
    }
}
