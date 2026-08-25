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
 * copy — so the exact code that froze the app for 294 s on a device would PASS here in milliseconds.
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
 * A reimplementation that allocated per match, built a match list, or took a substring per line
 * would fail here even on the JVM. Ratios are asserted rather than absolute times, so the test is
 * insensitive to how fast the machine running it happens to be.
 *
 * ## Measured margin (why these limits, and why these input sizes)
 *
 * Ratios are robust to machine speed but not to GC/JIT noise, and a guard that flakes gets disabled
 * by whoever is trying to merge late at night — after which it protects nothing while still appearing
 * in the file list. So the margin was measured rather than assumed, over repeated local runs of the
 * shipped linear implementation:
 *  - LINE axis: 0.82-0.97 against a limit of 4.0 (linear predicts ~1.0) — over 4x headroom.
 *  - BYTE axis: 3.36-4.25 against a limit of 8.0 (linear predicts 4.0, quadratic 16.0) — the mean
 *    sits on the prediction and the limit is ~2x it.
 *
 * The LINE-axis upper bound is 0.97 and not the 0.96 first recorded because it was RE-MEASURED on
 * the rebased branch — five runs gave 0.941/0.948/0.948/0.949/0.970 (mean 0.951), and the top
 * sample sat just outside the original span. The number was widened to the observation rather than
 * the observation waved through as noise: a recorded margin that the shipped code already exceeds
 * is worse than no record, because the next person reads it as the range to panic outside of. The
 * same five runs put the BYTE axis at 3.40-4.17 (mean 3.93), inside its recorded span, so that
 * line is unchanged. Neither LIMIT moved — the worst case is still 4.1x under the line-axis limit
 * and 1.9x under the byte-axis one.
 *
 * The byte-axis inputs were RAISED (8k/32k lines to 24k/96k) and trials raised to five specifically
 * to tighten that spread — it was 3.32-4.79 at the smaller sizes, and the worst case sat too close to
 * the ceiling for a slower CI runner. **The thresholds were NOT loosened to fit; the signal was raised
 * above the noise.** If this ever starts flaking, do the same again — raise the inputs, or move the
 * assertion to the instrumented lane. Do not tune the limits until it goes green, which would leave a
 * number that means nothing.
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
            override suspend fun readFile(uri: Uri) = Result.success("")
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

    /** Best-of-three total nanos for [REPS] calls — min, because noise only ever adds time. */
    private fun cost(text: String): Long {
        repeat(2) { viewModel.detectLineEnding(text) } // warm the JIT before measuring
        var best = Long.MAX_VALUE
        repeat(TRIALS) {
            val t0 = System.nanoTime()
            repeat(REPS) { viewModel.detectLineEnding(text) }
            best = minOf(best, System.nanoTime() - t0)
        }
        return best
    }

    @Test
    fun `cost does not blow up when LINE COUNT grows at a constant byte size`() {
        // ~2 MB either way; only the number of line endings differs, by 10x.
        val fewLines = document(lines = 16_000, bytesPerLine = 130)
        val manyLines = document(lines = 160_000, bytesPerLine = 13)
        assertEquals(
            "fixtures must be BYTE-IDENTICAL in size or this axis measures nothing",
            fewLines.length,
            manyLines.length,
        )
        assertEquals("CRLF", viewModel.detectLineEnding(fewLines))
        assertEquals("CRLF", viewModel.detectLineEnding(manyLines))

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
        val small = document(lines = 24_000, bytesPerLine = 64)
        val large = document(lines = 96_000, bytesPerLine = 64) // 4x the bytes, same density
        assertEquals("CRLF", viewModel.detectLineEnding(small))
        assertEquals("CRLF", viewModel.detectLineEnding(large))

        val ratio = cost(large).toDouble() / cost(small)
        assertTrue(
            "4x the bytes must cost about 4x, not 16x. Ratio was $ratio (limit $BYTE_AXIS_LIMIT), " +
                "which sits between linear (4x) and quadratic (16x) with margin either side.",
            ratio < BYTE_AXIS_LIMIT,
        )
    }

    private companion object {
        const val REPS = 20

        /** Best-of-N: noise only ever ADDS time, so the minimum is the least-contaminated sample. */
        const val TRIALS = 5

        /** Linear-in-bytes predicts ~1x here; 4x leaves room for allocation noise well below 10x. */
        const val LINE_AXIS_LIMIT = 4.0

        /** Linear predicts 4x, quadratic 16x. */
        const val BYTE_AXIS_LIMIT = 8.0
    }
}
