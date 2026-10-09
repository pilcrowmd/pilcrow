// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.rendering.MarkwonRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-184 through the real reader composable: once the reader has performed a headings jump it
 * reports that jump's seq, so the ViewModel can drop it and a reader composed later (the same file
 * re-opened, or the reader coming back from the editor) never replays it. The ViewModel side is
 * [com.pilcrowmd.viewmodel.MarkdownViewModelHeadingJumpTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewJumpAckTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val content = "# One\n\nText.\n\n# Two\n\nMore."

    private fun show(jumpPosition: Int, jumpSeq: Int): List<Int> {
        val acked = mutableListOf<Int>()
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            MarkdownPreview(
                content = content,
                renderer = renderer,
                jumpPosition = jumpPosition,
                jumpSeq = jumpSeq,
                onJumpHandled = { acked += it },
            )
        }
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return acked
    }

    @Test
    fun `a performed jump is acknowledged once with its seq`() {
        assertEquals(listOf(7), show(jumpPosition = 2, jumpSeq = 7))
    }

    private companion object {
        const val DRAIN_PASSES = 5
    }
}
