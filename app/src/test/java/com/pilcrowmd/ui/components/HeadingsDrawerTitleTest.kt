// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.PilcrowSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S8-A85: the Contents title never breaks mid-word, in German ("Inhaltsverzeichnis") at the system
 * font scales, and the Collapse/Expand all button drops to its own line only when both cannot share
 * one. The drawer is a fixed 280 dp, so the width is not a variable; the language and the font scale
 * are the only things that decide whether title and button fit side by side.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real font metrics; the legacy mode measures text as a few pixels
@Config(qualifiers = "de-w411dp-h891dp-night-xxhdpi")
class HeadingsDrawerTitleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Foldable, so the title button shows.
    private val outline = listOf(
        HeadingNode(1, "Eins", 0),
        HeadingNode(2, "Zwei", 1),
    )

    private fun show(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalMDColors provides DarkColorScheme,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                HeadingsDrawer(headings = outline)
            }
        }
        composeRule.waitForIdle()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = composeRule.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("no text layout for $text", action?.invoke(results) == true)
        return results.single()
    }

    private fun boundsOf(text: String): Rect = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** One line, the whole word on it, nothing cut off or spilling past the edge. */
    private fun assertTitleWhole(title: String) {
        val layout = layoutOf(title)
        assertEquals("title lines", 1, layout.lineCount)
        assertEquals("title fully on its line", title.length, layout.getLineEnd(0, visibleEnd = true))
        // Not didOverflowWidth: on a single unwrapped line it also reads true when the text is
        // narrower than the paragraph, so it cannot tell cut from whole. The drawn width can: it is
        // clamped to the room, so it falls short of the text's own width when the text is cut.
        assertTrue(
            "title is cut: ${layout.size.width} px drawn of ${layout.multiParagraph.maxIntrinsicWidth}",
            layout.size.width >= layout.multiParagraph.maxIntrinsicWidth,
        )
        assertFalse("title overflows height", layout.didOverflowHeight)
        assertFalse("title is ellipsized", layout.isLineEllipsized(0))
        val box = boundsOf(title)
        val drawerEnd = with(composeRule.density) { (280.dp - PilcrowSpacing.sm).toPx() }
        assertTrue("title starts inside the drawer", box.left >= 0f)
        assertTrue("title ends inside the drawer (${box.right} of $drawerEnd)", box.right <= drawerEnd)
    }

    private fun assertButtonBelowTitle(title: String, button: String) {
        assertTrue("button should be on its own line", boundsOf(button).top >= boundsOf(title).bottom)
    }

    private fun assertSharesLine(title: String, button: String) {
        assertTrue("button should share the title's line", boundsOf(button).top < boundsOf(title).bottom)
    }

    @Test
    fun germanTitleStaysWholeAtScale1() {
        show(1.0f)
        assertTitleWhole("Inhaltsverzeichnis")
        // The test font (Roboto) is narrower than a phone's, so here the two may still fit on one
        // line; the phone breaks the word at this scale. Either way they must not overlap.
        val title = boundsOf("Inhaltsverzeichnis")
        val button = boundsOf("Alle einklappen")
        assertTrue("button overlaps title", button.top >= title.bottom || button.left >= title.right)
    }

    @Test
    fun germanTitleStaysWholeAtScale1Point3() {
        show(1.3f)
        assertTitleWhole("Inhaltsverzeichnis")
        assertButtonBelowTitle("Inhaltsverzeichnis", "Alle einklappen")
    }

    @Test
    fun germanTitleStaysWholeAtLargestScale() {
        show(2.0f)
        assertTitleWhole("Inhaltsverzeichnis")
        assertButtonBelowTitle("Inhaltsverzeichnis", "Alle einklappen")
    }

    /**
     * Past the largest system scale (2.0). Android scales large text less than linearly up to 2.0,
     * so the German word still fits at 2.0 in the test font; 3.0 is linear and wider than the row at
     * 18 sp, so only the fit-to-width shrink can keep the word whole here.
     */
    @Test
    fun germanTitleShrinksToFitPastTheLargestScale() {
        show(3.0f)
        assertTitleWhole("Inhaltsverzeichnis")
        assertButtonBelowTitle("Inhaltsverzeichnis", "Alle einklappen")
    }

    @Test
    @Config(qualifiers = "es-w411dp-h891dp-night-xxhdpi")
    fun shortTitleSharesALineWithTheButton() {
        show(1.0f)
        assertTitleWhole("Índice")
        assertSharesLine("Índice", "Contraer todo")
    }
}
