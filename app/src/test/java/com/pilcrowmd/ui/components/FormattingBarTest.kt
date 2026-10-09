// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.pilcrowmd.domain.markdown.FormatAction
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-217: every formatting-bar button is reachable by its TalkBack label, sends its own action, and
 * More opens the second row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class FormattingBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<FormatAction>()
    private var undos = 0
    private var redos = 0

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                FormattingBar(onAction = { actions += it }, onUndo = { undos++ }, onRedo = { redos++ })
            }
        }
    }

    private fun button(label: String) = composeRule.onNodeWithContentDescription(label)

    private fun moreState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun theFirstRowHasItsButtonsAndTheSecondRowIsClosed() {
        show()
        (MAIN.keys + listOf(UNDO, REDO, MORE)).forEach { label ->
            button(label).assertExists("\"$label\" is on the first row")
            button(label).assertHeightIsAtLeast(42.dp).assertWidthIsAtLeast(42.dp)
        }
        MORE_ROW.keys.forEach { button(it).assertDoesNotExist() }
        button(MORE).assert(moreState("Collapsed"))
    }

    @Test
    fun moreOpensAndClosesTheSecondRow() {
        show()
        button(MORE).performClick()
        MORE_ROW.keys.forEach { button(it).assertExists("\"$it\" is on the second row") }
        button(MORE).assert(moreState("Expanded"))

        button(MORE).performClick()
        MORE_ROW.keys.forEach { button(it).assertDoesNotExist() }
    }

    @Test
    fun boldSendsBold() {
        show()
        button("Bold").performClick()
        assertEquals(listOf<FormatAction>(FormatAction.Bold), actions)
    }

    @Test
    fun everyFormattingButtonSendsItsOwnAction() {
        show()
        button(MORE).performClick()
        (MAIN + MORE_ROW).forEach { (label, action) ->
            actions.clear()
            button(label).performClick()
            assertEquals("\"$label\"", listOf(action), actions)
        }
    }

    @Test
    fun undoAndRedoCallTheirCallbacks() {
        show()
        button(UNDO).performClick()
        button(REDO).performClick()
        button(REDO).performClick()
        assertEquals(1, undos)
        assertEquals(2, redos)
        assertEquals("undo and redo are not formatting actions", emptyList<FormatAction>(), actions)
    }

    private companion object {
        const val UNDO = "Undo"
        const val REDO = "Redo"
        const val MORE = "More formatting"

        val MAIN = mapOf(
            "Bold" to FormatAction.Bold,
            "Italic" to FormatAction.Italic,
            "Heading" to FormatAction.Heading,
            "Bulleted list" to FormatAction.BulletList,
            "Checkbox" to FormatAction.Checkbox,
            "Link" to FormatAction.Link,
        )
        val MORE_ROW = mapOf(
            "Inline code" to FormatAction.InlineCode,
            "Quote" to FormatAction.Quote,
            "Table" to FormatAction.Table,
            "Strikethrough" to FormatAction.Strikethrough,
            "Divider" to FormatAction.Divider,
            "Outdent" to FormatAction.Outdent,
            "Indent" to FormatAction.Indent,
        )
    }
}
