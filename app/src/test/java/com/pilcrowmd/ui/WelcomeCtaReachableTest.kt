// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The welcome screen's primary action must be ON SCREEN at every viewport, not merely present in a
 * scroll. It was not: in landscape the cream "Open MD File" button sat entirely below the fold, so a
 * first-time user holding the phone sideways saw a wordmark, a tagline and no way to proceed.
 *
 * This asserts GEOMETRY rather than leaving the guarantee to a screenshot golden. The goldens for
 * this screen are pixel-change detectors with a 5% tolerance over a watermark drawn at 0.06 alpha,
 * and they demonstrably let real placement drift through; a bounds assertion cannot be fooled that
 * way.
 *
 * IT IS DELIBERATELY NOT PINNED TO ONE SCREEN, and that is not caution — a single-qualifier version
 * of this test was written first and PASSED against the very regression it was meant to catch.
 * Robolectric applies no system-bar insets, so at the device's own landscape qualifier the old fixed
 * gap put the button's bottom edge inside the viewport by a few dp and the test saw nothing wrong,
 * while on glass the insets take enough height that the button is gone. Rather than invent an inset
 * figure — the same tuned-constant mistake in a new place — the invariant is checked across a range
 * of heights, because the claim being defended is "every viewport", not "this phone". Each case was
 * watched failing against the previous fixed-height gap before being trusted.
 *
 * Portrait is the control: it has room to spare, so the gap must NOT yield and the button must sit
 * where it was composed. Where device geometry matters, ask the device: `adb shell wm size` and
 * `adb shell wm density`. Never read it off a golden.
 */
@RunWith(RobolectricTestRunner::class)
class WelcomeCtaReachableTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w832dp-h384dp-night-450dpi")
    fun ctaIsOnScreenAtTheDevicesLandscapeViewport() = assertCtaWithinViewport()

    @Test
    @Config(qualifiers = "w832dp-h360dp-night-450dpi")
    fun ctaIsOnScreenOnAShorterLandscapeViewport() = assertCtaWithinViewport()

    @Test
    @Config(qualifiers = "w832dp-h320dp-night-450dpi")
    fun ctaIsOnScreenOnAVeryShortViewport() = assertCtaWithinViewport()

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    fun ctaIsOnScreenInPortraitAndTheGapDoesNotYield() = assertCtaWithinViewport()

    private fun assertCtaWithinViewport() {
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DarkColorScheme.primaryBackground),
            ) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    WelcomeScreen(recentFiles = emptyList())
                }
            }
        }
        composeRule.waitForIdle()

        val viewportBottom = composeRule.onRoot().fetchSemanticsNode().size.height
        val cta = composeRule.onNodeWithText("Open MD File").fetchSemanticsNode()
        // positionInRoot, NOT boundsInRoot. boundsInRoot is CLIPPED by the scroll container, so a
        // button pushed below the fold reports its bottom edge AT the viewport edge and any
        // "bottom <= viewport" check passes by construction. Two earlier versions of this test did
        // exactly that and passed against the regression they existed to catch.
        val ctaBottom = cta.positionInRoot.y + cta.size.height
        // The fallback link sits directly below the button. Asserting on IT, not on the button, is
        // what keeps the button off the bottom edge: reserving only to the button parked it flush
        // against the edge with nothing beneath, which a device pass rejected.
        val fallback = composeRule.onNodeWithText("Can't see your file? Browse all files")
            .fetchSemanticsNode()
        val fallbackBottom = fallback.positionInRoot.y + fallback.size.height

        assertTrue(
            "The \"Open MD File\" button must be fully on screen: its bottom is ${ctaBottom.toInt()}px " +
                "against a viewport of ${viewportBottom}px.",
            ctaBottom <= viewportBottom.toFloat(),
        )
        assertTrue(
            "The button must not sit flush against the bottom edge: the fallback link below it ends " +
                "at ${fallbackBottom.toInt()}px against a viewport of ${viewportBottom}px.",
            fallbackBottom <= viewportBottom.toFloat(),
        )
    }
}
