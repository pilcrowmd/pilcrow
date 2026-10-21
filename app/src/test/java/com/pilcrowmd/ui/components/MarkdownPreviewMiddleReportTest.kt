// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.pilcrowmd.rendering.NullMermaidRenderer
import com.pilcrowmd.rendering.warmedMarkwonRenderer
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Issue #15: the reader tells the ViewModel what sits in the middle of its screen. A document opened
 * and left alone (no scroll, no drag) must still report it, because a first Edit lands on that line.
 * RecyclerView calls `onScrolled(0, 0)` after its first layout, which is what the reader hooks; this
 * pins that, so the first Edit cannot find nothing reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarkdownPreviewMiddleReportTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val content = (0 until BLOCK_COUNT).joinToString("\n\n") { "Paragraph $it." }

    private data class Report(val block: Int, val fraction: Float, val viewportHeight: Int)

    @Test
    fun `an untouched reader reports the block in the middle of its screen`() {
        val reports = mutableListOf<Report>()
        var moved = false
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { warmedMarkwonRenderer(context) }
            Box(Modifier.height(BOX_DP.dp)) {
                MarkdownPreview(
                    model = remember { renderer.readerModelFor(content) },
                    renderer = renderer,
                    mermaidRenderer = NullMermaidRenderer,
                    onReaderMoved = { moved = true },
                    onReaderMiddleChanged = { block, fraction, height -> reports += Report(block, fraction, height) },
                )
            }
        }
        repeat(DRAIN_PASSES) {
            composeRule.waitForIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }

        assertTrue("the reader reported its middle with nobody touching it", reports.isNotEmpty())
        val last = reports.last()
        assertTrue("a block in the document: ${last.block}", last.block in 1 until BLOCK_COUNT - 1)
        assertTrue("a screen height: ${last.viewportHeight}", last.viewportHeight > 0)
        assertTrue("a fraction within the block: ${last.fraction}", last.fraction in 0f..1f)
        assertEquals("a layout is not a move by the user", false, moved)
    }

    private companion object {
        const val BLOCK_COUNT = 80
        const val BOX_DP = 300
        const val DRAIN_PASSES = 5
    }
}
