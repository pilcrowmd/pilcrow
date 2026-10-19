// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.largefile

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.testing.LargeDocumentSuite
import com.pilcrowmd.viewmodel.ViewMode
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **M-172 — the reader must never parse the whole document on the main thread.**
 *
 * Play Vitals shows `PreviewKt.setContentForMode` blocking input dispatch: the reader parsed the
 * whole document inside the `AndroidView.update` lambda, i.e. during composition, on Main. Each
 * test drives one of the three triggers MAINTENANCE.md lists for that parse — a file opening, a
 * rebuild-key change (here the font scale), and the reader re-entering composition — through the
 * real ViewModel and the real `MainScreen`.
 *
 * **The probe.** Every whole-document parse on the reader path ends in [ReaderDocument.transform]
 * (the shared post-parse pass), called on the thread that parsed. It is spied, not replaced — the
 * original still runs — and each call records whether it ran on the main looper.
 *
 * **Why each test also asserts the probe FIRED.** "No call on Main" is vacuously true if the parse
 * never ran at all; the open test therefore requires at least one call, so the probe is proven live
 * before its silence is trusted. The two re-render tests assert NO main-thread call only: whether the
 * fixed reader re-parses at all there (it need not — nothing about the text changed) is a design
 * choice, not the defect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
// Own JVM (M-399). The third case hands the document to the word-wrapping editor, and under
// Robolectric Sora's wrap task can spin forever: the JDK's line BreakIterator walks back with
// previous() until DONE, and Sora's CharSequenceIterator never returns DONE at the start of a
// line, so a break point inside a line's first word never returns (a phone is not affected: its
// BreakIterator copies the text first). The task runs on Sora's one static two-thread pool, so
// once two of them spin, every editor measured later in the same JVM waits forever. Running this
// class in its own JVM keeps that from reaching the other editor tests.
@Category(LargeDocumentSuite::class)
class ReaderMainThreadParseTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: ReaderHarness

    /** One entry per whole-document parse on the reader path: true if it ran on the main thread. */
    private val parsedOnMain = CopyOnWriteArrayList<Boolean>()

    // 64 KB, not the 1 MB the other large-document tests use: the assertions are about WHICH thread
    // parses, so the size does not change what they can catch, and this class shares the
    // phone-sized heap of testLargeDocumentDebug with a Compose activity and an editor copy.
    private val content = LargeMarkdownFixture.generate(LargeMarkdownFixture.MB / 16)
    private val uri = Uri.parse("content://test/large.md")

    @Before
    fun setUp() {
        harness = ReaderHarness(composeRule, tempFolder)
        mockkObject(ReaderDocument)
        every { ReaderDocument.transform(any(), any(), any(), any()) } answers {
            parsedOnMain += Looper.myLooper() == Looper.getMainLooper()
            callOriginal()
        }
    }

    @After
    fun tearDown() {
        unmockkObject(ReaderDocument)
        harness.close()
    }

    private fun openAndAwaitReader() {
        val vm = harness.viewModel(content)
        harness.show(vm)
        vm.loadFile(uri)
        harness.awaitReader("the reader shows the document") { harness.readerShowsBlocks() }
        println("M-172 open: main-thread busy ${harness.mainThreadBusyMs} ms for a ${content.length}-char document")
        this.vm = vm
    }

    private lateinit var vm: com.pilcrowmd.viewmodel.MarkdownViewModel

    @Test
    fun `opening a document does not parse it on the main thread`() {
        openAndAwaitReader()

        assertTrue("probe never fired: the document was not parsed at all", parsedOnMain.isNotEmpty())
        assertTrue(
            "the reader parsed the whole document on the MAIN thread (M-172): $parsedOnMain",
            parsedOnMain.none { it },
        )
    }

    @Test
    fun `a font-scale change re-renders without parsing on the main thread`() {
        openAndAwaitReader()
        val before = harness.readerList()!!.adapter
        parsedOnMain.clear()

        vm.setPreviewFontScale(1.25f)
        // A scale change rebuilds the adapter; waiting for the swap is the barrier for that rebuild.
        harness.awaitReader("the reader rebuilt at the new scale") {
            harness.readerList()?.adapter.let { it != null && it !== before && it.itemCount > 0 }
        }

        assertTrue(
            "a font-scale rebuild parsed the whole document on the MAIN thread (M-172): $parsedOnMain",
            parsedOnMain.none { it },
        )
    }

    @Test
    fun `returning from the editor without edits does not parse on the main thread`() {
        openAndAwaitReader()
        val readerBefore = harness.readerList()
        vm.setMode(ViewMode.EDITOR)
        harness.awaitReader("the reader left the screen") { harness.readerList() == null }
        parsedOnMain.clear()

        vm.setMode(ViewMode.READER)
        // The reader re-enters composition as a NEW list; waiting for it to show blocks is the barrier.
        harness.awaitReader("the reader is back and shows the document") {
            harness.readerList().let { it != null && it !== readerBefore } && harness.readerShowsBlocks()
        }

        assertTrue(
            "re-entering the reader parsed the whole document on the MAIN thread (M-172): $parsedOnMain",
            parsedOnMain.none { it },
        )
    }
}
