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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.pilcrowmd.ui.components.WELCOME_WATERMARK_TEST_TAG
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ¶ brand mark's placement is a RULE, and this is where the rule is checked: **the top edge of
 * the ¶'s ink sits on the bottom edge of the settings gear**, in both orientations.
 *
 * A GOLDEN CANNOT DEFEND THIS, which is why the assertion is geometric. The welcome goldens compare
 * at a 0.05 threshold over a mark drawn at 0.06 alpha, and that tolerance measurably passes both a
 * 40dp shift of this mark and an 11% change in its size. Reading the mark off a screenshot instead
 * does not work either: the wordmark overlays it and its antialiased edges fall inside the mark's
 * own luminance band, so no threshold separates them. The layout knows exactly where the ink is;
 * this asks the layout.
 *
 * Both edges are read UNCLIPPED, as `positionInRoot + size` rather than `boundsInRoot` — the lesson
 * from the CTA-reachability test next door, where a clipped bound made the assertion true by
 * construction. Here the mark deliberately overruns the bottom of the screen, so a clipped height
 * would be the viewport's, not the glyph's.
 *
 * The gear is fetched from the UNMERGED tree on purpose. `IconButton` merges its descendants, so
 * the merged node is the 48dp touch target; the rule names the 22dp GLYPH, whose bottom edge is 13dp
 * higher. Fetching the merged node would move the anchor and the test would still pass.
 *
 * NATIVE graphics mode is not decoration. Under Robolectric's default LEGACY shadows text is not
 * measured at all: the mark reports 1 x 0 px, and BOTH assertions then pass on a node with no size —
 * a test that cannot fail. It was written that way first and caught here only because the numbers
 * were printed and read.
 *
 * Watched failing, with real numbers, against two planted regressions:
 *   a `Text` anchored the naive way ... 511 x 1080px (the LINE BOX) -> the ink assert fires
 *   the previous `padding(top = 40.dp)` .. ink top 113px vs a gear ending at 110px -> the anchor
 *                                          assert fires
 * The second is a 3px miss because 40dp and the gear's edge happen to be a dp apart — the old
 * defect was never the padding NUMBER, it was that the number anchored the font's leading and left
 * the ink 106dp further down. That is what the first control, and the second assertion, exist for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeWatermarkAnchorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi")
    fun markIsAnchoredToTheGearInPortrait() = assertMarkAnchoredToGear(DarkColorScheme)

    @Test
    @Config(qualifiers = "w832dp-h384dp-night-450dpi")
    fun markIsAnchoredToTheGearInLandscape() = assertMarkAnchoredToGear(DarkColorScheme)

    /**
     * The rule is a layout property, so the theme must not enter into it. If a colour ever did
     * change the placement, that would be the bug.
     */
    @Test
    @Config(qualifiers = "w384dp-h832dp-450dpi")
    fun markIsAnchoredToTheGearInLightTheme() = assertMarkAnchoredToGear(LightColorScheme)

    private fun assertMarkAnchoredToGear(colors: PilcrowColorScheme) {
        composeRule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.primaryBackground),
            ) {
                CompositionLocalProvider(LocalMDColors provides colors) {
                    WelcomeScreen(recentFiles = emptyList())
                }
            }
        }
        composeRule.waitForIdle()

        val gear = composeRule
            .onNodeWithContentDescription("Settings", useUnmergedTree = true)
            .fetchSemanticsNode()
        val mark = composeRule
            .onNodeWithTag(WELCOME_WATERMARK_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()

        val gearBottom = gear.positionInRoot.y + gear.size.height
        val markInkTop = mark.positionInRoot.y

        assertEquals(
            "The ¶'s ink must start at the gear's bottom edge: gear ends at ${gearBottom.toInt()}px, " +
                "the mark starts at ${markInkTop.toInt()}px.",
            gearBottom.toDouble(),
            markInkTop.toDouble(),
            1.0,
        )
        // The node must be INK-sized, not line-box-sized. Swap the mark back to a `Text` and it
        // still anchors its top edge here — to the top of the font's leading, with the ink a further
        // ~106dp down the screen and the rule quietly broken. The glyph's ink is ~40% shorter than
        // the font's natural line box, so the two cannot be confused by this margin.
        val lineBoxHeight = mark.size.width * NATURAL_LINE_HEIGHT_OVER_ADVANCE
        assertTrue(
            "The mark's box must be its INK (${mark.size.height}px), not the font's line box " +
                "(~${lineBoxHeight.toInt()}px) — otherwise the anchor is on empty leading.",
            mark.size.height < lineBoxHeight * 0.85f,
        )
    }

    private companion object {
        /**
         * Source Serif 4 Bold, from the font's own tables: the natural line height (ascent 1036 +
         * descent 335 per em) over the ¶'s advance width (649 per em) = 2.11. Expressed as a ratio
         * of a measurement taken at runtime so the check holds at any density or size.
         */
        const val NATURAL_LINE_HEIGHT_OVER_ADVANCE = (1036f + 335f) / 649f
    }
}
