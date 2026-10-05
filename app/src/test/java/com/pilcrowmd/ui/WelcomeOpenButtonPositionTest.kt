// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-196: the welcome block (wordmark, Open, Create, "Browse all files") has one position for every
 * number of recent files, 0 included, so a tap aimed at Open does not land on something else as the
 * list grows. That position is where the block sat with six recents before M-196.
 *
 * The one-position checks alone would pass for any reserve, so [theBlockSitsWhereSixRecentsPutItBeforeM196]
 * pins WHICH position, with no recent files at all, which is also the first-run screen.
 */
@RunWith(RobolectricTestRunner::class)
// NATIVE: real text metrics, the mode the goldens and the 307.6 dp measurement use.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeOpenButtonPositionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val recents = mutableStateOf(recents(0))

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    fun oneOpenButtonPositionForAnyNumberOfRecents() = assertOnePositionForZeroToEightRecents()

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi", fontScale = 2.0f)
    fun oneOpenButtonPositionForAnyNumberOfRecentsAtLargeText() = assertOnePositionForZeroToEightRecents()

    @Test
    @Config(qualifiers = "w384dp-h640dp-night-450dpi")
    fun oneOpenButtonPositionForAnyNumberOfRecentsOnAShortScreen() = assertOnePositionForZeroToEightRecents()

    /**
     * 307.6 dp is where the Open button's top sat with six recents before M-196, measured at this
     * size and font scale (2026-09-30). With five rows reserved it would be 358.8, with seven 256.4.
     */
    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    fun theBlockSitsWhereSixRecentsPutItBeforeM196() {
        show()
        val topDp = openButtonTop() / composeRule.density.density
        assertEquals("Open button top, dp, with no recent files", SIX_RECENTS_OPEN_TOP_DP, topDp, 0.5f)
    }

    /**
     * At OS font 2.0 the six-recents rule alone leaves no gap, which would start the wordmark at the
     * top of the screen, under the settings gear. The gap has a floor at the gear button's bottom
     * edge instead: the wordmark starts there for every count, and Open sits at 309.7 dp (261.7 dp
     * without the floor).
     */
    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi", fontScale = 2.0f)
    fun atLargeTextTheWordmarkStartsBelowTheGear() {
        show()
        (0..MAX_RECENTS).forEach { count ->
            recents.value = recents(count)
            composeRule.waitForIdle()
            val gearBottom = composeRule.onNodeWithContentDescription("Settings")
                .fetchSemanticsNode().boundsInRoot.bottom
            val wordmarkTop = composeRule.onNodeWithText("PilcrowMD", substring = true)
                .fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "wordmark top $wordmarkTop px must not be above the gear's bottom $gearBottom px ($count recents)",
                wordmarkTop >= gearBottom - HALF_DP_TOLERANCE_PX,
            )
        }
        val topDp = openButtonTop() / composeRule.density.density
        assertEquals("Open button top, dp, at OS font 2.0", LARGE_TEXT_OPEN_TOP_DP, topDp, 0.5f)
    }

    private fun assertOnePositionForZeroToEightRecents() {
        show()
        val tops = (0..MAX_RECENTS).map { count ->
            recents.value = recents(count)
            composeRule.waitForIdle()
            count to openButtonTop()
        }
        val first = tops.first().second
        tops.forEach { (count, top) ->
            assertEquals("Open button top with $count recent files, against none", first, top, 0.5f)
        }
    }

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    WelcomeScreen(recentFiles = recents.value)
                }
            }
        }
        composeRule.waitForIdle()
    }

    // positionInRoot, not boundsInRoot: the scroll container clips bounds (see WelcomeCtaReachableTest).
    private fun openButtonTop(): Float =
        composeRule.onNodeWithText("Open MD File").fetchSemanticsNode().positionInRoot.y

    private fun recents(count: Int): List<RecentFileUi> = (1..count).map {
        RecentFileUi(Uri.parse("content://uat/file$it.md"), "file$it.md", 1_000L * it, available = true)
    }

    private companion object {
        const val SIX_RECENTS_OPEN_TOP_DP = 307.6f
        const val LARGE_TEXT_OPEN_TOP_DP = 309.7f

        // Half a dp at 450 dpi (2.8125 px per dp): rounding of the gap, not a real overlap.
        const val HALF_DP_TOLERANCE_PX = 1.5f

        // The storage cap (`LocalStorageManager.maxRecents`), the most the list can ever hold.
        const val MAX_RECENTS = 8
    }
}
