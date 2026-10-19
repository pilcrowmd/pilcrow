// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.net.Uri
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** A recently-opened file entry. */
data class RecentFile(val uri: Uri, val displayName: String, val lastOpened: Long)

/**
 * Preview reading position: the adapter [index] of the first visible block plus
 * the [offset] in pixels of that block's top relative to the viewport top (negative when the block
 * is partially scrolled above the fold). Restored via `LinearLayoutManager.scrollToPositionWithOffset`
 * — robust to block heights changing between sessions (async images, font swaps), unlike a single
 * absolute pixel offset.
 */
data class ScrollAnchor(val index: Int = 0, val offset: Int = 0)

/**
 * Abstraction for persistence (preferences, content, etc.).
 * v1 implements local DataStore. vNext hook: cloud sync/multi-device.
 */
interface StorageManager {
    /**
     * Flow of the last opened file URI. Persists across restarts.
     * v1: DataStore. vNext: could sync across devices.
     */
    val lastFileUri: Flow<Uri?>

    /**
     * Flow of the line-numbers-enabled toggle.
     * v1: DataStore. vNext: could sync user preferences.
     */
    val lineNumbersEnabled: Flow<Boolean>

    /**
     * Flow of the "open documents in edit mode" preference. Default: false (open in the reader).
     *
     * When true, opening a document starts it in [com.pilcrowmd.viewmodel.ViewMode.EDITOR]
     * instead of the reader. When false the view mode is left exactly as it was, which preserves
     * the pre-existing behaviour of the mode being sticky within a session.
     */
    val openInEditMode: Flow<Boolean>

    /**
     * Flow of recently-opened files, most-recent first, capped at 8.
     */
    val recentFiles: Flow<List<RecentFile>>

    /**
     * Persist the last opened file URI.
     */
    suspend fun saveLastFileUri(uri: Uri)

    /**
     * Clear the persisted last-opened file URI so the app does not auto-reopen it
     * (used by Close, for privacy).
     */
    suspend fun clearLastFileUri()

    /**
     * Persist the line-numbers toggle state.
     */
    suspend fun setLineNumbersEnabled(enabled: Boolean)

    /** Persist the "open documents in edit mode" preference. */
    suspend fun setOpenInEditMode(enabled: Boolean)

    /** Add (or move to top, updating timestamp) a recent file. Caps the list at 8. */
    suspend fun addRecent(file: RecentFile)

    /** Remove a single recent entry. */
    suspend fun removeRecent(uri: Uri)

    /** Clear the entire recents list. */
    suspend fun clearRecents()

    /**
     * Flow of per-file preview scroll anchors. Persists reading position across restarts.
     */
    val scrollPositions: Flow<Map<Uri, ScrollAnchor>>

    /**
     * Save the preview scroll anchor for a file URI.
     */
    suspend fun saveScrollPosition(uri: Uri, anchor: ScrollAnchor)

    /**
     * Retrieve the saved preview scroll anchor for a file URI. Returns [ScrollAnchor] (0, 0) — top
     * of the document — if none is stored.
     */
    suspend fun getScrollPosition(uri: Uri): ScrollAnchor

    /**
     * The user's remembered render-mode override for a file URI, or null when none is
     * stored — null means "use the extension default". Overrides are explicit in BOTH directions
     * (a `.txt` toggled to Markdown stores MARKDOWN; toggled back stores PLAIN), so a future
     * default change never silently flips a user's chosen view.
     */
    suspend fun getRenderModeOverride(uri: Uri): RenderMode?

    /** Persist ([mode] non-null) or clear ([mode] null) the render-mode override for a file URI. */
    suspend fun setRenderModeOverride(uri: Uri, mode: RenderMode?)

    /**
     * Flow of font scale multiplier. Default: 1.0f (no scaling). Persists across app restarts.
     *
     * Legacy key. Retained so [previewFontScale]/[editorFontScale] can
     * fall back to it for users upgrading from the single-scale build.
     */
    val fontScale: Flow<Float>

    /**
     * Save the font scale multiplier to storage.
     * Clamped to [0.85, 1.6]; out-of-bounds values are rejected.
     */
    suspend fun saveFontScale(scale: Float)

    /**
     * Flow of the Preview (reader) font scale. Separate from the editor.
     * Falls back to the legacy [fontScale] value when unset (migration).
     */
    val previewFontScale: Flow<Float>

    /** Save the Preview font scale. Clamped to [0.85, 1.6]. */
    suspend fun savePreviewFontScale(scale: Float)

    /**
     * Flow of the Edit (source) font scale. Separate from the preview.
     * Falls back to the legacy [fontScale] value when unset (migration).
     */
    val editorFontScale: Flow<Float>

    /** Save the Edit font scale. Clamped to [0.85, 1.6]. */
    suspend fun saveEditorFontScale(scale: Float)

    /**
     * Flow of the selected font-set id. Default: "source".
     */
    val fontSetId: Flow<String>

    /** Persist the selected font-set id. */
    suspend fun saveFontSetId(id: String)

    /**
     * Flow of the reader's "Wrap long lines in code blocks" setting. Default: false — long lines
     * side-scroll, as they always have (M-134; the default is a public commitment on issue #8).
     */
    val wrapCodeLines: Flow<Boolean>

    /** Persist the code-block wrap setting. */
    suspend fun setWrapCodeLines(enabled: Boolean)

    /** Flow of the editor's "Show formatting bar" setting. Default: true (M-217). */
    val formattingBarEnabled: Flow<Boolean>

    /** Persist the formatting-bar setting. */
    suspend fun setFormattingBarEnabled(enabled: Boolean)

    /**
     * Flow of the selected theme mode. Default: ThemeMode.DARK (ensures Dark remains default).
     * v1: DataStore. vNext: could sync across devices.
     */
    val themeMode: Flow<ThemeMode>

    /**
     * Persist the selected theme mode.
     */
    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * M-93: folders whose "show this note's pictures" banner the user answered with "Not now", by
     * folder key. The reader does not offer the banner for these again; "Tap to show" still works.
     */
    val dismissedImageFolders: Flow<Set<String>>

    /** Remember or forget a "Not now" for [folderKey]. */
    suspend fun setImageFolderDismissed(folderKey: String, dismissed: Boolean)

    /**
     * Documents the user has opened (M-177): every open they start that reaches a loaded document —
     * picker, "Open with", recents, Create. Re-opening a file counts again; a failed load and the
     * cold-start restore do not. Starts at 0, including for users upgrading to 1.0.13.
     */
    val documentsOpened: Flow<Int>

    /** Add one to [documentsOpened]. */
    suspend fun incrementDocumentsOpened()

    /** True once either button on the welcome-screen rating card was tapped; it never shows again (M-177). */
    val ratingCardDismissed: Flow<Boolean>

    /** Hide the welcome-screen rating card for good. */
    suspend fun dismissRatingCard()

    /** When the M-174 update note was last dismissed (epoch millis), or null if never. */
    val updateNoteDismissedAt: Flow<Long?>

    /** Record that the update note was dismissed at [atMillis]; it snoozes from then. */
    suspend fun dismissUpdateNote(atMillis: Long)

    // vNext hooks (not implemented in v1):
    // suspend fun syncPreferences(destination: SyncBackend): Result<Unit>
    // suspend fun uploadContent(uri: Uri, content: String): Result<CloudId>
}
