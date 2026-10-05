// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.rendering.ImagePlaceholderDrawable
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.components.MarkdownPreview
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-93: the image placeholder at Android's largest font size (2.0), in Dark and Light.
 *
 *  - `image_placeholder_reader_*_200`: body text with a remote picture's placeholder between two
 *    paragraphs; the placeholder's label is the same size as the body text.
 *  - `image_placeholder_tap_to_show_*_200`: the "Hut · Tap to show" placeholder on its own.
 *
 * The 1.0 look is held by the `markdown_link_and_image_*` goldens. Pixel goldens assert nothing
 * textually; a re-record must be looked at (M-56).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class ImagePlaceholderScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    private val scheme get() = if (themeMode == ThemeMode.DARK) DarkColorScheme else LightColorScheme
    private val theme get() = if (themeMode == ThemeMode.DARK) "dark" else "light"

    @Test
    fun readerAtLargestFont() {
        RuntimeEnvironment.setFontScale(LARGEST_FONT_SCALE)
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            CompositionLocalProvider(LocalMDColors provides scheme) {
                Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                    MarkdownPreview(content = READER_MARKDOWN, renderer = renderer)
                }
            }
        }
        drainMainLooper()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/image_placeholder_reader_${theme}_200.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun tapToShowAtLargestFont() {
        RuntimeEnvironment.setFontScale(LARGEST_FONT_SCALE)
        val metrics = composeRule.activity.resources.displayMetrics
        val drawable = ImagePlaceholderDrawable(
            "Hut",
            scheme,
            metrics,
            1f,
            maxWidthPx = metrics.widthPixels,
            actionLabel = ImagePlaceholderDrawable.TAP_TO_SHOW,
        )
        val margin = (MARGIN_DP * metrics.density).toInt()
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth + 2 * margin,
            drawable.intrinsicHeight + 2 * margin,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(scheme.primaryBackground.toArgb())
            translate(margin.toFloat(), margin.toFloat())
            drawable.draw(this)
        }
        bitmap.captureRoboImage(
            filePath = "src/test/screenshots/image_placeholder_tap_to_show_${theme}_200.png",
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
        private const val MARGIN_DP = 16
        private const val LARGEST_FONT_SCALE = 2.0f

        // A remote picture is never fetched, so it always draws the placeholder (M-93).
        private const val READER_MARKDOWN =
            "We walked up from the valley and reached the hut before lunch.\n\n" +
                "![Hut](https://example.com/hut.jpg)\n\n" +
                "The stove still works."

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    }
}
