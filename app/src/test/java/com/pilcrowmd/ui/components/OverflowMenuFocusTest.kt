// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.testing.useKeyboardMode
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-308: the "More actions" popup was announced only as "Pop-Up Window". It now carries the pane
 * title "More actions" (the fail-first test below).
 *
 * The keyboard-focus test is NOT a fail-first test and tests no app code: it passes without any,
 * because the popup window focuses its first item ("Save a copy") by itself. It guards that default,
 * so a future change to the menu cannot silently lose it. There is no touch-mode check for the
 * reason in [useKeyboardMode].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class OverflowMenuFocusTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show() {
        lateinit var manager: InputModeManager
        composeRule.setContent {
            manager = LocalInputModeManager.current
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) { PilcrowToolbar() }
        }
        composeRule.runOnIdle { manager.useKeyboardMode() }
    }

    private fun openMenu() {
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.waitForIdle()
    }

    private fun paneTitles(): List<String> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle), useUnmergedTree = true)
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .map { it.config[SemanticsProperties.PaneTitle] }

    @Test
    fun theOpenMenuIsAPaneNamedMoreActions() {
        show()
        assertEquals("precondition: no pane before the menu opens", emptyList<String>(), paneTitles())
        openMenu()
        assertEquals(listOf("More actions"), paneTitles())
    }

    /**
     * TalkBack names a popup by its WINDOW's title, not the pane title inside it, and said "Pop-Up
     * Window" (Compose's default). The popup's root view is the one the window manager holds.
     */
    @Test
    fun thePopupWindowIsTitledMoreActionsForTalkBack() {
        show()
        openMenu()
        assertEquals("More actions", popupWindowParams().title?.toString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun popupWindowParams(): WindowManager.LayoutParams {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        val popup = views.single { it.javaClass.simpleName == "PopupLayout" }
        return popup.layoutParams as WindowManager.LayoutParams
    }

    @Test
    fun inKeyboardModeOpeningTheMenuFocusesSaveACopy() {
        show()
        openMenu()
        composeRule.onNodeWithText("Save a copy").assertIsFocused()
    }
}
