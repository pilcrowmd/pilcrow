// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import com.pilcrowmd.viewmodel.ViewMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S8-A41: when full-screen reading wants the top bar and the system bars hidden. */
class ReadingBarsPolicyTest {

    private fun wanted(
        fullScreen: Boolean = true,
        mode: ViewMode = ViewMode.READER,
        hasDocument: Boolean = true,
        overlayOpen: Boolean = false,
    ) = readingBarsWanted(fullScreen, mode, hasDocument, overlayOpen)

    @Test
    fun `reading with the switch on hides the bars`() = assertTrue(wanted())

    @Test
    fun `switch off never hides anything`() = assertFalse(wanted(fullScreen = false))

    @Test
    fun `edit mode shows the bars`() = assertFalse(wanted(mode = ViewMode.EDITOR))

    @Test
    fun `an overlay such as Settings, search or a dialog shows the bars`() = assertFalse(wanted(overlayOpen = true))

    @Test
    fun `the welcome screen with no document shows the bars`() = assertFalse(wanted(hasDocument = false))

    // Screen 2340 px tall, strips of 136 px (top) and 68 px (bottom), 40 px to count as a swipe.
    private val strips = EdgeStrips(top = 136f, bottom = 68f)

    private fun swipe(startY: Float, endY: Float) = isEdgeSwipe(startY, endY, 2340f, strips, 40f)

    @Test
    fun `a drag down from the top edge is the reveal swipe`() = assertTrue(swipe(10f, 300f))

    @Test
    fun `a drag up from the bottom edge is the reveal swipe`() = assertTrue(swipe(2330f, 2000f))

    @Test
    fun `a drag that starts in the text is not`() = assertFalse(swipe(1000f, 1400f))

    @Test
    fun `a drag from the top edge that goes outward or barely moves is not`() {
        assertFalse(swipe(100f, 20f))
        assertFalse(swipe(10f, 30f))
    }
}
