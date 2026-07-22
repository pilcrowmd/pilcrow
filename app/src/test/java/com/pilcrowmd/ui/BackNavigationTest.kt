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
    )

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
