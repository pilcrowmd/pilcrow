// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import kotlin.math.roundToInt

/**
 * Pure helper for reader pinch-to-zoom (Fix #6). Keeps the clamp/quantise logic out of the gesture
 * callback so it is unit-testable. The result feeds the **same** `previewFontScale` setting the
 * Settings A−/A+ slider writes, so pinch and Settings stay in lockstep (single source of truth).
 */
object ReaderZoom {
    /** Preview font-scale bounds — must match `MarkdownViewModel.setPreviewFontScale` (0.85×–1.6×). */
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.6f

    // Whole-percent quantisation step (matches the Settings slider's 1% granularity).
    private const val PERCENT = 100f

    /**
     * Damping applied to the raw pinch gesture (UAT feedback): the font-scale range is narrow
     * (0.85×–1.6×, ~1.9×) while a natural pinch easily spans 2–3×, so a 1:1 mapping saturates
     * instantly — the zoom felt like a 2-state large/small toggle. Mapping only a fraction of the
     * gesture span onto the scale makes it gradual and controllable across the whole range.
     */
    private const val DAMPING = 0.5f

    /**
     * Quantise a raw scale to whole-percent steps (matching the Settings slider's 1% granularity)
     * and clamp into [[MIN_SCALE], [MAX_SCALE]].
     */
    fun clampScale(raw: Float): Float {
        val rounded = (raw * PERCENT).roundToInt() / PERCENT
        return rounded.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * Continuous live font scale for a pinch that started at [startScale] and has accumulated
     * [gestureScale] (the product of the detector's per-event scale factors). The gesture is
     * **damped** so the zoom is gradual, then clamped to [[MIN_SCALE], [MAX_SCALE]] — but **NOT
     * quantised**, because this value drives `setTextSize` every frame and must vary continuously
     * for a smooth resize. The persisted value is this run through [clampScale] on gesture end.
     */
    fun dampedScale(startScale: Float, gestureScale: Float): Float {
        val damped = 1f + (gestureScale - 1f) * DAMPING
        return (startScale * damped).coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
