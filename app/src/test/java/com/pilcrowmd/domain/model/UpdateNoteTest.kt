// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * M-174's gate, one boundary per pair: 59/60 is decided by the 60-day comparison alone and 73/74 by
 * the snooze comparison alone (the release is 73+ days old on both sides of that pair).
 */
class UpdateNoteTest {

    private val release = 1_700_000_000_000L
    private fun day(n: Int) = release + n * DAY_MILLIS
    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(defaultZone)

    @Test
    fun `hidden at 59 days, shown at 60 and 74`() {
        assertFalse(isUpdateNoteDue(release, day(59), dismissedAtMillis = null))
        assertTrue(isUpdateNoteDue(release, day(60), dismissedAtMillis = null))
        assertTrue(isUpdateNoteDue(release, day(74), dismissedAtMillis = null))
    }

    @Test
    fun `dismissed at day 60 it is hidden at day 73 and back at day 74`() {
        assertFalse(isUpdateNoteDue(release, day(73), dismissedAtMillis = day(60)))
        assertTrue(isUpdateNoteDue(release, day(74), dismissedAtMillis = day(60)))
    }

    @Test
    fun `a clock earlier than the release date hides it`() {
        assertFalse(isUpdateNoteDue(release, release - DAY_MILLIS, dismissedAtMillis = null))
    }

    @Test
    fun `an unknown release date hides it`() {
        assertFalse(isUpdateNoteDue(null, day(400), dismissedAtMillis = null))
    }

    @Test
    fun `the About month is taken in UTC, not the device timezone`() {
        // 1 October at UTC midnight is still 30 September in Los Angeles: a label computed in the
        // device's zone would say September.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals("1.0.11 · October 2026", versionLabel("1.0.11", utcMidnightMillis("2026-10-01")))
    }

    @Test
    fun `without a date About shows the bare version`() {
        assertEquals("1.0.11", versionLabel("1.0.11", null))
        assertEquals("", versionLabel("", utcMidnightMillis("2026-10-01")))
    }

    @Test
    fun `the prep date parses as UTC midnight, and a malformed one is null`() {
        assertEquals(1_790_726_400_000L, utcMidnightMillis("2026-09-30"))
        assertNull(utcMidnightMillis("2026-9-30"))
        assertNull(utcMidnightMillis("TBD"))
    }
}
