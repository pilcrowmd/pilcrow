// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-160 through the real reader composable: where the list sits when a model of the same text is
 * swapped in (the note's bare links arriving), when a note is opened in place of another, and when a
 * note is opened by a link to a heading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewNoteLinksTest {

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

    private fun firstVisible(): Int {
        val list = findList(composeRule.activity.window.decorView)!!
        return (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
    }

    private fun scrollTo(block: Int) {
        val list = findList(composeRule.activity.window.decorView)!!
        composeRule.runOnIdle { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(block, 0) }
        drain()
    }

    // 80 short paragraphs; the last one holds a bare link and a heading sits at block 50.
    private val text = (0 until BLOCKS).joinToString("\n\n") {
        when (it) {
            HEADING_AT -> "## Third"
            BLOCKS - 1 -> "See [two](Note 2.md)."
            else -> "Paragraph $it."
        }
    }

    @Test
    fun `a model of the same text swapped in keeps the reader where it is`() {
        val bare = mutableStateOf(emptySet<String>())
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember(bare.value) { renderer.readerModelFor(text, bareLinks = bare.value) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                )
            }
        }
        drain()
        scrollTo(READ_AT)
        assertEquals(READ_AT, firstVisible())

        composeRule.runOnIdle { bare.value = setOf("Note 2.md") }
        drain()

        assertEquals("the bare links arriving did not move the reader", READ_AT, firstVisible())
    }

    @Test
    fun `a note opened in place of another restores its own saved place`() {
        val note = mutableStateOf(Uri.parse("content://t/a.md") to ScrollAnchor(0, 0))
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    // A different text for each note, as a model for another note is.
                    model = remember(note.value.first) { renderer.readerModelFor(text + "\n\n" + note.value.first) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = note.value.second,
                    documentUri = note.value.first,
                )
            }
        }
        drain()
        assertEquals(0, firstVisible())

        composeRule.runOnIdle { note.value = Uri.parse("content://t/b.md") to ScrollAnchor(SAVED_AT, 0) }
        drain()

        assertEquals("the second note's own place", SAVED_AT, firstVisible())
    }

    @Test
    fun `a note opened by a link lands on its heading, once, and not back at the saved place`() {
        var landed = 0
        val landOn = mutableStateOf<String?>("third")
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember { renderer.readerModelFor(text) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = ScrollAnchor(SAVED_AT, 0),
                    landOn = landOn.value,
                    onLanded = {
                        landed++
                        landOn.value = null
                    },
                )
            }
        }
        drain()

        assertEquals("on the heading, not the saved place", HEADING_AT, firstVisible())
        assertEquals("the landing was applied and reported exactly once", 1, landed)
    }

    @Test
    fun `a heading that names nothing lands at the top`() {
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember { renderer.readerModelFor(text) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = ScrollAnchor(SAVED_AT, 0),
                    landOn = "nothing here",
                )
            }
        }
        drain()

        assertEquals(0, firstVisible())
    }

    private companion object {
        const val BLOCKS = 80
        const val HEADING_AT = 50
        const val READ_AT = 40
        const val SAVED_AT = 30
        const val BOX_DP = 300
        const val DRAIN_PASSES = 5
    }
}
