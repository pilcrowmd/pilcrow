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
 * search bar → TOC drawer → Licenses → GitHub teaser → Settings → open document → root.
 * (Compose [androidx.compose.material3.AlertDialog]s consume Back in their own window, so the
 * unsaved-close / stranded-slot dialogs are handled by the framework before this runs.)
 */
/** How long the "press back again to exit" window stays armed after the first root Back press. */
const val EXIT_CONFIRM_WINDOW_MS = 2000L

sealed interface BackIntent {
    /** Close the in-document search bar. */
    data object CloseSearch : BackIntent

    /** Close the open TOC drawer. */
    data object CloseDrawer : BackIntent

    /** Leave the Licenses sub-screen back to Settings (where it was opened from). */
    data object LicensesToSettings : BackIntent

    /** Leave the GitHub-integration sub-screen back to Settings. */
    data object GitHubToSettings : BackIntent

    /** Leave Settings back to the home/Welcome screen. */
    data object CloseSettings : BackIntent

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
    val searchVisible: Boolean,
    val drawerOpen: Boolean,
    val showLicenses: Boolean,
    val showGitHub: Boolean,
    val showSettings: Boolean,
    val hasDocument: Boolean,
    val documentDirty: Boolean,
    val exitArmed: Boolean,
)

/** Resolve the single [BackIntent] for a Back press given the current [state]. */
fun resolveBackIntent(state: BackNavState): BackIntent = when {
    state.searchVisible -> BackIntent.CloseSearch
    state.drawerOpen -> BackIntent.CloseDrawer
    state.showLicenses -> BackIntent.LicensesToSettings
    state.showGitHub -> BackIntent.GitHubToSettings
    state.showSettings -> BackIntent.CloseSettings
    state.hasDocument && state.documentDirty -> BackIntent.PromptUnsavedClose
    state.hasDocument -> BackIntent.CloseFile
    state.exitArmed -> BackIntent.Exit
    else -> BackIntent.ArmExit
}
