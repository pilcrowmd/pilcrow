// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the no-loss preferences-store rename merge. Verifies the core guarantee directly
 * (pure function, no DataStore file): legacy settings are carried over, and a value already written
 * to the renamed store is never clobbered by the old one.
 */
class LegacyPrefsRenameMigrationTest {

    private val theme = stringPreferencesKey("theme_mode")
    private val font = floatPreferencesKey("preview_font_scale")
    private val recents = stringPreferencesKey("recent_files")

    @Test
    fun mergeCopiesLegacyKeysButNeverClobbersNewerValues() {
        val legacy = preferencesOf(theme to "Dark", font to 1.2f, recents to "a\nb")
        val current = preferencesOf(theme to "Light") // already re-set in the new store

        val merged = mergeLegacyPreferences(legacy, current)

        // A shared key keeps the new store's value...
        assertEquals("Light", merged[theme])
        // ...and every legacy-only key is carried over — no setting is lost on the rename.
        assertEquals(1.2f, merged[font])
        assertEquals("a\nb", merged[recents])
    }

    @Test
    fun mergeWithEmptyLegacyLeavesCurrentUnchanged() {
        val current = preferencesOf(theme to "Light")

        val merged = mergeLegacyPreferences(preferencesOf(), current)

        assertEquals("Light", merged[theme])
        assertEquals(1, merged.asMap().size)
    }
}
