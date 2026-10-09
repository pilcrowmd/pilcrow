// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.roundtrip

import androidx.test.core.app.ApplicationProvider
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

/**
 * Round-trip corpus (Safeguard 2, content fidelity): open a real file, save it unchanged, and the
 * bytes on disk must be IDENTICAL to the original — for every document in [RoundTripCorpus].
 *
 * One test per category, each looping its documents through [RoundTripRig] (a fresh ViewModel per
 * document) and reporting every mismatch with its first differing byte. Documents known to fail are
 * NOT here: they are pinned, one test each, in [RoundTripFindingsTest].
 *
 * No `Dispatchers.setMain`, deliberately: the rig drives the main looper itself, so this class runs in
 * `testDebugUnitTest`. That task also carries AGP's `--add-opens java.base/java.io` flag, without which
 * Robolectric cannot hand out the real ParcelFileDescriptors the repository reads and writes through.
 */
@RunWith(RobolectricTestRunner::class)
class RoundTripCorpusTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rig: RoundTripRig

    @Before
    fun setup() {
        rig = RoundTripRig(
            context = ApplicationProvider.getApplicationContext(),
            docsDir = tempFolder.newFolder("docs"),
            walDir = tempFolder.newFolder("nobackup"),
            dataStoreFile = tempFolder.newFile("roundtrip.preferences_pb"),
        )
    }

    @After
    fun tearDown() {
        rig.close()
    }

    /**
     * A loop over zero documents passes. Pin the corpus size so a broken extractor or a missing
     * resource fails here instead of turning every category test into a no-op.
     */
    @Test
    fun corpusHasTheExpectedShape() {
        assertEquals("CommonMark 0.31.2 has 652 examples", 652, RoundTripCorpus.commonMarkExamples.size)
        assertEquals("GFM 0.29 has 672 examples", 672, RoundTripCorpus.gfmExamples.size)
        assertEquals(8, RoundTripCorpus.realWorld.size)
        assertEquals(8 * 6 + 10, RoundTripCorpus.lineEndingVariants.size)
        assertTrue(RoundTripCorpus.unicode.size >= 10)
        assertTrue(RoundTripCorpus.tables.size >= 5)
        assertTrue(RoundTripCorpus.mixedEndings.size >= 5)
        assertTrue("corpus must hold 300+ documents", RoundTripCorpus.passing.size >= 300)
        assertEquals(
            "document names must be unique (each is its own file)",
            RoundTripCorpus.passing.size,
            RoundTripCorpus.passing.map { it.name }.toSet().size,
        )
    }

    @Test
    fun commonMarkSpecExamplesRoundTrip() = assertRoundTrips(RoundTripCorpus.commonMarkExamples)

    @Test
    fun gfmSpecExamplesRoundTrip() = assertRoundTrips(RoundTripCorpus.gfmExamples)

    @Test
    fun realWorldDocumentsRoundTrip() = assertRoundTrips(RoundTripCorpus.realWorld)

    @Test
    fun lineEndingAndBomVariantsRoundTrip() = assertRoundTrips(RoundTripCorpus.lineEndingVariants)

    @Test
    fun unicodeDocumentsRoundTrip() = assertRoundTrips(RoundTripCorpus.unicode)

    @Test
    fun hugeAndWideTablesRoundTrip() = assertRoundTrips(RoundTripCorpus.tables)

    /**
     * ACCEPTED LIMITATION, asserted explicitly: mixed endings are written back normalised to the
     * dominant style. Each fixture really is mixed (its expected bytes differ from its input), so this
     * test checks the normalisation, not an accidental identity.
     */
    @Test
    fun mixedEndingsAreNormalisedToTheDominantStyle() {
        RoundTripCorpus.mixedEndings.forEach {
            assertFalse("${it.name} must actually be mixed", it.bytes.contentEquals(it.expected))
        }
        assertRoundTrips(RoundTripCorpus.mixedEndings)
    }

    private fun assertRoundTrips(cases: List<CorpusCase>) {
        assertTrue("empty category", cases.isNotEmpty())
        val failures = cases.mapNotNull { case ->
            when (val outcome = rig.roundTrip(case.name, case.bytes)) {
                is RoundTripOutcome.Failed -> "${case.name}: ${outcome.reason}"
                is RoundTripOutcome.Saved ->
                    if (outcome.bytes.contentEquals(case.expected)) {
                        null
                    } else {
                        RoundTripRig.describeMismatch(case.name, case.expected, outcome.bytes)
                    }
            }
        }
        assertTrue(
            "${failures.size} of ${cases.size} documents did not round-trip:\n" +
                failures.take(MAX_REPORTED).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private companion object {
        const val MAX_REPORTED = 25
    }
}
