// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S8-A7: the full-screen viewer's zoom maths, on plain values. The square fixture (a 1000 px picture in
 * a 1000 px viewport) makes the fitted picture fill the viewport, so the edge limits are simple sums;
 * the wide fixture has a picture shorter than the viewport, so the centring is the only thing that can
 * decide the vertical offset.
 */
class DiagramZoomTest {

    private val square = DiagramZoom().layout(viewport = Size(1000f, 1000f), picture = Size(1000f, 1000f))
    private val wide = DiagramZoom().layout(viewport = Size(1000f, 1000f), picture = Size(2000f, 1000f))

    /** The point of the fitted layout that shows at [screen]. */
    private fun contentAt(zoom: DiagramZoom, screen: Offset): Offset = (screen - zoom.offset) / zoom.scale

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals("x", expected.x, actual.x, 0.5f)
        assertEquals("y", expected.y, actual.y, 0.5f)
    }

    @Test
    fun `scale stays between fit and five times`() {
        assertEquals(5f, square.transform(Offset(500f, 500f), Offset.Zero, 100f).scale, 0f)
        assertEquals(1f, square.transform(Offset(500f, 500f), Offset.Zero, 0.01f).scale, 0f)
        val twice = square.transform(Offset(500f, 500f), Offset.Zero, 2f)
        val zoomedOut = twice.transform(Offset(500f, 500f), Offset.Zero, 0.1f)
        assertEquals("zooming out from 2x stops at fit", 1f, zoomedOut.scale, 0f)
    }

    @Test
    fun `a pinch keeps the point under the fingers where it was`() {
        val first = Offset(300f, 400f)
        val second = Offset(600f, 200f)
        val pinched = square.transform(first, Offset.Zero, 2f)
        assertOffset(contentAt(square, first), contentAt(pinched, first))

        val again = pinched.transform(second, Offset.Zero, 1.5f)
        assertEquals(3f, again.scale, 0f)
        assertOffset(contentAt(pinched, second), contentAt(again, second))
    }

    @Test
    fun `panning cannot show blank space past the picture`() {
        val zoomed = square.transform(Offset(500f, 500f), Offset.Zero, 2f)

        assertOffset(Offset(0f, 0f), zoomed.transform(Offset(500f, 500f), Offset(5000f, 5000f), 1f).offset)
        assertOffset(Offset(-1000f, -1000f), zoomed.transform(Offset(500f, 500f), Offset(-5000f, -5000f), 1f).offset)
    }

    @Test
    fun `at fit the picture is centred and cannot be moved`() {
        assertOffset(Offset.Zero, square.transform(Offset(500f, 500f), Offset(80f, -60f), 1f).offset)
        assertOffset(Offset.Zero, wide.transform(Offset(500f, 500f), Offset(80f, -60f), 1f).offset)
    }

    @Test
    fun `an axis shorter than the viewport stays centred while the other pans`() {
        // Fitted, the wide picture is 1000 x 500, centred vertically (top at 250). At 2x it is 2000 x 1000:
        // exactly the viewport's height, so y stays put; x is free to pan across 1000 px.
        val zoomed = wide.transform(Offset(500f, 500f), Offset.Zero, 2f)
        val panned = zoomed.transform(Offset(500f, 500f), Offset(-300f, 300f), 1f)

        assertEquals("x pans", zoomed.offset.x - 300f, panned.offset.x, 0.5f)
        assertEquals("y stays centred", zoomed.offset.y, panned.offset.y, 0.5f)
        assertEquals("the centred picture's middle is the viewport's middle", 500f, panned.offset.y + 2f * 500f, 0.5f)
    }

    @Test
    fun `double-tap zooms to two and a half about the tap, and a second one returns to fit`() {
        val tap = Offset(300f, 400f)

        val zoomed = square.doubleTap(tap)
        assertEquals(2.5f, zoomed.scale, 0f)
        assertOffset(contentAt(square, tap), contentAt(zoomed, tap))

        val back = zoomed.doubleTap(Offset(800f, 800f))
        assertEquals(1f, back.scale, 0f)
        assertOffset(Offset.Zero, back.offset)
    }
}
