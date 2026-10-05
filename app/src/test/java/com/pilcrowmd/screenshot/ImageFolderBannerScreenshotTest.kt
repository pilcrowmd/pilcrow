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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.components.ImageFolderBannerView
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.ImageFolderBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-93: the folder banner, both kinds, in Dark and Light, at the system font scale 1.0 and 2.0 (the
 * largest Android offers), so a long line wrapping at big text is caught.
 *
 *  - `image_folder_banner_ask_*`: "This note has 2 pictures in its folder." with Not now / Allow folder.
 *  - `image_folder_banner_cannot_*`: the Download/storage-root explanation with OK.
 *
 * Pixel goldens assert nothing textually; a re-record must be looked at (M-56).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h400dp-night-xhdpi")
class ImageFolderBannerScreenshotTest(private val themeMode: ThemeMode, private val fontScale: Float) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun askForFolder() = capture(ImageFolderBanner.AskForFolder(pictureCount = 2), "ask")

    @Test
    fun cannotShowHere() = capture(ImageFolderBanner.CannotShowHere, "cannot")

    private fun capture(banner: ImageFolderBanner, kind: String) {
        val scheme = if (themeMode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalMDColors provides scheme,
                // The system font-size setting, which every sp in the banner follows.
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                    ImageFolderBannerView(banner = banner, onAllow = {}, onDismiss = {})
                }
            }
        }
        drainMainLooper()
        val theme = if (themeMode == ThemeMode.DARK) "dark" else "light"
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/image_folder_banner_${kind}_${theme}_${(fontScale * 100).toInt()}.png",
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
