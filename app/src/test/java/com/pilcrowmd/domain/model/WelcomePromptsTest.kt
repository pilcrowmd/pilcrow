// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-177's gate. Every case starts from a fixture where ALL other gates already pass, so the one
 * input it names is the only thing that can decide the answer — a cheaper gate never answers first.
 */
class WelcomePromptsTest {

    private val installed = 1_000_000_000_000L

    private fun due(
        isPlayInstall: Boolean = true,
        opens: Int = 5,
        days: Long = 7,
        dismissed: Boolean = false,
        updateNoteDue: Boolean = false,
    ) = isRatingCardDue(
        isPlayInstall = isPlayInstall,
        opens = opens,
        firstInstallMillis = installed,
        nowMillis = installed + days * DAY_MILLIS,
        dismissed = dismissed,
        updateNoteDue = updateNoteDue,
    )

    @Test
    fun `5 opens on day 7 shows the card`() = assertTrue(due())

    @Test
    fun `4 opens on day 7 hides it`() = assertFalse(due(opens = 4))

    @Test
    fun `5 opens on day 6 hides it`() = assertFalse(due(days = 6))

    @Test
    fun `one millisecond short of 7 days hides it`() = assertFalse(
        isRatingCardDue(true, 5, installed, installed + 7 * DAY_MILLIS - 1, dismissed = false, updateNoteDue = false),
    )

    @Test
    fun `either button hides it for good, a year and many opens later`() =
        assertFalse(due(opens = 500, days = 365, dismissed = true))

    @Test
    fun `the update note wins when both are due`() = assertFalse(due(updateNoteDue = true))

    @Test
    fun `a non-Play install never shows it, at 50 opens and 365 days`() =
        assertFalse(due(isPlayInstall = false, opens = 50, days = 365))

    @Test
    fun `a device clock set before the install hides it`() = assertFalse(due(days = -30))

    /** An unreadable install time is not "installed at epoch zero", which would pass the day gate. */
    @Test
    fun `an unknown install time hides it, at 50 opens`() = assertFalse(
        isRatingCardDue(true, 50, firstInstallMillis = null, installed, dismissed = false, updateNoteDue = false),
    )
}
