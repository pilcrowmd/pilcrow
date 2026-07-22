// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.components.MarkdownPreview
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
import kotlin.math.roundToInt

/**
 * One screenshot case: a [sample] rendered at a given [fontScale] and [themeMode].
 * The label (e.g. "headings@160_light") names both the parameterized test and the golden file,
 * so a diff is self-describing.
 */
data class ScreenshotCase(val sample: MarkdownSample, val fontScale: Float, val themeMode: ThemeMode) {
    val scalePct: Int get() = (fontScale * 100).roundToInt()
    val themeSuffix: String get() = when (themeMode) {
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
    }
    override fun toString(): String = "${sample.name}@${scalePct}_$themeSuffix"
}

/**
 * Visual-regression (golden-image) tests for the Markdown renderer.
 *
 * Each [MarkdownSample] is rendered by the REAL renderer ([MarkdownPreview] → Markwon →
 * RecyclerView of native TextViews) under Robolectric's NATIVE graphics, then captured — once per
 * font scale (0.85–1.6) and per theme (Dark, Light), so text-clipping and line-height regressions
 * at min/default/max zoom and across both themes are caught. Any future change to padding,
 * typography, or line-height that shifts the rendered layout fails the verify task with a pixel diff.
 *
 * Total cases: 16 samples × 3 scales × 2 themes = 96 test cases.
 *
 *   Record baseline:  ./gradlew recordRoborazziDebug
 *   Verify (gate):    ./gradlew verifyRoborazziDebug
 *
 * Determinism: the device is pinned (width/density); the main looper is fully drained before
 * capture so the RecyclerView is laid out and async JLatexMath bitmaps are set. A small compare
 * threshold absorbs cross-OS anti-aliasing (record + verify should run on the same OS).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Qualifier order follows Android resource precedence (night mode before density), else
// Robolectric's parser rejects it. Pins width/height/dark-theme/density for deterministic capture.
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class MarkdownScreenshotTest(private val case: ScreenshotCase) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Instance field, NOT companion: the parameterized runner reads @Parameters by loading the
    // class OUTSIDE the Robolectric sandbox, where RoborazziOptions' Robolectric hooks NPE.
    private val roborazziOptions = RoborazziOptions(
        // Cross-OS-calibrated tolerance (supersedes the earlier strict-0.0f stance). The
        // committed goldens are the single source of truth; verifying them on a different OS than
        // they were recorded on differs ONLY by sub-pixel font anti-aliasing — measured at ≤0.5% of
        // pixels across every markdown sample (record-and-diff on macOS vs the goldens). 0.01 sits
        // just above that floor, so the SAME goldens pass on macOS and the CI runner WITHOUT
        // re-recording, while still failing any real layout/typography/color regression (which moves
        // far more than 1% of pixels — a single shifted block or token change is ≫1%).
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    @Test
    fun golden() {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }

            // Select color scheme based on theme
            val colorScheme = when (case.themeMode) {
                ThemeMode.DARK -> DarkColorScheme
                ThemeMode.LIGHT -> LightColorScheme
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(colorScheme.primaryBackground),
            ) {
                CompositionLocalProvider(LocalMDColors provides colorScheme) {
                    MarkdownPreview(
                        content = case.sample.markdown,
                        renderer = renderer,
                        fontScale = case.fontScale,
                    )
                }
            }
        }

        drainMainLooper()

        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/markdown_${case.sample.name}_${case.scalePct}_${case.themeSuffix}.png",
            roborazziOptions = roborazziOptions,
        )
    }

    /**
     * Settle Compose + posted Markwon/JLatexMath work so layout and LaTeX bitmaps exist at capture.
     * Drains until the main looper is genuinely idle (not a fixed iteration count), with a safety
     * cap so a pathological repost can never hang the test instead of failing it.
     */
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
        // Generous upper bound: the read-only reader posts a handful of layout runnables, never
        // an unbounded stream — this only exists so a bug fails loudly instead of hanging CI.
        private const val MAX_DRAIN_PASSES = 50

        // The app clamps the font scale to 0.85–1.6, so these are the real min/default/max — 2.0 would
        // never occur on device. Each sample is captured at all three.
        private val FONT_SCALES = listOf(0.85f, 1.0f, 1.6f)

        // Both themes are captured: Dark + Light. Total cases = samples × scales × themes.
        private val THEMES = listOf(ThemeMode.DARK, ThemeMode.LIGHT)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<ScreenshotCase> {
            val samples = MarkdownSampleProvider().values.toList()
            return samples.flatMap { sample ->
                FONT_SCALES.flatMap { scale ->
                    THEMES.map { theme ->
                        ScreenshotCase(sample, scale, theme)
                    }
                }
            }
        }
    }
}
