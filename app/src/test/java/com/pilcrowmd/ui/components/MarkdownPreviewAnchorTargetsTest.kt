// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.rendering.AnchorTargets
import com.pilcrowmd.rendering.MarkwonRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-159 through the real reader composable: the RecyclerView carries the document's heading anchors
 * (what a `[text](#heading)` tap looks up), and they are refilled when the document changes. The
 * link resolver's own tests set that tag by hand, so this is the test that the screen fills it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewAnchorTargetsTest {

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

    @Test
    fun `the reader's list carries the heading anchors and refills them when the content changes`() {
        val content = mutableStateOf("Intro.\n\n## First\n\ntext\n\n## Second\n")
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            MarkdownPreview(content = content.value, renderer = renderer)
        }
        drain()
        val list = findList(composeRule.activity.window.decorView)!!
        val targets = list.getTag(R.id.anchor_targets) as AnchorTargets
        assertEquals(mapOf("first" to 1, "second" to 3), targets.byAnchor)

        composeRule.runOnIdle { content.value = "## Other\n" }
        drain()
        assertEquals("the same holder, refilled for the new document", mapOf("other" to 0), targets.byAnchor)
    }

    private companion object {
        const val DRAIN_PASSES = 5
    }
}
