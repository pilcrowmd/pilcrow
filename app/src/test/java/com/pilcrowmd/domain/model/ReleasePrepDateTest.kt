// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import com.pilcrowmd.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * M-174: a release-prep PR that bumps `versionName` but forgets `releasePrepDate` must fail here.
 *
 * The date must be later than the newest dated release in CHANGELOG.md for a version OTHER than the
 * current one. The current version's heading is excluded, not just `TBD`: the tag commit dates it,
 * possibly with the prep date itself, and that commit must still pass. Both inputs are file content,
 * so the verdict is the same on every machine and every day — no clock is read (F-Droid rebuilds).
 * This is the one test allowed to read the real `BuildConfig` date; nothing that renders may.
 */
class ReleasePrepDateTest {

    @Test
    fun `the release-prep date is later than the previous release`() {
        val changelog = File("../CHANGELOG.md").readText()
        val previous = newestOtherRelease(changelog, BuildConfig.VERSION_NAME)
        assertNotNull("CHANGELOG.md has no dated release other than ${BuildConfig.VERSION_NAME}", previous)
        val prep = LocalDate.parse(BuildConfig.RELEASE_PREP_DATE)
        assertTrue(
            "releasePrepDate $prep in app/build.gradle.kts is not later than the previous release " +
                "($previous) — the prep PR bumped versionName but not the date next to it",
            prep.isAfter(previous),
        )
    }

    @Test
    fun `the current version's own heading is skipped, dated or TBD`() {
        val changelog = """
            ## [Unreleased]
            ## [1.0.11] - 2026-10-02
            ## [1.0.10] - TBD
            ## [1.0.9] - 2026-09-21
            ## [1.0.8] - 2026-09-18
        """.trimIndent()
        assertEquals(LocalDate.parse("2026-09-21"), newestOtherRelease(changelog, "1.0.11"))
        assertEquals(LocalDate.parse("2026-10-02"), newestOtherRelease(changelog, "1.0.9"))
    }

    @Test
    fun `no dated heading means no answer`() {
        assertNull(newestOtherRelease("## [Unreleased]\n## [1.0.0] - TBD", "1.0.1"))
    }

    private fun newestOtherRelease(changelog: String, currentVersion: String): LocalDate? =
        Regex("""^## \[(\d+\.\d+\.\d+)] - (\d{4}-\d{2}-\d{2})\s*$""", RegexOption.MULTILINE)
            .findAll(changelog)
            .filter { it.groupValues[1] != currentVersion }
            .map { LocalDate.parse(it.groupValues[2]) }
            .maxOrNull()
}
