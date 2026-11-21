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
import com.pilcrowmd.domain.markdown.ReaderModel
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
 * An edit of the open document reaching the reader (a ticked task box) keeps the reader where it is,
 * and the list is not emptied while the edited text's model is prepared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewEditKeepsPlaceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val uri = Uri.parse("content://t/todo.md")

    private val text = (0 until BLOCKS).joinToString("\n\n") { if (it == TASK_AT) "- [ ] task" else "Paragraph $it." }

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

    private val list get() = findList(composeRule.activity.window.decorView)!!

    private fun firstVisible(): Int = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

    @Test
    fun `an edit of the same document keeps the place and never empties the list`() {
        val content = mutableStateOf(text)
        val ready = mutableStateOf(true)
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                val built: ReaderModel = remember(content.value) { renderer.readerModelFor(content.value) }
                MarkdownPreview(
                    model = built.takeIf { ready.value },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = ScrollAnchor(0, 0),
                    documentUri = uri,
                )
            }
        }
        drain()
        composeRule.runOnIdle { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(READ_AT, 0) }
        drain()
        assertEquals(READ_AT, firstVisible())

        // The tick: the old model is gone, the new one is still being prepared.
        composeRule.runOnIdle { ready.value = false }
        drain()
        assertEquals("the previous model stays on screen meanwhile", BLOCKS, list.adapter!!.itemCount)
        assertEquals(READ_AT, firstVisible())

        composeRule.runOnIdle {
            content.value = text.replace("- [ ] task", "- [x] task")
            ready.value = true
        }
        drain()

        assertEquals("the edited text keeps the place, not the opening one", READ_AT, firstVisible())
    }

    @Test
    fun `another document still opens at its own place`() {
        val note = mutableStateOf(uri)
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember(note.value) { renderer.readerModelFor(text + "\n\n" + note.value) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    scrollPosition = if (note.value == uri) ScrollAnchor(0, 0) else ScrollAnchor(SAVED_AT, 0),
                    documentUri = note.value,
                )
            }
        }
        drain()
        composeRule.runOnIdle { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(READ_AT, 0) }
        drain()

        composeRule.runOnIdle { note.value = Uri.parse("content://t/other.md") }
        drain()

        assertEquals(SAVED_AT, firstVisible())
    }

    private companion object {
        const val BLOCKS = 80
        const val TASK_AT = 45
        const val READ_AT = 40
        const val SAVED_AT = 30
        const val BOX_DP = 300
        const val DRAIN_PASSES = 5
    }
}
