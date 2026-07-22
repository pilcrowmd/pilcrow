// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A3 (corruption resilience). A corrupt preferences file must degrade to defaults, never crash —
 * the prefs are read eagerly at startup (e.g. `lastFileUri.first()` in the ViewModel init), so an
 * unguarded `CorruptionException` would crash-loop the app on launch with no escape. The
 * `ReplaceFileCorruptionHandler` replaces a corrupt file with empty preferences instead of throwing
 * (Safeguard 3 spirit applied to prefs). Verified here on the legacy-read path inside the migration;
 * the main `pilcrow_prefs` store uses the identical handler.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyPrefsCorruptionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val theme = stringPreferencesKey("theme_mode")

    @Test
    fun corruptLegacyFileDegradesToDefaultsInsteadOfThrowing() = runBlocking {
        val legacyFile = File(tempFolder.newFolder(), "legacy.preferences_pb")
        // Malformed protobuf: a length-delimited field tag (0x0A) claiming 127 bytes with no payload
        // — DataStore's preferences serializer throws on read, which without a handler propagates as
        // a CorruptionException and crash-loops the app at startup.
        legacyFile.writeBytes(byteArrayOf(0x0A, 0x7F, 0x42, 0x42, 0x42))

        // With the corruption handler the migration must NOT throw: the corrupt legacy file degrades
        // to empty, so the current store's value is preserved and nothing is lost to a crash.
        val merged = LegacyPrefsRenameMigration(legacyFile).migrate(preferencesOf(theme to "DARK"))

        assertEquals("DARK", merged[theme])
    }
}
