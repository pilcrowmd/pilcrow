// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.components.SettingsScreen
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
 * Visual-regression goldens for the whole Settings screen at the system's largest font scale
 * (2.0), in Dark + Light. They guard the large-text layout: the size readouts stay on one line and
 * the reading-font pills grow to show their whole label. The 1.0 look is guarded separately by
 * `settings_github_card_*` in [GitHubTeaserScreenshotTest].
 *
 * PIXEL goldens: a re-record makes any change "pass", so re-recorded PNGs must be looked at.
 *
 * The very tall viewport keeps the doubled-size scrolling column fully in frame.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2600dp-night-xhdpi", fontScale = 2.0f)
class SettingsLargeFontScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Same cross-OS tolerance as the 1.0 Settings golden (text-heavy screen).
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun settingsAtFontScale200() {
        val (scheme, suffix) = when (themeMode) {
            ThemeMode.DARK -> DarkColorScheme to "dark"
            ThemeMode.LIGHT -> LightColorScheme to "light"
        }
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    SettingsScreen(appVersion = "1.0.0")
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/settings_font200_$suffix.png",
            roborazziOptions = roborazziOptions,
        )
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
