// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.largefile

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.testing.LargeDocumentSuite
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * **M-154 — switching a large document to the editor must not run out of memory.**
 *
 * The phone lost the process on the tap on Editor with a 33 MB document the reader had shown fine.
 * Each subclass opens one size through the real ViewModel and `MainScreen`, in a JVM whose heap is
 * sized like the phone's (`testLargeDocumentDebug`), and switches to the editor. It then waits until
 * the editor holds the whole document AND Sora's highlighter thread has stopped working — the
 * highlighter is where most of the editor's memory goes, and it runs AFTER the editor appears, so a
 * test that stopped at "the editor appeared" would pass against the unfixed code.
 *
 * **Loose about HOW, strict about the crash** (as `LargeDocumentOpenTest`): after the switch the
 * document is either in the editor, or the screen keeps showing the reader in editor mode because
 * the document is too large to edit. An `OutOfMemoryError` on any thread is never acceptable.
 * [expect] removes the read-only way out for a size the app promises to edit, so a fix cannot pass
 * by refusing everything. The read-only outcome is read off the screen (editor mode, the reader's
 * list on screen, no editor holding text), not off an API, so the SAME test decides both the
 * unfixed and the fixed code.
 *
 * **No typing here.** A keystroke takes Sora's `Content` write lock, and Sora's word-wrap pass holds
 * the read lock for its whole run; under Robolectric that pass uses the JDK's `BreakIterator` and
 * takes many minutes at these sizes (measured: > 10 min at 2.1 MB, > 20 min at 4 MB), so the
 * keystroke — an uninterruptible lock wait — hangs the test. Typing in the unhighlighted editor is
 * pinned on a small document by `MarkdownEditorSyntaxHighlightingTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LargeDocumentSuite::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
abstract class LargeDocumentEditorTest(private val sizeChars: Int, private val expect: Expect) {

    enum class Expect {
        /** Either the editor or the read-only reader; only a crash fails. */
        ANY_SAFE_OUTCOME,

        /** The document must open in the editor. */
        OPENS_IN_EDITOR,

        /** The document must open in the editor, with no highlighter. */
        OPENS_WITHOUT_HIGHLIGHTER,
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var harness: ReaderHarness
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var idlePolls = 0

    @Before
    fun setUp() {
        harness = ReaderHarness(composeRule, tempFolder)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        harness.close()
    }

    @Test(timeout = TIMEOUT_MS)
    fun `switching to the editor does not run out of memory`() {
        var thrown: Throwable? = null
        var outcome = "none"
        try {
            val vm = harness.viewModel(LargeMarkdownFixture.generate(sizeChars))
            harness.show(vm)
            vm.loadFile(Uri.parse("content://test/large-$sizeChars.md"))
            harness.awaitReader("the document shows in the reader", TIMEOUT_MS) {
                uncaught.isNotEmpty() || harness.readerShowsBlocks() || vm.fileLoadState.value is FileLoadState.Error
            }
            vm.setMode(ViewMode.EDITOR)
            harness.awaitReader("the editor holds the document and its highlighter is done, or read-only", TIMEOUT_MS) {
                uncaught.isNotEmpty() || (editorLength() == sizeChars && highlighterIdle()) || readOnly(vm)
            }
            outcome = when {
                uncaught.isNotEmpty() -> "crashed"
                editorLength() == sizeChars -> "editor"
                readOnly(vm) -> "read-only"
                else -> "none"
            }
            println("M-154 $sizeChars chars: outcome=$outcome")
        } catch (e: OutOfMemoryError) {
            thrown = e
        }
        val oom = (listOfNotNull(thrown) + uncaught).filter { e -> causes(e).any { it is OutOfMemoryError } }
        val frames = oom.map { e ->
            causes(e).last().stackTrace.firstOrNull { it.className.startsWith("com.pilcrowmd") }
                ?: causes(e).last().stackTrace.firstOrNull()
        }
        assertTrue("the editor on a $sizeChars-char document ran out of memory (M-154), frames: $frames", oom.isEmpty())
        assertTrue("an unexpected uncaught throwable: $uncaught", uncaught.isEmpty())
        assertExpected(outcome)
    }

    private fun assertExpected(outcome: String) {
        when (expect) {
            Expect.ANY_SAFE_OUTCOME -> {
                assertTrue("neither editor nor read-only: $outcome", outcome != "none")
                // The read-only state's notice (M-154): it must say why, not just refuse.
                if (outcome == "read-only") composeRule.onNodeWithText(READ_ONLY_NOTICE).assertExists()
            }
            Expect.OPENS_IN_EDITOR -> assertEquals("must open in the editor", "editor", outcome)
            Expect.OPENS_WITHOUT_HIGHLIGHTER -> {
                assertEquals("must open in the editor", "editor", outcome)
                // `!is TextMateLanguage`, NOT `is EmptyLanguage`: TextMateLanguage EXTENDS EmptyLanguage,
                // so the latter holds with the highlighter on too (it passed on d93f716 — caught by the
                // red run).
                val language = harness.editor()!!.editorLanguage
                assertFalse("no highlighter above the highlighting ceiling: $language", language is TextMateLanguage)
            }
        }
    }

    private fun editorLength(): Int = harness.editor()?.takeIf { it.isAttachedToWindow }?.text?.length ?: -1

    /** Editor mode, the reader's list on screen, and no editor holding the document. */
    private fun readOnly(vm: MarkdownViewModel): Boolean =
        vm.mode.value == ViewMode.EDITOR && harness.readerShowsBlocks() && editorLength() <= 0

    /**
     * True once no Sora analysis thread has been RUNNABLE for three polls in a row — finished, died,
     * or never started (no highlighter). Its looper thread stays alive after finishing, so "alive"
     * is not the signal; "working" is.
     */
    private fun highlighterIdle(): Boolean {
        val working = Thread.getAllStackTraces().any { (thread, stack) ->
            thread.state == Thread.State.RUNNABLE &&
                stack.any { it.className.startsWith("io.github.rosemoe.sora.lang.analysis") }
        }
        idlePolls = if (working) 0 else idlePolls + 1
        return idlePolls >= IDLE_POLLS
    }

    private fun causes(e: Throwable): List<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()

    private companion object {
        const val TIMEOUT_MS = 240_000L
        const val MAX_CAUSES = 8
        const val IDLE_POLLS = 3
        const val READ_ONLY_NOTICE = "This file is too large to edit here. You can still read and search it."
    }
}

/** Far above anything the phone can edit: the 80 MB file that also crashed the reader (M-179). */
class LargeDocumentEditor80MbTest : LargeDocumentEditorTest(80 * LargeMarkdownFixture.MB, Expect.ANY_SAFE_OUTCOME)

/** The M-179 marginal size; near the M-154 phone observation (33 MB). */
class LargeDocumentEditor25MbTest : LargeDocumentEditorTest(25 * LargeMarkdownFixture.MB, Expect.ANY_SAFE_OUTCOME)

/**
 * Just under the reader's Markdown ceiling (10 M chars), where the reader's full tree is still alive
 * when the editor opens. The app must still open it in the editor.
 */
class LargeDocumentEditor9MbTest : LargeDocumentEditorTest(9 * LargeMarkdownFixture.MB, Expect.OPENS_IN_EDITOR)

/**
 * Just above the proposed highlighting ceiling (2 M chars): the editor opens without a highlighter.
 * Not a memory reproduction — this size fits the heap either way; it pins the middle tier through
 * the real screen, and fails on the unfixed code on the highlighter assertion.
 */
class LargeDocumentEditorPlainTierTest : LargeDocumentEditorTest(2_100_000, Expect.OPENS_WITHOUT_HIGHLIGHTER)
