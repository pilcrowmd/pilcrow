// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.PilcrowColorScheme

/**
 * The navigation drawer and the screen it slides over, both inside the active colour scheme (M-171).
 *
 * The scheme is provided OUTSIDE [ModalNavigationDrawer] on purpose. The drawer composes
 * [drawerContent] and paints its scrim itself, not through its content slot, so a provider placed
 * inside the content slot never reaches them: they fell back to [LocalMDColors]' Dark default,
 * which drew a dark drawer over the light reader.
 *
 * M-307: with a hardware keyboard the drawer HOLDS focus while it is open. Tab used to walk out of
 * it into the reader behind the scrim. A close in keyboard mode, by any path, hands focus back to
 * where it was; a close by touch only clears the focus the drawer took.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    val drawerFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    // The TARGET, not the settled value: the content is shut the moment an open starts, and handed
    // back the moment a close starts, whichever path (Back, scrim, a row, a swipe) asked for it.
    val opening = { drawerState.targetValue == DrawerValue.Open }
    val focusManager = LocalFocusManager.current
    val emptyStopFocus = remember { FocusRequester() }
    var emptyStop by remember { mutableStateOf(false) }
    var drawerHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(drawerState) {
        var wasOpening = false
        var movedFocusIn = false
        snapshotFlow(opening).collect { nowOpening ->
            if (nowOpening != wasOpening) {
                val keyboard = inputMode.inputMode == InputMode.Keyboard
                if (nowOpening && keyboard) {
                    contentFocus.saveFocusedChild()
                    drawerFocus.requestFocus()
                    if (!drawerHasFocus) {
                        // No rows to land on (a document without headings): focus would stay on the
                        // button behind the scrim and Tab would walk the content from there. Clearing it
                        // is no better, an unhandled Tab goes on to the Android views. So the drawer gets
                        // a stop of its own to hold focus, for as long as it is open.
                        emptyStop = true
                        withFrameNanos { }
                        emptyStopFocus.requestFocus()
                    }
                    movedFocusIn = true
                } else if (!nowOpening && movedFocusIn) {
                    movedFocusIn = false
                    emptyStop = false
                    // A scrim tap or a swipe has flipped the mode to touch: nothing to restore, but the
                    // focus WE put in the drawer must not stay on an off-screen row.
                    if (keyboard) contentFocus.restoreFocusedChild() else focusManager.clearFocus()
                }
            }
            wasOpening = nowOpening
        }
    }
    CompositionLocalProvider(LocalMDColors provides colorScheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = {
                Box(
                    Modifier
                        .keepTabInside(isOpen = opening, focusManager = focusManager, hasFocus = { drawerHasFocus }) {
                            drawerFocus.requestFocus()
                        }
                        .focusRequester(drawerFocus)
                        .onFocusChanged { drawerHasFocus = it.hasFocus }
                        .focusGroup(),
                ) {
                    drawerContent()
                    if (emptyStop) Box(Modifier.focusRequester(emptyStopFocus).focusable())
                }
            },
            scrimColor = colorScheme.scrimOverlay,
            modifier = modifier,
        ) {
            // While the drawer is open Tab cannot enter the content, the Android views in it included.
            Box(
                Modifier
                    .focusRequester(contentFocus)
                    .focusProperties { enter = { if (opening()) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup(),
            ) { content() }
        }
    }
}

/**
 * While [isOpen], Tab is ours: left to Compose, a Tab past the last row leaves the drawer for the
 * screen behind it (on the emulator: Compose nodes hidden from the accessibility tree). It is always
 * consumed here, and if the move took focus out of the drawer ([hasFocus] false) [refocus] pulls it back.
 */
private fun Modifier.keepTabInside(
    isOpen: () -> Boolean,
    focusManager: FocusManager,
    hasFocus: () -> Boolean,
    refocus: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val handled = isOpen() && event.key == Key.Tab
    if (handled && event.type == KeyEventType.KeyDown) {
        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
        if (!hasFocus()) refocus()
    }
    handled
}
