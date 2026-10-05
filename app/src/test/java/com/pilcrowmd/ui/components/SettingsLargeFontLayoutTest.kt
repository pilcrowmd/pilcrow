// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.LocalMDColors
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
 * At the system's largest font scale (2.0) the Settings screen must grow its containers rather
 * than break its text: the size readout beside each text-size slider stays on ONE line (it was
 * boxed at a fixed 40 dp and wrapped "100%" into "10" / "0%"), and every reading-font pill shows
 * its whole label (the pill was a fixed 34 dp tall and cut the label off at the bottom).
 *
 * Both checks read the Text's own [TextLayoutResult], so they measure what the user sees.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi", fontScale = 2.0f)
class SettingsLargeFontLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    SettingsScreen(appVersion = "1.0.0")
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertEquals("node $id exposes exactly one text layout", 1, results.size)
        return results.single()
    }

    @Test
    fun sizeReadoutsStayOnOneLine() {
        show()
        val readouts = composeRule.onAllNodesWithText(READOUT, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one readout per text-size slider (preview + edit)", 2, readouts.size)
        readouts.forEach { node ->
            val layout = node.textLayout()
            assertEquals("\"$READOUT\" readout wraps at font scale 2.0", 1, layout.lineCount)
            // `maxLines = 1` in a too-narrow box would pass the count by dropping "0%": the one
            // line must hold the whole readout.
            assertEquals(
                "\"$READOUT\" readout loses characters at font scale 2.0",
                READOUT.length,
                layout.getLineEnd(0, visibleEnd = true),
            )
            // Not `hasVisualOverflow`: an End-aligned wrap-content Text lays its paragraph out at
            // the full incoming width, so that flag is set even when every glyph shows. The line's
            // own extent against the node's width is what the user sees.
            val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            assertTrue(
                "\"$READOUT\" readout is clipped at font scale 2.0 (line ${lineWidth}px, box ${layout.size.width}px)",
                lineWidth <= layout.size.width,
            )
        }
    }

    @Test
    fun fontPillLabelsAreNotClipped() {
        show()
        FontSets.ALL.forEach { set ->
            val node = composeRule.onNodeWithText(set.displayName, useUnmergedTree = true).fetchSemanticsNode()
            val layout = node.textLayout()
            assertFalse(
                "\"${set.displayName}\" pill cuts its label off at font scale 2.0 " +
                    "(text ${layout.multiParagraph.height}px, box ${layout.size.height}px)",
                layout.didOverflowHeight,
            )
            assertEquals("\"${set.displayName}\" pill wraps its label", 1, layout.lineCount)
        }
    }

    private companion object {
        const val READOUT = "100%"
    }
}
