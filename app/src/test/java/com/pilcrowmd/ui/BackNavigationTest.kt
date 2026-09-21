// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import com.pilcrowmd.ui.screen.BackIntent
import com.pilcrowmd.ui.screen.BackNavState
import com.pilcrowmd.ui.screen.resolveBackIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure Back-navigation resolver — the decision logic behind Fix #1 (Back must
 * navigate within the app, not terminate it). Covers the precedence order and the double-back exit.
 */
class BackNavigationTest {

    // All-false root state; each test starts here and flips only the fields it exercises via copy().
    private val root = BackNavState(
        searchVisible = false,
        drawerOpen = false,
        showLicenses = false,
        showGitHub = false,
        showSettings = false,
        hasDocument = false,
        documentDirty = false,
        exitArmed = false,
        isSaving = false,
    )

    // ── M-148 ────────────────────────────────────────────────────────────────────────────────
    //
    // The toolbar's Close (X) is `enabled = !isSaving`; BackNavState had no such input, so system
    // Back closed a file mid-save. The state below is built so that ONLY the isSaving clause can
    // decide it: every earlier branch is false, and hasDocument/documentDirty are set so the
    // control case resolves to a real close. Without the control, this would pass against a
    // resolver that returned None for everything.

    @Test
    fun backIsInertWhileAWriteIsInFlight() {
        val saving = root.copy(hasDocument = true, isSaving = true)
        assertEquals("Back must not close a file mid-write", BackIntent.None, resolveBackIntent(saving))
        // THE CONTROL: the identical state with the write finished still closes. Without this the
        // assertion above is satisfied by a resolver that never closes anything.
        assertEquals(
            "control: the same state closes once the write is done",
            BackIntent.CloseFile,
            resolveBackIntent(saving.copy(isSaving = false)),
        )
    }

    @Test
    fun backDoesNotEvenPromptWhileAWriteIsInFlight() {
        // A DIRTY document would otherwise resolve to PromptUnsavedClose, which is a close path
        // too — the gate sits above both, so it must beat the prompt as well as the close.
        val savingDirty = root.copy(hasDocument = true, documentDirty = true, isSaving = true)
        assertEquals(BackIntent.None, resolveBackIntent(savingDirty))
        assertEquals(
            "control: the same state prompts once the write is done",
            BackIntent.PromptUnsavedClose,
            resolveBackIntent(savingDirty.copy(isSaving = false)),
        )
    }

    @Test
    fun aWriteInFlightDoesNotSwallowTheOtherBackTargets() {
        // The gate is NOT a blanket "Back does nothing while saving": search and the drawer are
        // still dismissible, because they close nothing on disk. Ordering, proved rather than
        // assumed.
        assertEquals(
            BackIntent.CloseSearch,
            resolveBackIntent(root.copy(hasDocument = true, isSaving = true, searchVisible = true)),
        )
        assertEquals(
            BackIntent.CloseDrawer,
            resolveBackIntent(root.copy(hasDocument = true, isSaving = true, drawerOpen = true)),
        )
    }

    @Test
    fun rootFirstBackArmsExit() {
        assertEquals(BackIntent.ArmExit, resolveBackIntent(root))
    }

    @Test
    fun rootSecondBackExits() {
        assertEquals(BackIntent.Exit, resolveBackIntent(root.copy(exitArmed = true)))
    }

    @Test
    fun openCleanDocumentClosesToHome() {
        assertEquals(BackIntent.CloseFile, resolveBackIntent(root.copy(hasDocument = true)))
    }

    @Test
    fun openDirtyDocumentPromptsUnsavedClose() {
        assertEquals(
            BackIntent.PromptUnsavedClose,
            resolveBackIntent(root.copy(hasDocument = true, documentDirty = true)),
        )
    }

    @Test
    fun settingsBackToHome() {
        assertEquals(BackIntent.CloseSettings, resolveBackIntent(root.copy(showSettings = true)))
    }

    @Test
    fun licensesBackToSettings() {
        assertEquals(BackIntent.LicensesToSettings, resolveBackIntent(root.copy(showLicenses = true)))
    }

    @Test
    fun gitHubBackToSettings() {
        assertEquals(BackIntent.GitHubToSettings, resolveBackIntent(root.copy(showGitHub = true)))
    }

    @Test
    fun searchTakesPrecedenceOverEverything() {
        // Search bar is the most specific surface — even with a dirty document open and the drawer
        // somehow open, Back closes search first.
        assertEquals(
            BackIntent.CloseSearch,
            resolveBackIntent(
                root.copy(searchVisible = true, drawerOpen = true, hasDocument = true, documentDirty = true),
            ),
        )
    }

    @Test
    fun drawerClosesBeforeFileCloses() {
        assertEquals(
            BackIntent.CloseDrawer,
            resolveBackIntent(root.copy(drawerOpen = true, hasDocument = true)),
        )
    }

    @Test
    fun subScreenClosesBeforeFileEvenWithDocumentOpen() {
        // Settings is reached from home, but guard the precedence regardless: a sub-screen unwinds
        // before the document close path.
        assertEquals(
            BackIntent.CloseSettings,
            resolveBackIntent(root.copy(showSettings = true, hasDocument = true, documentDirty = true)),
        )
    }
}
