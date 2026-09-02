// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
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
import org.robolectric.annotation.GraphicsMode

/**
 * The "PilcrowMD" lockup must never break mid-word, and must not sit at the edge of the width it
 * has. This is the release's headline element, so "it happens to fit" is not good enough.
 *
 * WHY A HEADROOM ASSERT AND NOT "does it fit". Measured on the S24+'s real portrait viewport
 * (384dp at 450dpi -> 1080px, less 32dp padding a side -> 900px available), the lockup at a fixed
 * 58dp measured **895px**. That is a **5px, 0.55% margin**, and it is the whole defect: anything
 * that widens the run by more than half a percent overflows it. A test asserting only `fits`
 * passes on 895/900 and would have shipped exactly the build that failed on a real phone.
 *
 * WHAT ROBOLECTRIC CANNOT SEE, stated so this test is not read as more than it is. On a physical
 * S24+ at the LARGEST OS font size, in portrait, the final "D" left the line. That is not
 * reproducible here: `58.dp.toSp()` round-trips exactly through the API-34+ non-linear
 * converter in this environment (`DensityWithConverter`, 58dp -> 52.58sp -> 163.125px == 58dp at
 * fontScale 2.0), so the measured width is **895px at every font scale from 1.0 to 2.0**. A test
 * keyed on font scale would therefore be TAUTOLOGICAL here — green whatever the code does. So this
 * pins the invariant that is real and measurable instead: the run must leave slack, at the widths
 * the app actually meets. The device remains the authority on the symptom; this is the guard.
 *
 * The 320dp case is not hypothetical either. Before the fix the node measured exactly the available
 * width at every viewport at or below 380dp — the signature of `maxLines = 1` clipping, i.e. the
 * "D" already lost on any phone narrower than the S24+.
 *
 * Watched failing before the fix, with real numbers:
 *   w384dp portrait  895px used of 900px available (99.4%) -> needs <= 828px
 *   w320dp portrait  720px used of 720px available (100.0%, clipped) -> needs <= 662px
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeWordmarkFitTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi", fontScale = 1.0f)
    fun lockupHasHeadroomOnTheS24PlusInPortrait() = assertLockupHasHeadroom()

    /** The largest OS font setting. Constant here by design; see the class note. */
    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi", fontScale = 2.0f)
    fun lockupHasHeadroomAtTheLargestFontScale() = assertLockupHasHeadroom()

    /** A 320dp phone — the narrowest the app supports. Clipped outright before the fix. */
    @Test
    @Config(qualifiers = "w320dp-h800dp-night-450dpi", fontScale = 1.0f)
    fun lockupHasHeadroomOnANarrowPhone() = assertLockupHasHeadroom()

    @Test
    @Config(qualifiers = "w320dp-h800dp-night-450dpi", fontScale = 2.0f)
    fun lockupHasHeadroomOnANarrowPhoneAtTheLargestFontScale() = assertLockupHasHeadroom()

    private fun assertLockupHasHeadroom() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    WelcomeScreen(recentFiles = emptyList())
                }
            }
        }
        composeRule.waitForIdle()

        val node = composeRule
            .onNodeWithText(WORDMARK, useUnmergedTree = true)
            .fetchSemanticsNode()
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().size.width
        // The Column the wordmark sits in is padded 32dp a side; at 450dpi that is 90px each.
        val available = rootWidth - 2 * HORIZONTAL_PADDING_PX
        val budget = available * WIDTH_BUDGET

        assertTrue(
            "The \"$WORDMARK\" lockup must leave slack in its line, not sit on the edge of it: " +
                "it measured ${node.size.width}px of ${available}px available " +
                "(${"%.1f".format(100f * node.size.width / available)}%), and the budget is " +
                "${budget.toInt()}px (${(100 * WIDTH_BUDGET).toInt()}%). A run this close to the " +
                "edge is what put the final \"D\" off the line on a physical S24+.",
            node.size.width <= budget,
        )
        // A single line, always. If the fit ever fails, it must not fail by breaking the word.
        assertTrue(
            "The lockup must stay on ONE line: it measured ${node.size.height}px tall, and a " +
                "single line at this size is ~${SINGLE_LINE_MAX_PX}px.",
            node.size.height <= SINGLE_LINE_MAX_PX,
        )
    }

    private companion object {
        const val WORDMARK = "PilcrowMD"

        /**
         * 32dp of `padding(horizontal = ...)` at 450dpi (density 2.8125) = 90px a side.
         */
        const val HORIZONTAL_PADDING_PX = 90

        /**
         * The run may occupy at most 92% of its line. Chosen against the measured failure: the
         * rejected build sat at 99.4%, so any budget below ~99% catches it, and 92% leaves roughly
         * fifteen times the 0.55% margin that proved insufficient on a real phone, without shrinking the
         * mark enough to change its character.
         */
        const val WIDTH_BUDGET = 0.92f

        /**
         * One line of Source Serif 4 Bold at 58dp measures 224px at 450dpi. A second line would
         * roughly double it, so this separates one line from two with a wide margin and does not
         * need adjusting if the fitted size moves.
         */
        const val SINGLE_LINE_MAX_PX = 300
    }
}
