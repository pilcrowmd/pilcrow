// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.ui.theme.PilcrowTypography
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme

/**
 * Heading sizes H1–H6 (M-164), from the design system's type scale. Markwon sizes a heading as a
 * multiplier of the text size it is drawn at, so the sp values are divided by the prose body size
 * here; the reader's zoom then scales headings and body together.
 *
 * Without this plugin Markwon's stock multipliers {2, 1.5, 1.17, 1, 0.83, 0.67} apply, which put
 * H4 at body size and H5/H6 below it.
 */
internal class HeadingScalePlugin : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder.headingTextSizeMultipliers(HEADING_SIZE_MULTIPLIERS)
    }

    companion object {
        /** H1–H6 in sp at 100% zoom. H6 equals body and differs from it by weight alone. */
        private val HEADING_SIZES_SP = floatArrayOf(29f, 23f, 21f, 19f, 18f, 17f)

        val HEADING_SIZE_MULTIPLIERS: FloatArray =
            FloatArray(HEADING_SIZES_SP.size) { HEADING_SIZES_SP[it] / PilcrowTypography.PROSE_BODY_FONT_SIZE_SP }
    }
}
