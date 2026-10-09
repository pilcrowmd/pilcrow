// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-161 through the real reader composable: a search match or a headings jump that lands inside a
 * closed `<details>` section opens it, as a browser's find does. Heights, not flags, are asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewDetailsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // 0 para · 1 header · 2 body heading · 3 body para · 4 </details> · 5 para
    private val content =
        "Before.\n\n<details>\n<summary>Section</summary>\n\n## Inside\n\nHidden needle.\n</details>\n\nAfter."

    private fun show(query: String = "", jumpPosition: Int = -1, jumpSeq: Int = 0): RecyclerView {
        val matches = if (query.isEmpty()) {
            emptyList()
        } else {
            SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(content, query)
        }
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            MarkdownPreview(
                content = content,
                renderer = renderer,
                searchMatches = matches,
                jumpPosition = jumpPosition,
                jumpSeq = jumpSeq,
            )
        }
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return findList(composeRule.activity.window.decorView)!!
    }

    private fun findList(view: View): RecyclerView? = when (view) {
        is RecyclerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) }
        else -> null
    }

    private fun height(list: RecyclerView, position: Int) =
        list.findViewHolderForAdapterPosition(position)!!.itemView.height

    @Test
    fun `the section starts closed`() {
        val list = show()
        assertEquals(0, height(list, 3))
    }

    @Test
    fun `a search match inside a closed section opens it`() {
        val list = show(query = "needle")
        assertTrue("the matched block is visible", height(list, 3) > 0)
    }

    @Test
    fun `a headings jump into a closed section opens it`() {
        val list = show(jumpPosition = 2, jumpSeq = 1)
        assertTrue("the heading jumped to is visible", height(list, 2) > 0)
    }

    private companion object {
        const val DRAIN_PASSES = 5
    }
}
