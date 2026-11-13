// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.FootnoteDefinitionBlock
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * M-76 through the real reader composable: a marker tap (the handler the reader puts on its list,
 * which the span calls) opens a sheet showing that note, rendered by Markwon, and Close dismisses it.
 * An index with no note behind it (an orphan) opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewFootnoteSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun drain() {
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun findList(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
        else -> null
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** What the reader's list does with a marker tap: exactly what `FootnoteJumpSpan.onClick` calls. */
    @Suppress("UNCHECKED_CAST")
    private fun tapMarkerFor(blockIndex: Int) {
        val list = findList(composeRule.activity.window.decorView)!!
        val handler = list.getTag(R.id.footnote_tap_handler) as (Int) -> Unit
        composeRule.runOnIdle { handler(blockIndex) }
        drain()
    }

    @Test
    fun `a marker tap shows its note in a sheet, and Close dismisses it`() {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Reader("Newton[^1] wrote it.\n\n[^1]: the **gravity** note\n", renderer)
        }
        drain()

        tapMarkerFor(1)
        composeRule.onNodeWithContentDescription("Footnote 1").assertExists()
        val sheet = ShadowDialog.getLatestDialog()
        assertNotNull("the sheet is its own window", sheet)
        assertEquals(
            "the note, rendered by Markwon (the bold markers are gone)",
            listOf("the gravity note"),
            texts(sheet.window!!.decorView).map { it.trim() }.filter { it.contains("gravity") },
        )

        composeRule.onNodeWithText("Close").performClick()
        drain()
        composeRule.onNodeWithContentDescription("Footnote 1").assertDoesNotExist()
    }

    @Test
    fun `an index with no note behind it opens nothing`() {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Reader("Newton[^1] wrote it.\n\n[^1]: the note\n", renderer)
        }
        drain()

        tapMarkerFor(0) // the paragraph, not a note
        composeRule.onNodeWithContentDescription("Footnote 1").assertDoesNotExist()
    }

    @Test
    fun `the open sheet survives a configuration change, and a new document closes it`() {
        val content = mutableStateOf("Newton[^1] wrote it.\n\n[^1]: the note\n")
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Reader(content.value, renderer)
        }
        drain()
        tapMarkerFor(1)
        composeRule.onNodeWithContentDescription("Footnote 1").assertExists()

        restoration.emulateSavedInstanceStateRestore()
        drain()
        composeRule.onNodeWithContentDescription("Footnote 1").assertExists()

        composeRule.runOnIdle { content.value = "Another[^1] note.\n\n[^1]: other\n" }
        drain()
        composeRule.onNodeWithContentDescription("Footnote 1").assertDoesNotExist()
    }

    /** The reader as production builds it: from a prepared model, whose blocks are already detached. */
    @Composable
    private fun Reader(content: String, renderer: MarkwonRenderer) {
        MarkdownPreview(
            model = remember(content) { renderer.readerModelFor(content) },
            renderer = renderer,
            mermaidRenderer = NullMermaidRenderer,
        )
    }

    @Test
    fun `a note found past a split paragraph is the note the marker named`() {
        // M-328 cuts a long paragraph into several blocks, so the note's list index is not its source order.
        val long = List(400) { "*w$it*" }.joinToString(" ")
        lateinit var model: ReaderModel
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            model = remember { renderer.readerModelFor("$long[^1]\n\n[^1]: the far note\n") }
            MarkdownPreview(
                model = model,
                renderer = renderer,
                mermaidRenderer = NullMermaidRenderer,
            )
        }
        drain()
        val index = model.blocks.indexOfFirst { it is FootnoteDefinitionBlock }
        assertTrue("the fixture must really split", index > 1)

        tapMarkerFor(index)
        composeRule.onNodeWithContentDescription("Footnote 1").assertExists()
    }

    @Test
    fun `a model of the same text keeps the sheet open`() {
        val model = mutableStateOf<ReaderModel?>(null)
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            model.value = model.value ?: renderer.readerModelFor(SAME_TEXT)
            MarkdownPreview(
                model = model.value,
                renderer = renderer,
                mermaidRenderer = NullMermaidRenderer,
            )
        }
        drain()
        tapMarkerFor(1)
        val first = model.value!! as ReaderModel.Markdown
        composeRule.runOnIdle {
            // What the bare links arriving does: new model object, the very same text.
            model.value = ReaderModel.Markdown(
                first.content,
                first.blocks,
                first.details,
                first.anchors,
                first.headings,
            )
        }
        drain()
        composeRule.onNodeWithContentDescription("Footnote 1").assertExists()
    }

    private companion object {
        const val SAME_TEXT = "Newton[^1] wrote it.\n\n[^1]: the note\n"
        const val DRAIN_PASSES = 5
    }
}
