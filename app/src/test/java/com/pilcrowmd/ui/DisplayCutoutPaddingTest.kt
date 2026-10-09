// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.components.GitHubIntegrationScreen
import com.pilcrowmd.ui.components.HeadingsDrawer
import com.pilcrowmd.ui.components.License
import com.pilcrowmd.ui.components.LicenseDetailView
import com.pilcrowmd.ui.components.LicensesScreen
import com.pilcrowmd.ui.components.SettingsScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **NEW-37b.** NEW-37 made API <= 34 edge-to-edge, which exposed that the app has no display-cutout
 * padding anywhere: in landscape on an API 34 emulator the text ran under the camera hole, a
 * 136 px band. `systemBarsPadding()` does not cover it, so every one is followed by
 * `windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))`; the
 * drawer, which opens from the start edge, uses `Start` only, so a cutout on the
 * end side adds nothing there.
 *
 * The fixture is built so the cutout padding is the ONLY thing that can move the content: the
 * injected insets carry a LEFT display-cutout of [CUTOUT_PX] and nothing else, and Robolectric
 * applies no system-bar insets of its own, so `systemBarsPadding()` has nothing to answer with.
 * The insets are dispatched straight to the Compose host view, which is where Compose's
 * `WindowInsets` listener lives; the BEFORE measurement is the control. If the injection did not
 * really reach `WindowInsets.displayCutout`, the "moves" half of each case fails, so the control
 * and the assertion cannot both pass on a dead injection.
 *
 * One case per site that carries its own `systemBarsPadding()`; each was watched failing with
 * that one added line deleted. The `MainScreen` column is covered separately
 * (`MainScreenDisplayCutoutPaddingTest`) because it needs the real activity.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayCutoutPaddingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun headingsDrawerKeepsItsTitleOutOfTheCutout() = assertMovedByCutout("Headings") { HeadingsDrawer() }

    @Test
    fun headingsDrawerIgnoresACutoutOnTheEndSide() {
        setContent { HeadingsDrawer(headings = listOf(HeadingNode(level = 1, text = "Intro", adapterPosition = 0))) }
        val titleBefore = leftEdgeOf("Headings")
        val itemRightBefore = rightEdgeOf("Intro")
        assertTrue("control: the heading row has a real width, ${itemRightBefore}px", itemRightBefore > CUTOUT_PX)

        dispatchCutout(Insets.of(0, 0, CUTOUT_PX, 0))

        assertEquals("an end-side cutout must not move the title", titleBefore, leftEdgeOf("Headings"), 0.5f)
        assertEquals(
            "an end-side cutout must not pull the content's right edge in",
            itemRightBefore,
            rightEdgeOf("Intro"),
            0.5f,
        )
    }

    @Test
    fun settingsScreenKeepsItsTitleOutOfTheCutout() = assertMovedByCutout("Settings") { SettingsScreen() }

    @Test
    fun licensesListKeepsItsTitleOutOfTheCutout() =
        assertMovedByCutout("Open Source Licenses") { LicensesScreen(onClose = {}) }

    @Test
    fun licenseDetailKeepsItsTitleOutOfTheCutout() = assertMovedByCutout("Example Library") {
        LicenseDetailView(license = License(name = "Example Library", version = "1.0", license = "MIT"), onBack = {})
    }

    @Test
    fun gitHubScreenKeepsItsTitleOutOfTheCutout() =
        assertMovedByCutout("Tell us what to build") { GitHubIntegrationScreen(onClose = {}) }

    @Test
    fun anEmptyCutoutInsetDoesNotMoveTheContent() {
        setContent { SettingsScreen() }
        val before = leftEdgeOf("Settings")
        dispatchCutout(Insets.NONE)
        assertEquals("a zero cutout inset must leave the content where it was", before, leftEdgeOf("Settings"), 0f)
    }

    private fun assertMovedByCutout(anchorText: String, content: @Composable () -> Unit) {
        setContent(content)
        val before = leftEdgeOf(anchorText)
        assertTrue("control: with no cutout the content starts inside the band, at ${before}px", before < CUTOUT_PX)

        dispatchCutout(Insets.of(CUTOUT_PX, 0, 0, 0))

        val after = leftEdgeOf(anchorText)
        assertTrue(
            "the content must start at or beyond the ${CUTOUT_PX}px cutout band, but starts at ${after}px",
            after >= CUTOUT_PX,
        )
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    // First match: a screen may repeat its title further down (the GitHub screen does); the first
    // is the header, which is the node this test cares about.
    private fun leftEdgeOf(text: String): Float =
        composeRule.onAllNodesWithText(text).onFirst().fetchSemanticsNode().positionInRoot.x

    // The heading row's text fills the row (weight 1), so its right bound follows the drawer's
    // end padding.
    private fun rightEdgeOf(text: String): Float {
        val node = composeRule.onAllNodesWithText(text).onFirst().fetchSemanticsNode()
        return node.positionInRoot.x + node.size.width
    }

    private fun dispatchCutout(cutout: Insets) {
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.displayCutout(), cutout).build()
        val host: View = composeRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        composeRule.waitForIdle()
    }

    private companion object {
        const val CUTOUT_PX = 136
    }
}
