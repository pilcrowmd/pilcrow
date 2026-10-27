// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

/**
 * S8-A38: the N and the U of the New and Updated marks sit in the middle of their 16 dp circle. The
 * U looked off-centre. Measured on the drawn pixels: the letter's ink box against the circle's
 * box, in Dark and Light, at font scale 1.0 and 2.0, for both letters.
 *
 * The ink is looked for only inside the circle, clear of the ring and its antialiasing, and against
 * the colour the circle has there (the fill for New, the background for Updated), so nothing but the
 * letter can be found. A letter that is not found at all fails the test rather than passing it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkBadgeCentringTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var scheme by mutableStateOf<PilcrowColorScheme>(DarkColorScheme)
    private var fontScale by mutableStateOf(1f)
    private var mark by mutableStateOf(Mark.NEW)

    private class Offset(val label: String, val dx: Double, val dy: Double)

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(
                com.pilcrowmd.ui.theme.LocalMDColors provides scheme,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                Box(Modifier.size(CANVAS_DP.dp).background(scheme.primaryBackground), Alignment.Center) {
                    MarkBadge(mark)
                }
            }
        }
    }

    private fun drawContent(): Bitmap {
        val content = composeRule.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        return bitmap
    }

    private fun distance(a: Int, b: Int): Int = (
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
        ) / 3

    /** Where the letter's ink box centre is from the circle's box centre, in pixels. */
    private fun measure(label: String): Offset {
        composeRule.waitForIdle()
        val image = drawContent()
        val bg = scheme.primaryBackground.toArgb()
        val canvasPx = (CANVAS_DP * composeRule.density.density).toInt()
        // The circle: everything that is not the background.
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var b = -1
        for (y in 0 until canvasPx) {
            for (x in 0 until canvasPx) {
                if (distance(image.getPixel(x, y), bg) > INK) {
                    l = minOf(l, x)
                    t = minOf(t, y)
                    r = maxOf(r, x)
                    b = maxOf(b, y)
                }
            }
        }
        assertTrue("$label: the circle was not drawn", r > l && b > t)
        val cx = (l + r + 1) / 2.0
        val cy = (t + b + 1) / 2.0
        val radius = (r - l + 1) / 2.0
        // What the circle looks like where the letter is not: the fill, or the background inside the ring.
        val base = image.getPixel((cx + radius * 0.8).toInt().coerceAtMost(r), cy.toInt())
            .takeIf { mark == Mark.NEW } ?: bg
        var il = Int.MAX_VALUE
        var it = Int.MAX_VALUE
        var ir = -1
        var ib = -1
        for (y in t..b) {
            for (x in l..r) {
                if (hypot(x + 0.5 - cx, y + 0.5 - cy) > radius - CLEAR_OF_RING_PX) continue
                if (distance(image.getPixel(x, y), base) > INK) {
                    il = minOf(il, x)
                    it = minOf(it, y)
                    ir = maxOf(ir, x)
                    ib = maxOf(ib, y)
                }
            }
        }
        assertTrue("$label: no letter ink found inside the circle", ir >= il && ib >= it)
        return Offset(label, (il + ir + 1) / 2.0 - cx, (it + ib + 1) / 2.0 - cy)
    }

    @Test
    fun theLettersAreCentredInTheirCircle() {
        show()
        val offsets = mutableListOf<Offset>()
        for ((themeName, colors) in listOf("dark" to DarkColorScheme, "light" to LightColorScheme)) {
            for (scale in listOf(1f, 2f)) {
                for (m in listOf(Mark.NEW, Mark.UPDATED)) {
                    scheme = colors
                    fontScale = scale
                    mark = m
                    offsets += measure("$themeName x$scale ${m.name}")
                }
            }
        }
        val report = offsets.joinToString("; ") { "${it.label}: dx=${it.dx} dy=${it.dy}" }
        println("MARK_CENTRING $report")
        val off = offsets.filter { abs(it.dx) > MAX_OFFSET_PX || abs(it.dy) > MAX_OFFSET_PX }
        assertTrue(
            "letter ink centre more than $MAX_OFFSET_PX px from the circle centre: " +
                off.joinToString("; ") { "${it.label}: dx=${it.dx} dy=${it.dy}" } + " (all: $report)",
            off.isEmpty(),
        )
    }

    private companion object {
        const val CANVAS_DP = 64
        const val INK = 60
        const val CLEAR_OF_RING_PX = 7.0
        const val MAX_OFFSET_PX = 1.0
    }
}
