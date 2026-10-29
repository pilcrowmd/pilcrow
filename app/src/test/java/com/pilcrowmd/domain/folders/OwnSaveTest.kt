// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S8-A46: only the provider's own date for the app's save is taken in silently. */
class OwnSaveTest {

    private val save = OwnSave(size = 7L, at = 1_000L)

    @Test
    fun `the date moving alone, at the size written, inside the wait, settles the save`() {
        assertTrue(save.settles(42L, 7L, 50L, 7L, now = 1_000L + OwnSave.SETTLE_MS))
    }

    @Test
    fun `anything else is a real change`() {
        assertFalse("size moved", save.settles(42L, 7L, 50L, 8L, now = 2_000L))
        assertFalse("baseline at another size", save.settles(42L, 6L, 50L, 7L, now = 2_000L))
        assertFalse("after the wait", save.settles(42L, 7L, 50L, 7L, now = 1_001L + OwnSave.SETTLE_MS))
        assertFalse("clock set back", save.settles(42L, 7L, 50L, 7L, now = 999L))
        assertFalse("size unknown", save.settles(42L, null, 50L, null, now = 2_000L))
        assertFalse("nothing moved", save.settles(42L, 7L, 42L, 7L, now = 2_000L))
        assertFalse("date no longer given", save.settles(42L, 7L, null, 7L, now = 2_000L))
        assertFalse("date not given before", save.settles(null, 7L, 50L, 7L, now = 2_000L))
    }
}
