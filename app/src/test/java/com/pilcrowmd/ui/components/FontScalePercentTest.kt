// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The A−/A+ size stepper and its % readout must land on clean 5% stops (…95, 100, 105, 110…)
 * — never the float-truncation artifact "104". [scaleToPercent] rounds; [steppedScale] works in
 * integer-percent space.
 */
class FontScalePercentTest {

    @Test
    fun percent_rounds_not_truncates() {
        // 1.05f as a float is ~1.0499999 → toInt() gave 104; roundToInt() gives 105.
        assertEquals(105, scaleToPercent(1.05f))
        assertEquals(100, scaleToPercent(1.0f))
        assertEquals(85, scaleToPercent(0.85f))
        assertEquals(160, scaleToPercent(1.6f))
    }

    @Test
    fun stepUp_landsOnCleanFivePercentStops() {
        assertEquals(105, scaleToPercent(steppedScale(1.0f, +5)))
        assertEquals(110, scaleToPercent(steppedScale(1.05f, +5)))
        assertEquals(100, scaleToPercent(steppedScale(0.95f, +5)))
    }

    @Test
    fun stepDown_landsOnCleanFivePercentStops() {
        assertEquals(95, scaleToPercent(steppedScale(1.0f, -5)))
        assertEquals(100, scaleToPercent(steppedScale(1.05f, -5)))
    }

    @Test
    fun stepping_snapsOffGridValueToNearestFiveFirst() {
        // 1.02 (102%) → nearest 5% stop is 100% → +5 ⇒ 105%, −5 ⇒ 95%.
        assertEquals(105, scaleToPercent(steppedScale(1.02f, +5)))
        assertEquals(95, scaleToPercent(steppedScale(1.02f, -5)))
    }

    @Test
    fun stepping_clampsToRange() {
        assertEquals(160, scaleToPercent(steppedScale(1.6f, +5)))
        assertEquals(85, scaleToPercent(steppedScale(0.85f, -5)))
    }
}
