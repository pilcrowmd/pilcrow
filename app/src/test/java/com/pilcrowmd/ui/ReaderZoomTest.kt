// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import com.pilcrowmd.ui.components.ReaderZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the reader pinch-zoom clamp/quantise helper (Fix #6). */
class ReaderZoomTest {

    @Test
    fun clampsBelowMinToMin() {
        assertEquals(ReaderZoom.MIN_SCALE, ReaderZoom.clampScale(0.5f), 0.0001f)
    }

    @Test
    fun clampsAboveMaxToMax() {
        assertEquals(ReaderZoom.MAX_SCALE, ReaderZoom.clampScale(3.0f), 0.0001f)
    }

    @Test
    fun quantisesToWholePercent() {
        // 1.234 → 1.23 (whole-percent steps, matching the Settings slider granularity).
        assertEquals(1.23f, ReaderZoom.clampScale(1.234f), 0.0001f)
        assertEquals(1.24f, ReaderZoom.clampScale(1.236f), 0.0001f)
    }

    @Test
    fun midRangeScaleIsClampedWithinBounds() {
        val s = ReaderZoom.clampScale(1.1f)
        assertTrue(s in ReaderZoom.MIN_SCALE..ReaderZoom.MAX_SCALE)
        assertEquals(1.1f, s, 0.0001f)
    }

    @Test
    fun boundsMatchViewModelRange() {
        // Guards that the pinch bounds stay aligned with setPreviewFontScale's coerceIn(0.85, 1.6).
        assertEquals(0.85f, ReaderZoom.MIN_SCALE, 0.0001f)
        assertEquals(1.6f, ReaderZoom.MAX_SCALE, 0.0001f)
    }

    @Test
    fun dampedScaleNoGestureKeepsScale() {
        assertEquals(1.0f, ReaderZoom.dampedScale(1.0f, 1.0f), 0.0001f)
    }

    @Test
    fun dampedScaleModeratePinchIsGradualNotSaturated() {
        // The original feel bug: a natural 2× pinch slammed straight to MAX (felt like large/small).
        // Damping (0.5) maps a 2× gesture to a gradual 1.5×, well short of the cap.
        val s = ReaderZoom.dampedScale(1.0f, 2.0f)
        assertEquals(1.5f, s, 0.0001f)
        assertTrue("must not saturate to max on a moderate pinch", s < ReaderZoom.MAX_SCALE)
    }

    @Test
    fun dampedScaleIsContinuousNotQuantised() {
        // The LIVE scale drives setTextSize every frame, so it must be continuous (NOT rounded to
        // whole-percent like the persisted value) — otherwise the resize looks stepped. A 23.4%
        // pinch out, damped by 0.5, is a continuous 11.7% increase, not 11% or 12%.
        assertEquals(1.117f, ReaderZoom.dampedScale(1.0f, 1.234f), 0.0001f)
    }

    @Test
    fun dampedScaleClampsToBounds() {
        // The continuous live value still clamps to the same [MIN, MAX] range as the committed value.
        assertEquals(ReaderZoom.MAX_SCALE, ReaderZoom.dampedScale(1.0f, 3.0f), 0.0001f)
        assertEquals(ReaderZoom.MIN_SCALE, ReaderZoom.dampedScale(1.0f, 0.4f), 0.0001f)
    }

    @Test
    fun dampedScaleQuantisesToCommittedValueViaClampScale() {
        // On gesture end the continuous live value is quantised to the whole-percent committed value.
        assertEquals(1.12f, ReaderZoom.clampScale(ReaderZoom.dampedScale(1.0f, 1.234f)), 0.0001f)
    }
}
