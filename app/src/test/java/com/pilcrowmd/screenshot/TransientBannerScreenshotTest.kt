// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.R
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.screen.ChangedFileBanner
import com.pilcrowmd.ui.screen.TransientBanner
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
 * The calm "save a copy" banners under the toolbar, in Dark and Light, at the system font scale 1.0
 * and 2.0 (the largest Android offers), so the two-line text wrapping beside the close button is caught.
 *
 *  - `transient_banner_temporary_*`: "Temporary file opened from another app", with the close button.
 *  - `transient_banner_not_utf8_*`: the not-UTF-8 explanation, with NO close button.
 *  - `transient_banner_changed_*`: S8-A46, the file changed on another device, no unsaved edits.
 *  - `transient_banner_changed_edits_*`: the same with unsaved edits: Reload and Keep mine.
 *
 * Pixel goldens assert nothing textually; a re-record must be looked at (M-56).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h400dp-night-xhdpi")
class TransientBannerScreenshotTest(private val themeMode: ThemeMode, private val fontScale: Float) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun temporaryFile() = capture(R.string.main_banner_transient, closable = true, kind = "temporary")

    @Test
    fun notUtf8() = capture(R.string.main_banner_not_utf8, closable = false, kind = "not_utf8")

    @Test
    fun changedFile() = shoot("changed") { ChangedFileBanner(hasEdits = false, onReload = {}, onKeepMine = {}) }

    @Test
    fun changedFileWithEdits() = shoot("changed_edits") {
        ChangedFileBanner(hasEdits = true, onReload = {}, onKeepMine = {})
    }

    private fun capture(@StringRes text: Int, closable: Boolean, kind: String) = shoot(kind) {
        TransientBanner(
            onClick = {},
            text = stringResource(text),
            onClose = if (closable) ({}) else null,
        )
    }

    private fun shoot(kind: String, banner: @Composable () -> Unit) {
        val scheme = if (themeMode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalMDColors provides scheme,
                // The system font-size setting, which every sp in the banner follows.
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                    banner()
                }
            }
        }
        drainMainLooper()
        val theme = if (themeMode == ThemeMode.DARK) "dark" else "light"
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/transient_banner_${kind}_${theme}_${(fontScale * 100).toInt()}.png",
            roborazziOptions = roborazziOptions,
        )
    }

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
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf(ThemeMode.DARK, 1.0f),
            arrayOf(ThemeMode.LIGHT, 1.0f),
            arrayOf(ThemeMode.DARK, 2.0f),
            arrayOf(ThemeMode.LIGHT, 2.0f),
        )
    }
}
