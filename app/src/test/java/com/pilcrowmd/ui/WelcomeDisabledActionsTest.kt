// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * **M-117** — while a restore is loading, the welcome-screen actions it disables must LOOK
 * disabled. `clickable(enabled = false)` alone changed nothing on screen, so a disabled action was
 * indistinguishable from an enabled one.
 *
 * **Why pixels and not the goldens.** The welcome goldens compare at `changeThreshold = 0.05`, so
 * a change to under 5% of the screen passes them. Dimming three buttons and a few text rows can
 * come in under that, so a golden could stay green with the dimming deleted. This test measures
 * each action's own pixels instead: its mean distance from the background, idle against loading.
 * At the token's 0.5 opacity that distance halves, and nothing else on the screen can change it,
 * because the only thing `isLoading` does to these regions is the dimming.
 *
 * **The remove button is the control.** It still works during a load, so it must NOT dim — a
 * dimmed control that still responds is the same lie the other way round. It is also what shows
 * the measurement can tell "dimmed" from "unchanged".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class WelcomeDisabledActionsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scheme = DarkColorScheme
    private var loading by mutableStateOf(false)

    /**
     * A FULL list (the storage cap of eight), as on the phone where the defect was seen. It matters:
     * with recents present the brand block moves up (M-67), and only then does the ¶ watermark reach
     * down behind the Open button. With two entries it ends above the wordmark, and the opacity test
     * below would have nothing to hide — its precondition fails, as measured.
     */
    private val recents = listOf("notes.md", "spec.md", "README.md", "journal.md", "todo.md", "a.md", "b.md", "c.md")
        .mapIndexed { i, name -> RecentFileUi(Uri.parse("content://uat/$name"), name, 5_000L - i, available = true) }

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(scheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides scheme) {
                    WelcomeScreen(recentFiles = recents, isLoading = loading)
                }
            }
        }
    }

    /** Mean per-channel distance from the background over the node's bounds, in 0..255. */
    private fun contrastOf(node: SemanticsNodeInteraction): Double {
        composeRule.waitForIdle()
        val bounds: Rect = node.fetchSemanticsNode().boundsInRoot
        val image: Bitmap = drawContent()
        val bg = scheme.primaryBackground.toArgb()
        var sum = 0.0
        var count = 0
        for (y in bounds.top.toInt() until bounds.bottom.toInt().coerceAtMost(image.height)) {
            for (x in bounds.left.toInt() until bounds.right.toInt().coerceAtMost(image.width)) {
                val p = image.getPixel(x, y)
                sum += channelDistance(p, bg)
                count++
            }
        }
        assertTrue("precondition: the node must be on screen", count > 0)
        return sum / count
    }

    /**
     * The content view drawn in software, which is what Roborazzi's goldens capture too. Compose's
     * own `captureToImage` times out under Robolectric. Semantics bounds are in the root view's
     * coordinates, and the Compose root sits at the content view's origin.
     */
    private fun drawContent(): Bitmap {
        val content = composeRule.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        return bitmap
    }

    private fun channelDistance(a: Int, b: Int): Double = (
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))
        ) / 3.0

    /** Contrast of one action idle, then loading, on the same composition. */
    private fun idleThenLoading(find: () -> SemanticsNodeInteraction): Pair<Double, Double> {
        loading = false
        val idle = contrastOf(find())
        loading = true
        val busy = contrastOf(find())
        assertTrue("precondition: the action must be visible against the background", idle > MIN_VISIBLE)
        return idle to busy
    }

    private fun assertDimmed(what: String, find: () -> SemanticsNodeInteraction) {
        show()
        val (idle, busy) = idleThenLoading(find)
        assertTrue(
            "$what must dim while loading: contrast $idle idle vs $busy loading",
            busy < idle * DIMMED_AT_MOST,
        )
    }

    @Test
    fun openMdFileDimsWhileLoading() = assertDimmed("Open MD File") { composeRule.onNodeWithText("Open MD File") }

    @Test
    fun createMdFileDimsWhileLoading() = assertDimmed("Create MD File") { composeRule.onNodeWithText("Create MD File") }

    @Test
    fun browseAllFilesDimsWhileLoading() =
        assertDimmed("Browse all files") { composeRule.onNodeWithText("Can't see your file? Browse all files") }

    @Test
    fun aRecentFileDimsWhileLoading() =
        assertDimmed("a recent file") { composeRule.onNodeWithText("notes.md", useUnmergedTree = true) }

    @Test
    fun theRemoveButtonStaysUndimmedBecauseItStillWorks() {
        show()
        val (idle, busy) = idleThenLoading {
            composeRule.onAllNodesWithContentDescription("Remove from recents")[0]
        }
        assertTrue(
            "the remove button still works during a load, so it must not dim: $idle idle vs $busy loading",
            abs(busy - idle) < idle * UNCHANGED_WITHIN,
        )
    }

    /**
     * The disabled Open button must be OPAQUE. It is the one dimmed action with a fill, it sits over
     * the ¶ watermark, and with a translucent fill the watermark's stem showed through it as a dark
     * band (seen on a phone test, 2026-09-24). A scan line just inside the button's top edge crosses
     * the watermark: if the fill is opaque, every pixel on it is the fill colour.
     *
     * The same line just ABOVE the button must NOT be uniform. That proves the watermark really is
     * behind the button at this width, so the uniformity inside cannot pass for want of anything to
     * hide. The contrast test above still pins that the button is dimmed at all.
     */
    @Test
    fun theDisabledOpenButtonHidesTheWatermarkBehindIt() {
        show()
        loading = true
        composeRule.waitForIdle()
        val button = composeRule.onNodeWithText("Open MD File").fetchSemanticsNode().boundsInRoot
        val image = drawContent()
        val px = composeRule.density.density
        val left = (button.left + CORNER_CLEARANCE_DP * px).toInt()
        val right = (button.right - CORNER_CLEARANCE_DP * px).toInt()
        val above = spreadAlong(image, (button.top - LINE_OFFSET_DP * px).toInt(), left, right)
        val inside = spreadAlong(image, (button.top + LINE_OFFSET_DP * px).toInt(), left, right)

        assertTrue(
            "precondition: the watermark must cross the line just above the button ($above)",
            above > MIN_WATERMARK_SPREAD,
        )
        assertTrue("the disabled button must hide the watermark: fill spread $inside", inside <= MAX_FILL_SPREAD)
    }

    /** The largest per-channel distance between any pixel on row [y] (from [x0] to [x1]) and the first. */
    private fun spreadAlong(image: Bitmap, y: Int, x0: Int, x1: Int): Double {
        val first = image.getPixel(x0, y)
        return (x0 until x1).maxOf { x -> channelDistance(image.getPixel(x, y), first) }
    }

    private companion object {
        /** An action at 0.5 opacity keeps half its contrast; 0.7 leaves room for anti-aliasing. */
        const val DIMMED_AT_MOST = 0.7

        /** "Unchanged" still allows for anti-aliasing noise between two captures. */
        const val UNCHANGED_WITHIN = 0.1

        /** Guards against measuring an empty or background-only region. */
        const val MIN_VISIBLE = 2.0

        /** Clear of the 16 dp rounded corners at the scan lines' depth. */
        const val CORNER_CLEARANCE_DP = 20f

        /** Scan lines sit this far above and below the button's top edge. */
        const val LINE_OFFSET_DP = 6f

        /** The watermark is faint (0.06 alpha), so a few levels of spread already means it is there. */
        const val MIN_WATERMARK_SPREAD = 3.0

        /** An opaque flat fill is uniform; this only absorbs rounding. */
        const val MAX_FILL_SPREAD = 1.0
    }
}
