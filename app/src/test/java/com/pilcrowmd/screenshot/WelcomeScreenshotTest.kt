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
import com.pilcrowmd.ui.components.WelcomeScreen
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
 * Visual-regression (golden-image) test for the Welcome/home brand block — the large serif
 * "Pilcrow" wordmark over the faint ¶ watermark — captured in both Dark and Light themes with an
 * empty recents list (the first-launch state shown in the Pilcrow mockup). A future shift in the
 * wordmark size, watermark placement, divider, tagline, or button layout fails the verify task.
 *
 *   Record baseline:  ./gradlew recordRoborazziDebug
 *   Verify (gate):    ./gradlew verifyRoborazziDebug
 *
 * The theme comes from the provided color scheme (LocalMDColors), not system night mode, so both
 * goldens render under the one pinned config. Custom OFL fonts may fall back to a system serif under
 * Robolectric — the golden is still deterministic and catches layout regressions.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class WelcomeScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        // Cross-OS-calibrated tolerance. The welcome brand block (72sp serif wordmark + the
        // large faint ¶ watermark) has far more low-alpha glyph-edge area than the markdown samples,
        // so its cross-OS anti-aliasing delta is higher — measured at ~4.5% of pixels (macOS render
        // vs the committed goldens). 0.05 sits just above that, letting the SAME goldens pass across
        // OSes without re-recording while still catching a real brand-layout regression.
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun golden() {
        val colorScheme = when (themeMode) {
            ThemeMode.DARK -> DarkColorScheme
            ThemeMode.LIGHT -> LightColorScheme
        }
        val suffix = when (themeMode) {
            ThemeMode.DARK -> "dark"
            ThemeMode.LIGHT -> "light"
        }

        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colorScheme.primaryBackground),
            ) {
                CompositionLocalProvider(LocalMDColors provides colorScheme) {
                    WelcomeScreen(recentFiles = emptyList())
                }
            }
        }

        drainMainLooper()

        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/welcome_$suffix.png",
            roborazziOptions = roborazziOptions,
        )
    }

    /** Settle Compose so the brand block is laid out before capture (mirrors the renderer suite). */
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
