// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.PilcrowTypography
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.HeadingSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-164 — heading sizes as the reader actually draws them.
 *
 * Each level is rendered through the production plugin chain, and its [HeadingSpan] is applied to
 * a paint at the body size: that is the exact call Markwon makes when the TextView measures the
 * heading, so the resulting text size is the rendered size. Nothing else in the fixture can
 * change it — the only input is the theme's multiplier for that level.
 *
 * Seen failing with Markwon's stock multipliers (the plugin unregistered): H5 at 14.11 sp and H6
 * at 11.39 sp against a 17 sp body.
 */
@RunWith(RobolectricTestRunner::class)
class HeadingScaleTest {

    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    /** Rendered size, in sp, of a level-[level] heading drawn at the prose body size. */
    private fun renderedHeadingSizeSp(level: Int): Float {
        val spanned = markwon.render(markwon.parse("#".repeat(level) + " Heading")) as Spanned
        val span = spanned.getSpans(0, spanned.length, HeadingSpan::class.java).single()
        val paint = TextPaint().apply { textSize = PilcrowTypography.PROSE_BODY_FONT_SIZE_SP }
        span.updateMeasureState(paint)
        return paint.textSize
    }

    @Test
    fun noHeadingRendersSmallerThanBody() {
        for (level in 1..6) {
            val size = renderedHeadingSizeSp(level)
            assertTrue(
                "H$level renders at $size sp, smaller than the ${PilcrowTypography.PROSE_BODY_FONT_SIZE_SP} sp body",
                size >= PilcrowTypography.PROSE_BODY_FONT_SIZE_SP,
            )
        }
    }

    @Test
    fun headingsRenderAtTheSpecSizes() {
        val specSp = floatArrayOf(29f, 23f, 21f, 19f, 18f, 17f) // the design system's type scale
        for (level in 1..6) {
            assertEquals("H$level", specSp[level - 1], renderedHeadingSizeSp(level), 0.01f)
        }
    }
}
