// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The "temporary file" banner: the text starts Save a copy, the close button (only when the caller
 * gives one) closes the notice, and the two taps never reach each other's handler. Without
 * `onClose` there is no close node at all, which is how the not-UTF-8 banner stays undismissable.
 */
@RunWith(RobolectricTestRunner::class)
class TransientBannerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0
    private var closes = 0

    private fun show(withClose: Boolean) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                TransientBanner(onClick = { clicks++ }, onClose = if (withClose) ({ closes++ }) else null)
            }
        }
    }

    private val closeLabel get() = composeRule.activity.getString(R.string.main_banner_close)

    @Test
    fun tappingTheTextStartsSaveACopyAndDoesNotClose() {
        show(withClose = true)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.main_banner_transient)).performClick()

        assertEquals("the text tap did not reach onClick", 1, clicks)
        assertEquals("the text tap closed the notice", 0, closes)
    }

    @Test
    fun tappingTheCloseButtonClosesAndDoesNotStartSaveACopy() {
        show(withClose = true)

        composeRule.onNodeWithContentDescription(closeLabel).performClick()

        assertEquals("the close tap did not reach onClose", 1, closes)
        assertEquals("the close tap started Save a copy", 0, clicks)
    }

    @Test
    fun withoutOnCloseThereIsNoCloseButton() {
        show(withClose = false)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.main_banner_transient)).assertExists()
        composeRule.onNodeWithContentDescription(closeLabel).assertDoesNotExist()
    }
}
