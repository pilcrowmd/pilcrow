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
import com.pilcrowmd.ui.components.GitHubIntegrationScreen
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
 * Visual-regression goldens for the GitHub-integration roadmap teaser (view-layer only):
 *  - `settings_github_card_*` — the full Settings screen (tall viewport so the Integrations card is
 *    in frame), guarding the relabelled "GitHub integration" card + the accent "Coming soon" pill;
 *  - `github_integration_screen_*` — the new teaser sub-screen (heading, copy, accent button).
 * Both in Dark + Light. A future change to the card or screen layout fails the verify task.
 *
 *   Record baseline:  ./gradlew recordRoborazziDebug
 *   Verify (gate):    ./gradlew verifyRoborazziDebug
 *
 * Theme is driven by the provided color scheme (LocalMDColors), not system night mode, so both
 * goldens render under the one pinned config. The tall height keeps the scrolling Settings column
 * fully in the captured viewport.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp-night-xhdpi")
class GitHubTeaserScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Cross-OS-calibrated tolerance, matching the Welcome golden: text-heavy screens carry more
    // low-alpha glyph-edge area, so the cross-OS anti-aliasing delta sits higher.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    private fun colorScheme() = when (themeMode) {
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.LIGHT -> LightColorScheme
    }

    private fun suffix() = when (themeMode) {
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
    }

    @Test
    fun settingsGithubCard() {
        val scheme = colorScheme()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    SettingsScreen(appVersion = "1.0.0")
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/settings_github_card_${suffix()}.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun gitHubIntegrationScreen() {
        val scheme = colorScheme()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    GitHubIntegrationScreen(onClose = {})
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/github_integration_screen_${suffix()}.png",
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
