// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.PilcrowColorScheme

/**
 * The navigation drawer and the screen it slides over, both inside the active colour scheme (M-171).
 *
 * The scheme is provided OUTSIDE [ModalNavigationDrawer] on purpose. The drawer composes
 * [drawerContent] and paints its scrim itself, not through its content slot, so a provider placed
 * inside the content slot never reaches them: they fell back to [LocalMDColors]' Dark default,
 * which drew a dark drawer over the light reader.
 */
@Suppress("LongParameterList") // ModalNavigationDrawer's own parameters, plus the scheme.
@Composable
internal fun ThemedNavigationDrawer(
    colorScheme: PilcrowColorScheme,
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMDColors provides colorScheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = drawerContent,
            scrimColor = colorScheme.scrimOverlay,
            modifier = modifier,
            content = content,
        )
    }
}
