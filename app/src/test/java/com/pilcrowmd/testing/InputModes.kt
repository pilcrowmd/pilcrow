// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Puts the composition in keyboard mode and checks it took, so a test of "only in keyboard mode"
 * fixes the mode BEFORE the code under test runs. (Robolectric starts in keyboard mode already; the
 * explicit call keeps the test true if that default ever changes.)
 *
 * There is deliberately no touch-mode twin. In touch mode Compose refuses focus to every focusable
 * that is not a text field, so a test that "nothing took focus" there passes whatever the code does.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun InputModeManager.useKeyboardMode() {
    assertTrue("keyboard mode was refused", requestInputMode(InputMode.Keyboard))
    assertEquals("precondition: the input mode", InputMode.Keyboard, inputMode)
}
