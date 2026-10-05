// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards `detectLineEnding` against a return to SUPERLINEAR cost.
 *
 * ## What this test can and cannot see — read before trusting it
 *
 * **It CANNOT observe the defect that caused the outage.** The production ANR (Play Vitals, 1.0.3)
 * came from `Regex.findAll(...).count()` on ANDROID, where each match re-enters ICU's
 * `MatcherNative.setInput` and re-copies the whole document, making the true cost
 * BYTES x MATCHES. This test runs on the JVM, where `java.util.regex` is O(n) and does no per-match
 * copy — so the exact code that froze the app for 294 s on a device would PASS here.
 * That is not a flaw to be fixed by tuning thresholds; it is a property of the runtime.
 *
 * **This was VERIFIED, not assumed: both tests below were run against the exact regex implementation
 * that caused the ANR, and both PASSED.** So this file would not have caught the defect it was
 * written in response to. **The real guard is the instrumented (androidTest) lane, tracked as its own
 * item in `docs/MAINTENANCE.md`. Do not delete that lane on the grounds that "we already have a
 * scaling test" — measured, this is not one.**
 *
 * **What it DOES cover:** algorithmic superlinearity visible on the JVM, on BOTH axes that matter:
 *  - the LINE-COUNT axis at constant byte size — the dominant term in the original defect, and the
 *    one a byte-only sweep would have missed entirely (device UAT: two fixtures of identical size,
 *    14,152 vs 75,934 lines, behaved completely differently);
 *  - the BYTE axis at constant line density.
 * A reimplementation that rescanned the text per match, or took a substring or copy per line, would
 * fail here.
 *
 * ## Counted, not timed (M-215)
 *
 * The cost is the number of characters `detectLineEnding` reads, counted by [CountingText]: every
 * `get`, plus the full length of any `subSequence` or `toString` copy. The count depends only on the
 * input, so the ratios below are the same on every machine and every run. The earlier version timed
 * the calls with `System.nanoTime` and asserted the same ratios; it failed once on CI (#257, first
 * run on `4dc6549`) and passed on rerun, because a shared runner's GC and JIT noise reached the
 * limit. The limits are unchanged; only the measurement is.
 *
 * The shipped loop reads each character once and, at each `\n` after the first character, the one
 * before it. That gives these exact ratios for the fixtures below:
 *  - LINE axis: 1.069 (10x the line endings at the same byte size) against a limit of 4.0;
 *  - BYTE axis: 4.000 (4x the bytes at the same line density) against a limit of 8.0, where linear
 *    predicts 4.0 and quadratic 16.0.
 * Any implementation that stays linear, with any constant number of passes, gives the same ratios.
 * The fixtures are 10x smaller than the timed version's, because a count needs no signal above noise;
 * the smaller size also keeps a superlinear regression from making the test hang.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownViewModelLineEndingScalingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageScope: CoroutineScope
    private lateinit var viewModel: MarkdownViewModel

    @Before
    fun setup() {
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("scaling_test.preferences_pb")
        }
        val repository = object : FileRepository {
            override suspend fun readFile(uri: Uri) = Result.success(FileText("", isUtf8 = true))
            override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
            override suspend fun recoverPendingSaves() = Result.success(0)
            override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
            override suspend fun displayName(uri: Uri): String = "test.md"
            override fun hasPersistedPermission(uri: Uri): Boolean = true
            override fun hasPersistedWritePermission(uri: Uri): Boolean = true
            override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
            override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
            override suspend fun discardSlot(key: String) = Result.success(Unit)
        }
        val parseHeadings = ParseMarkdownHeadingsUseCase()
        viewModel = MarkdownViewModel(
            repository = repository,
            storage = LocalStorageManager(ApplicationProvider.getApplicationContext(), dataStore),
            parseHeadingsUseCase = parseHeadings,
            searchUseCase = SearchMarkdownUseCase(parseHeadings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName: String = "test"
            },
        )
    }

    @After
    fun tearDown() = storageScope.cancel()

    /** A CRLF document of [lines] lines, each padded to [bytesPerLine] — the two axes, independently. */
    private fun document(lines: Int, bytesPerLine: Int): String {
        val body = "x".repeat((bytesPerLine - 2).coerceAtLeast(1))
        return buildString(lines * bytesPerLine) { repeat(lines) { append(body).append("\r\n") } }
    }

    /**
     * Text that counts the characters read from it: one per [get], and the full length of any copy
     * taken through [subSequence] or [toString], so a per-line copy is not free.
     */
    private class CountingText(private val text: String) : CharSequence {
        var reads = 0L
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            reads += endIndex - startIndex
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String {
            reads += text.length
            return text
        }
    }

    /** Characters read by one call on [text], checking the answer on the way. */
    private fun cost(text: String): Long {
        val counted = CountingText(text)
        assertEquals("CRLF", viewModel.detectLineEnding(counted))
        return counted.reads
    }

    @Test
    fun `cost does not blow up when LINE COUNT grows at a constant byte size`() {
        // ~208 KB either way; only the number of line endings differs, by 10x.
        val fewLines = document(lines = 1_600, bytesPerLine = 130)
        val manyLines = document(lines = 16_000, bytesPerLine = 13)
        assertEquals(
            "fixtures must be BYTE-IDENTICAL in size or this axis measures nothing",
            fewLines.length,
            manyLines.length,
        )

        val ratio = cost(manyLines).toDouble() / cost(fewLines)
        assertTrue(
            "10x the line endings at the SAME byte size must not cost materially more — the shipped " +
                "loop is O(bytes) and indifferent to line count. Ratio was $ratio (limit $LINE_AXIS_LIMIT). " +
                "A ratio near 10 means cost is tracking MATCHES again, which is the shape of the 1.0.3 ANR.",
            ratio < LINE_AXIS_LIMIT,
        )
    }

    @Test
    fun `cost grows about linearly when BYTE SIZE grows at a constant line density`() {
        val small = document(lines = 2_400, bytesPerLine = 64)
        val large = document(lines = 9_600, bytesPerLine = 64) // 4x the bytes, same density

        val ratio = cost(large).toDouble() / cost(small)
        assertTrue(
            "4x the bytes must cost about 4x, not 16x. Ratio was $ratio (limit $BYTE_AXIS_LIMIT), " +
                "which sits between linear (4x) and quadratic (16x) with margin either side.",
            ratio < BYTE_AXIS_LIMIT,
        )
    }

    private companion object {
        /** Linear-in-bytes predicts ~1x here; 4x leaves room for a linear loop that reads more per line ending. */
        const val LINE_AXIS_LIMIT = 4.0

        /** Linear predicts 4x, quadratic 16x. */
        const val BYTE_AXIS_LIMIT = 8.0
    }
}
