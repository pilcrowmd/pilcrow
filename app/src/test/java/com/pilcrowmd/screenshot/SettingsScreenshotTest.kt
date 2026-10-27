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
import com.pilcrowmd.ui.theme.AmoledColorScheme
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
 * Visual-regression goldens for the full Settings screen at 1.0 font scale (tall viewport so the
 * whole column is in frame), in Dark, AMOLED (M-92) and Light, each with the matching colour scheme:
 *  - `settings_rate_card_*` — the Feedback section with the Play-only "Rate PilcrowMD" card (M-176);
 *  - `settings_about_release_month_*` — About with the longest version label (M-174).
 * Together they also pin the 1.0 look of the whole screen.
 *
 * NOTE: these are PIXEL goldens and assert nothing textually. Re-recording them is what makes a
 * copy change "pass", so a green verify after a re-record proves nothing on its own: the recorded
 * PNGs must be looked at by a human in the diff (M-56).
 *
 *   Record baseline:  ./gradlew recordRoborazziDebug
 *   Verify (gate):    ./gradlew verifyRoborazziDebug
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-night-xhdpi")
class SettingsScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Cross-OS-calibrated tolerance, matching the Welcome golden: text-heavy screens carry more
    // low-alpha glyph-edge area, so the cross-OS anti-aliasing delta sits higher.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    private fun colorScheme() = when (themeMode) {
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.AMOLED -> AmoledColorScheme
        ThemeMode.LIGHT -> LightColorScheme
    }

    private fun suffix() = when (themeMode) {
        ThemeMode.DARK -> "dark"
        ThemeMode.AMOLED -> "amoled"
        ThemeMode.LIGHT -> "light"
    }

    /**
     * M-176: the "Rate PilcrowMD" card under the Feedback entries, as a Play install sees it.
     */
    @Test
    fun settingsRateCard() {
        val scheme = colorScheme()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    SettingsScreen(appVersion = "1.0.0", showRateCard = true)
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/settings_rate_card_${suffix()}.png",
            roborazziOptions = roborazziOptions,
        )
    }

    /**
     * M-174: About with the version's release month, the longest label it will carry. A NEW golden;
     * the value is injected, never the real BuildConfig date, which changes every release. Taken at
     * 384 dp, the S24+'s width: at the class's 411 dp the title fitted beside the version, and the
     * wrap it had on the phone never showed here.
     */
    @Test
    @Config(qualifiers = "w384dp-h1600dp-night-xhdpi")
    fun settingsAboutReleaseMonth() {
        val scheme = colorScheme()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    SettingsScreen(appVersion = "1.0.11 · September 2026")
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/settings_about_release_month_${suffix()}.png",
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
        fun themes(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.AMOLED, ThemeMode.LIGHT)
    }
}
