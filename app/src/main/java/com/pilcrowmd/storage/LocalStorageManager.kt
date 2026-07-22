// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.preferencesDataStoreFile
import com.pilcrowmd.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

// Top-level (process-singleton) DataStore. DataStore requires exactly one active
// instance per file per process; declaring this inside the class made every
// LocalStorageManager open a second DataStore on the same file, which crashed the
// app on Activity recreation (e.g. device rotation).
//
// The store was renamed (older builds used a different file name); on first access
// [LegacyPrefsRenameMigration] copies any prefs from the old file into this one, so
// upgrading users never lose their settings.
private const val PREFS_NAME = "pilcrow_prefs"
private const val LEGACY_PREFS_NAME = "mdeasyreader_prefs"

// Corrupt prefs degrade to empty defaults instead of throwing. Preferences are read eagerly at
// startup, so an unguarded CorruptionException would crash-loop the app on launch with no escape;
// resetting settings to defaults is the safe failure (Safeguard 3 spirit applied to prefs).
private fun prefsCorruptionHandler() = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFS_NAME,
    corruptionHandler = prefsCorruptionHandler(),
    produceMigrations = { context ->
        listOf(LegacyPrefsRenameMigration(context.preferencesDataStoreFile(LEGACY_PREFS_NAME)))
    },
)

/**
 * One-shot, no-loss migration for the preferences-store rename. Copies every preference from the
 * legacy file into the new store (an existing new-store value wins on any shared key), then deletes
 * the legacy file so it runs at most once. Only runs while the legacy file still exists.
 */
internal class LegacyPrefsRenameMigration(private val legacyFile: File) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = legacyFile.exists()

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Read the legacy file through a DataStore whose scope we OWN and cancel, so this one-shot
        // migration never abandons a live SupervisorJob/Dispatchers.IO actor that would leak until
        // process death. Nothing else holds the legacy file (production now opens the renamed store),
        // so this transient reader is the only instance on it — safe per DataStore's one-instance rule.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            val legacy = PreferenceDataStoreFactory.create(
                corruptionHandler = prefsCorruptionHandler(),
                scope = scope,
                produceFile = { legacyFile },
            ).data.first()
            mergeLegacyPreferences(legacy, currentData)
        } finally {
            scope.cancel()
        }
    }

    override suspend fun cleanUp() {
        // The legacy prefs are now durably in the new store; drop the old file so we never re-migrate.
        legacyFile.delete()
    }
}

/**
 * Merge [legacy] preferences into [current], keeping [current]'s value on any key both define (the
 * renamed store is authoritative for anything written since the rename). Pure — unit-tested directly.
 */
internal fun mergeLegacyPreferences(legacy: Preferences, current: Preferences): Preferences =
    current.toMutablePreferences().apply {
        legacy.asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            val typedKey = key as Preferences.Key<Any>
            if (!contains(typedKey)) set(typedKey, value)
        }
    }.toPreferences()

/**
 * Local DataStore-based preferences manager.
 * Persists: last-opened file URI, line-numbers toggle, and recent files.
 * vNext hook: could be swapped for a cloud-sync implementation.
 */
class LocalStorageManager(
    context: Context,
    // Injected for testability: production uses the process-singleton above; tests pass an
    // ephemeral, per-test DataStore (temp file) for full isolation. Default keeps call sites simple.
    private val dataStore: DataStore<Preferences> = context.applicationContext.dataStore,
) : StorageManager {

    private val lastFileUriKey = stringPreferencesKey("last_file_uri")
    private val lineNumbersEnabledKey = booleanPreferencesKey("line_numbers_enabled")
    private val recentFilesKey = stringPreferencesKey("recent_files")
    private val scrollPositionsKey = stringPreferencesKey("scroll_positions")
    private val fontScaleKey = floatPreferencesKey("font_scale") // legacy, pre-split scale
    private val previewFontScaleKey = floatPreferencesKey("preview_font_scale")
    private val editorFontScaleKey = floatPreferencesKey("editor_font_scale")
    private val fontSetIdKey = stringPreferencesKey("font_set_id")
    private val mermaidCloudEnabledKey = booleanPreferencesKey("mermaid_cloud_enabled")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    private val maxRecents = 8

    // Tab separates fields, newline separates records — neither occurs in a content
    // URI or a typical file display name.
    private val fieldSep = "\t"
    private val recordSep = "\n"

    // distinctUntilChanged: DataStore emits the whole Preferences on every write, so without
    // this an unrelated write (scroll position, recents) would re-emit the same URI and could
    // re-trigger a file reload. Only emit when the last-file URI actually changes.
    override val lastFileUri: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[lastFileUriKey]?.let { Uri.parse(it) }
    }.distinctUntilChanged()

    override val lineNumbersEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[lineNumbersEnabledKey] ?: true // Default: enabled
    }

    override val recentFiles: Flow<List<RecentFile>> = dataStore.data.map { prefs ->
        decodeRecents(prefs[recentFilesKey])
    }

    override val scrollPositions: Flow<Map<Uri, ScrollAnchor>> = dataStore.data.map { prefs ->
        decodeScrollPositions(prefs[scrollPositionsKey])
    }

    override val fontScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[fontScaleKey] ?: 1.0f // Default: no scaling
    }.distinctUntilChanged()

    // Preview/Edit scales fall back to the legacy single scale when unset, so an
    // upgrading user's current size carries over to both until they change one explicitly.
    override val previewFontScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[previewFontScaleKey] ?: prefs[fontScaleKey] ?: 1.0f
    }.distinctUntilChanged()

    override val editorFontScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[editorFontScaleKey] ?: prefs[fontScaleKey] ?: 1.0f
    }.distinctUntilChanged()

    override val fontSetId: Flow<String> = dataStore.data.map { prefs ->
        prefs[fontSetIdKey] ?: "source" // Default: Source Serif 4 + JetBrains Mono
    }.distinctUntilChanged()

    override val mermaidCloudEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[mermaidCloudEnabledKey] ?: false // Default: OFF (offline/private)
    }.distinctUntilChanged()

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val modeStr = prefs[themeModeKey] ?: "DARK"
        runCatching { ThemeMode.valueOf(modeStr) }
            .getOrDefault(ThemeMode.DARK) // fallback to Dark on corrupt/unknown value
    }.distinctUntilChanged()

    override suspend fun saveLastFileUri(uri: Uri) {
        dataStore.edit { prefs -> prefs[lastFileUriKey] = uri.toString() }
    }

    override suspend fun clearLastFileUri() {
        dataStore.edit { prefs -> prefs.remove(lastFileUriKey) }
    }

    override suspend fun setLineNumbersEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[lineNumbersEnabledKey] = enabled }
    }

    override suspend fun addRecent(file: RecentFile) {
        dataStore.edit { prefs ->
            val current = decodeRecents(prefs[recentFilesKey])
                .filterNot { it.uri == file.uri } // de-dupe by uri
            val updated = (listOf(file) + current).take(maxRecents) // newest first, cap 8
            prefs[recentFilesKey] = encodeRecents(updated)
        }
    }

    override suspend fun removeRecent(uri: Uri) {
        dataStore.edit { prefs ->
            val updated = decodeRecents(prefs[recentFilesKey]).filterNot { it.uri == uri }
            if (updated.isEmpty()) {
                prefs.remove(recentFilesKey)
            } else {
                prefs[recentFilesKey] = encodeRecents(updated)
            }
        }
    }

    override suspend fun clearRecents() {
        dataStore.edit { prefs -> prefs.remove(recentFilesKey) }
    }

    override suspend fun saveScrollPosition(uri: Uri, anchor: ScrollAnchor) {
        dataStore.edit { prefs ->
            val current = decodeScrollPositions(prefs[scrollPositionsKey]).toMutableMap()
            current[uri] = anchor
            prefs[scrollPositionsKey] = encodeScrollPositions(current)
        }
    }

    override suspend fun getScrollPosition(uri: Uri): ScrollAnchor {
        return dataStore.data.map { prefs ->
            decodeScrollPositions(prefs[scrollPositionsKey])[uri] ?: ScrollAnchor()
        }.first()
    }

    override suspend fun saveFontScale(scale: Float) {
        dataStore.edit { prefs ->
            val clamped = scale.coerceIn(0.85f, 1.6f)
            prefs[fontScaleKey] = clamped
        }
    }

    override suspend fun savePreviewFontScale(scale: Float) {
        dataStore.edit { prefs -> prefs[previewFontScaleKey] = scale.coerceIn(0.85f, 1.6f) }
    }

    override suspend fun saveEditorFontScale(scale: Float) {
        dataStore.edit { prefs -> prefs[editorFontScaleKey] = scale.coerceIn(0.85f, 1.6f) }
    }

    override suspend fun saveFontSetId(id: String) {
        dataStore.edit { prefs -> prefs[fontSetIdKey] = id }
    }

    override suspend fun setMermaidCloudEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[mermaidCloudEnabledKey] = enabled }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[themeModeKey] = mode.name }
    }

    private fun encodeRecents(list: List<RecentFile>): String =
        list.joinToString(recordSep) { "${it.uri}$fieldSep${it.displayName}$fieldSep${it.lastOpened}" }

    private fun decodeRecents(raw: String?): List<RecentFile> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(recordSep).mapNotNull { record ->
            val parts = record.split(fieldSep)
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            RecentFile(uri = Uri.parse(parts[0]), displayName = parts[1], lastOpened = ts)
        }
    }

    private fun encodeScrollPositions(map: Map<Uri, ScrollAnchor>): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value.index}\t${it.value.offset}" }

    private fun decodeScrollPositions(raw: String?): Map<Uri, ScrollAnchor> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split("\n").mapNotNull { record ->
            // "uri \t index \t offset". Legacy 2-field records (absolute pixel offset, older format)
            // are dropped — that representation was the bug, so falling back to top is correct.
            val parts = record.split("\t")
            if (parts.size == 3) {
                val index = parts[1].toIntOrNull() ?: return@mapNotNull null
                val offset = parts[2].toIntOrNull() ?: return@mapNotNull null
                Uri.parse(parts[0]) to ScrollAnchor(index, offset)
            } else {
                null
            }
        }.toMap()
    }
}
