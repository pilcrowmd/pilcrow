// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Resource-validity checks for the branded launch splash (path A — AndroidX core SplashScreen API).
 *
 * The splash adds no custom Kotlin logic (no theme branch, no tap handler, no timing), so there is
 * nothing behavioural to unit-test and fabricating timing/launch tests would be brittle. What is
 * worth guarding is that the declarative splash resources are well-formed and that the splash is
 * **fixed brand-dark in both system appearances** (no -night divergence) — a malformed AVD/vector
 * or an accidental -night override would otherwise only surface on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashResourcesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun animatedSplashIconInflates() {
        // Exercises the AVD plus its referenced vector, animators, and colour resources.
        assertNotNull(
            "splash_pilcrow_anim (AVD) should inflate",
            context.getDrawable(R.drawable.splash_pilcrow_anim),
        )
    }

    @Test
    fun staticSplashVectorInflates() {
        assertNotNull(
            "splash_pilcrow (vector) should inflate",
            context.getDrawable(R.drawable.splash_pilcrow),
        )
    }

    @Test
    fun splashThemeIsDefined() {
        assertTrue("Theme.Pilcrow.Splash style should exist", R.style.Theme_Pilcrow_Splash != 0)
    }

    @Test
    fun splashColoursAreOpaqueAndFixedBrandDarkInBothAppearances() {
        RuntimeEnvironment.setQualifiers("notnight")
        val dayBg = context.getColor(R.color.splash_background)
        val dayGlyph = context.getColor(R.color.splash_glyph)
        val dayLines = context.getColor(R.color.splash_lines)

        RuntimeEnvironment.setQualifiers("night")
        val nightBg = context.getColor(R.color.splash_background)
        val nightGlyph = context.getColor(R.color.splash_glyph)
        val nightLines = context.getColor(R.color.splash_lines)

        // A splash background/icon must be fully opaque (the window draws it before the first frame).
        for (c in listOf(dayBg, dayGlyph, dayLines, nightBg, nightGlyph, nightLines)) {
            assertEquals("splash colours must be opaque", 0xFF, Color.alpha(c))
        }

        // Fixed brand-dark: the splash is identical in both system appearances —
        // no -night divergence. Day must equal night for every splash colour.
        assertEquals("splash background must be fixed across appearances", dayBg, nightBg)
        assertEquals("splash glyph must be fixed across appearances", dayGlyph, nightGlyph)
        assertEquals("splash lines must be fixed across appearances", dayLines, nightLines)

        // …and the fixed values are the brand-dark palette (warm near-black bg + accent ¶).
        assertEquals("splash background is brand near-black", 0xFF1A1714.toInt(), nightBg)
        assertEquals("splash glyph is the Dark accent", 0xFF8E7CD6.toInt(), nightGlyph)
    }
}
