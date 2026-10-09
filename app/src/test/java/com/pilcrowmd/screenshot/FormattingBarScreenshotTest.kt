// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.components.FormattingBar
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual-regression goldens for the editor's formatting bar (M-217), closed and with More open, in
 * Dark + Light. The bar sits at the bottom of a short editor-coloured viewport, where it lives above
 * the keyboard. PIXEL goldens: a re-record makes any change "pass", so re-recorded PNGs must be
 * looked at.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h120dp-night-xhdpi")
class FormattingBarScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun suffix() = when (themeMode) {
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
    }

    private fun show() {
        val scheme = when (themeMode) {
            ThemeMode.DARK -> DarkColorScheme
            ThemeMode.LIGHT -> LightColorScheme
        }
        composeRule.setContent {
            Box(
                modifier = Modifier.fillMaxSize().background(scheme.primaryBackground),
                contentAlignment = Alignment.BottomCenter,
            ) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    FormattingBar(onAction = {}, onUndo = {}, onRedo = {})
                }
            }
        }
    }

    @Test
    fun formattingBarClosed() {
        show()
        drainMainLooper()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/formatting_bar_closed_${suffix()}.png")
    }

    @Test
    fun formattingBarWithMoreOpen() {
        show()
        composeRule.onNodeWithContentDescription("More formatting").performClick()
        drainMainLooper()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/formatting_bar_more_${suffix()}.png")
    }

    /** Settle Compose so the screen is laid out before capture (mirrors the other screenshot suites). */
    private fun drainMainLooper() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var guard = 0
        do {
            composeRule.waitForIdle()
            mainLooper.idle()
            check(guard++ < MAX_DRAIN_PASSES) { "Main looper never went idle before capture" }
        } while (!mainLooper.isIdle)
    }

    companion object {
        private const val MAX_DRAIN_PASSES = 50

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    }
}
