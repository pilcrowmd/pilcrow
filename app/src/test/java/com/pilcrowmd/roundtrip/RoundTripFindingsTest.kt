// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.roundtrip

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip documents that currently FAIL byte identity, one test each so each finding is visible by
 * name. Each was run and seen to fail before being ignored; remove the @Ignore when its fix lands.
 *
 * Observed, before the @Ignore went on (expected -> saved):
 *  - utf-16le-bom.md, 100 B -> 106 B: `FF FE 23 00 20 00 ...` -> `EF BF BD EF BF BD 23 00 20 00 ...`
 *  - windows-1252.md, 81 B -> 159 B: `43 61 66 E9 ...` ("Caf" + 0xE9) -> `43 61 66 EF BF BD ...`
 *  - latin-1.md, 154 B -> 360 B; truncated-utf-8.md, 11 B -> 12 B (`E2 82` at EOF -> `EF BF BD`)
 *  - cr-cr-lf.md, 33 B -> 32 B: `65 0D 0D 0A` -> `65 0D 0A`
 *
 * Re-run 6 Oct on main after 1.0.12: the four non-UTF-8 cases no longer write U+FFFD; the save is
 * refused (SaveRefusedNotUtf8) and the file is left as it was, which this rig reports as a timeout
 * (M-352). cr-cr-lf.md still loses its CR, 33 B -> 32 B (M-353).
 */
@RunWith(RobolectricTestRunner::class)
class RoundTripFindingsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rig: RoundTripRig

    @Before
    fun setup() {
        rig = RoundTripRig(
            context = ApplicationProvider.getApplicationContext(),
            docsDir = tempFolder.newFolder("docs"),
            walDir = tempFolder.newFolder("nobackup"),
            dataStoreFile = tempFolder.newFile("findings.preferences_pb"),
        )
    }

    @After
    fun tearDown() {
        rig.close()
    }

    @Test
    @Ignore("M-352: a non-UTF-8 file is now refused an in-place save (SaveRefusedNotUtf8), so it cannot round-trip")
    fun windows1252RoundTrips() = assertIdentical("windows-1252.md", NonUtf8Samples.windows1252)

    @Test
    @Ignore("M-352: a non-UTF-8 file is now refused an in-place save (SaveRefusedNotUtf8), so it cannot round-trip")
    fun latin1RoundTrips() = assertIdentical("latin-1.md", NonUtf8Samples.latin1)

    @Test
    @Ignore("M-352: a non-UTF-8 file is now refused an in-place save (SaveRefusedNotUtf8), so it cannot round-trip")
    fun utf16LeWithBomRoundTrips() = assertIdentical("utf-16le-bom.md", NonUtf8Samples.utf16leBom)

    @Test
    @Ignore("M-352: a non-UTF-8 file is now refused an in-place save (SaveRefusedNotUtf8), so it cannot round-trip")
    fun truncatedUtf8RoundTrips() = assertIdentical("truncated-utf-8.md", NonUtf8Samples.truncatedUtf8)

    /** A lone CR directly before a CRLF, in an otherwise all-CRLF file (a double-converted line). */
    @Test
    @Ignore("M-353: a CR before a CRLF is dropped - load folds \\r\\r\\n to \\r\\n, the CRLF save regex eats the CR")
    fun loneCrBeforeCrlfRoundTrips() =
        assertIdentical("cr-cr-lf.md", "line one\r\r\nline two\r\nline three\r\n".toByteArray())

    private fun assertIdentical(name: String, bytes: ByteArray) {
        when (val outcome = rig.roundTrip(name, bytes)) {
            is RoundTripOutcome.Failed -> fail("$name: ${outcome.reason}")
            is RoundTripOutcome.Saved -> assertTrue(
                RoundTripRig.describeMismatch(name, bytes, outcome.bytes),
                outcome.bytes.contentEquals(bytes),
            )
        }
    }
}
