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
 * "PilcrowMD" wordmark over the faint ¶ watermark — captured in both Dark and Light themes with an
 * empty recents list (the first-launch state), in BOTH orientations. A future shift in the
 * wordmark size, watermark placement, divider, tagline, or button layout fails the verify task.
 *
 * The landscape pair is not redundant with the portrait pair. The ¶ watermark is clipped to its own
 * measured height, which the parent caps at the VIEWPORT height, so a ¶ whose ink outgrows the
 * viewport is cut — and a short landscape viewport reveals that at a size a tall portrait one
 * absorbs without visible change. That defect shipped to a device once precisely because no golden
 * rendered landscape; these two cases are the regression guard for the constraint recorded above the
 * watermark in WelcomeScreen.kt. Note the constraint is NOT "the ¶ clears the viewport" — it does
 * not on the S24+, and no size does on every phone. It is that the cut stays flush with the screen
 * edge instead of being dragged mid-screen by a negative offset. Get device geometry from the device
 * (`adb shell wm size` / `wm density`); never read it off a golden.
 *
 * BE HONEST ABOUT WHAT THESE CATCH, because the last person to trust them was wrong. Measured by
 * perturbing WelcomeScreen and running verify against these goldens:
 *
 *   watermark 280 -> 300dp / 320dp ......... passes (both orientations)
 *   watermark 280 -> 360dp ................. fails  (both)
 *   top padding 40 -> 80dp / 100dp ......... passes (both)
 *   top padding 40 -> 120dp ................ fails  LANDSCAPE ONLY -- portrait passes
 *   offset(y = -60.dp) ..................... PASSES BOTH -- see below
 *
 * The blind spot is changeThreshold = 0.05 below, made worse by the ¶ being a 0.06-alpha element: a
 * pixel comparator barely registers it moving. So a MODEST negative offset -- the exact defect class
 * these guard -- slips through, even though the -206dp version that shipped does fail. Treat these as
 * detectors of gross regression only. Recorded as M-56 in docs/MAINTENANCE.md; do not "fix" it by
 * lowering changeThreshold without first deciding the per-OS golden question recorded there.
 *
 * THESE ARE PIXEL-CHANGE DETECTORS, NOT GEOMETRIC MODELS. Robolectric falls back to a system serif
 * whose ¶ is taller than Source Serif 4's, so ink extents measured in a golden do NOT predict the
 * device. NEVER read device geometry off a golden -- the physical S24+ is the only arbiter, and
 * `adb shell wm size` / `wm density` are how you ask it. A whole session's arithmetic was correct on
 * the wrong screen because nobody did that.
 *
 * The landscape pair does now add detection power the portrait pair lacks (the 120dp padding row
 * above) -- it did not when it modelled a fictional 411dp-tall viewport. That comparison covers only
 * the VERTICAL perturbations measured; horizontal reflow was not tested either way. Its other value
 * is unchanged and may still be the larger one: a golden cannot catch the change that re-records it,
 * and re-recording is exactly what happened when the oversized watermark went in -- the goldens were
 * rewritten to match the defect and the gate went green. What catches that is a human looking at a
 * landscape PNG in the diff. Now there is one to look at.
 *
 *   Record baseline:  ./gradlew recordRoborazziDebug
 *   Verify (gate):    ./gradlew verifyRoborazziDebug
 *
 * The theme comes from the provided color scheme (LocalMDColors), not system night mode, so the
 * light and dark goldens of an orientation share that orientation's pinned config. Custom OFL fonts
 * may fall back to a system serif under Robolectric — the golden is still deterministic and catches
 * layout regressions.
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
    fun golden() = captureWelcome(suffix = themeSuffix())

    /**
     * Same composition under the short landscape viewport. Method-level [Config] overrides only the
     * size qualifiers; everything else is inherited from the class.
     */
    @Test
    @Config(qualifiers = LANDSCAPE_S24_PLUS)
    fun goldenLandscape() = captureWelcome(suffix = themeSuffix() + "_landscape")

    private fun themeSuffix(): String = when (themeMode) {
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
    }

    private fun captureWelcome(suffix: String) {
        val colorScheme = when (themeMode) {
            ThemeMode.DARK -> DarkColorScheme
            ThemeMode.LIGHT -> LightColorScheme
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

        /**
         * The S24+'s ACTUAL landscape viewport, not a reference device's. `adb shell wm size` reports
         * 1080x2340 and `adb shell wm density` reports 450, so the screen is 832x384dp — the class
         * qualifier's 411x891dp belongs to no phone we own. This case exists to guard a
         * VIEWPORT-HEIGHT clip, so the height has to be a real one or it guards nothing. 450dpi is not
         * a primary bucket but is a valid Android density qualifier, so it is written literally rather
         * than rounded to xxhdpi — rounding would change the dp size and defeat the point.
         */
        private const val LANDSCAPE_S24_PLUS = "w832dp-h384dp-night-450dpi"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    }
}
