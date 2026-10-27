// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

/**
 * Pure decision logic for the system/gesture Back button, extracted from [MainScreen] so it is
 * unit-testable without an Activity or Compose runtime (the composable just executes the resolved
 * intent). Back must navigate *within* the app instead of terminating it: it unwinds the most
 * specific open surface first, then closes the file, and only the home/root screen exits — and even
 * then via a double-back guard.
 *
 * Precedence (most specific first) mirrors the visible z-order in [MainScreen]:
 * Files → search bar → TOC drawer → Licenses → Settings → open document → root.
 * Files (M-162b) comes first because it covers everything else, the reader's search bar included.
 * (Compose [androidx.compose.material3.AlertDialog]s consume Back in their own window, so the
 * unsaved-close / stranded-slot dialogs are handled by the framework before this runs.)
 */
/** How long the "press back again to exit" window stays armed after the first root Back press. */
const val EXIT_CONFIRM_WINDOW_MS = 2000L

sealed interface BackIntent {
    /** Close the Files screen (M-162b), back to whatever it was opened over. */
    data object CloseFiles : BackIntent

    /** Close the in-document search bar. */
    data object CloseSearch : BackIntent

    /** Close the open TOC drawer. */
    data object CloseDrawer : BackIntent

    /** Leave the Licenses sub-screen back to Settings (where it was opened from). */
    data object LicensesToSettings : BackIntent

    /** Leave Settings back to the home/Welcome screen. */
    data object CloseSettings : BackIntent

    /**
     * Do nothing (M-148). A write is in flight, so Back is inert — the same answer the disabled
     * Close (X) gives. Not `Exit`: swallowing Back for the duration of a save is the point.
     */
    data object None : BackIntent

    /**
     * K10 (M-160): back to the note the last followed link was in. Through the reader's normal open,
     * so a note with unsaved edits still gets the unsaved-changes prompt first (K11).
     */
    data object LinkBack : BackIntent

    /** Open document has unsaved edits — show the existing Save/Discard close prompt (no new dialog). */
    data object PromptUnsavedClose : BackIntent

    /** Close the (clean) open document, returning to home. */
    data object CloseFile : BackIntent

    /** Root screen, first back press — arm the "press back again to exit" window. */
    data object ArmExit : BackIntent

    /** Root screen, second back press within the window — exit the app. */
    data object Exit : BackIntent
}

/** Immutable snapshot of the navigation state Back depends on. */
data class BackNavState(
    /** The Files screen (M-162b) is shown. */
    val showFiles: Boolean,
    val searchVisible: Boolean,
    val drawerOpen: Boolean,
    val showLicenses: Boolean,
    val showSettings: Boolean,
    val hasDocument: Boolean,
    /**
     * A file write is in flight (M-149's claim). **M-148:** the toolbar's Close (X) has been
     * gated on this all along; Back was not, so system Back closed a file mid-save. The two must
     * agree — a second way to do the same thing that skips the gate IS the defect.
     */
    val isSaving: Boolean,
    val documentDirty: Boolean,
    val exitArmed: Boolean,
    /** K10 (M-160): the open note was opened by a followed link, and Back returns to the note it was in. */
    val canGoBackLink: Boolean = false,
)

/** Resolve the single [BackIntent] for a Back press given the current [state]. */
fun resolveBackIntent(state: BackNavState): BackIntent = overlayIntent(state) ?: documentIntent(state)

/** The surfaces drawn over the document or home screen, most specific first; null when none is open. */
private fun overlayIntent(state: BackNavState): BackIntent? = when {
    state.showFiles -> BackIntent.CloseFiles
    state.searchVisible -> BackIntent.CloseSearch
    state.drawerOpen -> BackIntent.CloseDrawer
    state.showLicenses -> BackIntent.LicensesToSettings
    state.showSettings -> BackIntent.CloseSettings
    else -> null
}

/** The open document, then the root screen. */
private fun documentIntent(state: BackNavState): BackIntent = when {
    // M-148: ordered ABOVE both close branches. A write is in flight, so neither closing nor
    // prompting to close is allowed — Back does nothing, exactly as the disabled X does nothing.
    state.isSaving -> BackIntent.None
    // K10: after following a link, Back returns to the previous note instead of closing this one.
    state.hasDocument && state.canGoBackLink -> BackIntent.LinkBack
    state.hasDocument && state.documentDirty -> BackIntent.PromptUnsavedClose
    state.hasDocument -> BackIntent.CloseFile
    state.exitArmed -> BackIntent.Exit
    else -> BackIntent.ArmExit
}
