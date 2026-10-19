// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import java.util.Locale

/**
 * How a Mermaid diagram is coloured: Mermaid's base theme plus the reader's own colours, written
 * as CSS `#RRGGBB` strings so they can be handed to the page unchanged.
 *
 * A data class on purpose: it is part of the render cache key, so a theme change draws afresh.
 */
data class MermaidStyle(
    /** Mermaid's `dark` theme when true, its `default` theme otherwise. */
    val dark: Boolean,
    /** The panel the diagram sits on. */
    val background: String,
    /** Labels, titles and legends. */
    val text: String,
    /** Edges, arrows and secondary text. */
    val line: String,
    /** Node borders; `null` keeps the Mermaid theme's own. */
    val border: String? = null,
    /**
     * Pie slice fills, in order; empty keeps the Mermaid theme's own. Mermaid's dark theme fills
     * slices with near-black shades that vanish on the dark panel (M-192), so a dark style takes the
     * reader's code-token hues instead, which are made to read on that same panel.
     */
    val slices: List<String> = emptyList(),
) {
    companion object {
        /**
         * The style for [scheme]. Colours come only from its tokens: the diagram sits on the code
         * block panel, as the source it replaces does. Dark is decided by the page's luminance
         * rather than by which scheme this is, so any future dark scheme gets the dark theme.
         */
        fun from(scheme: PilcrowColorScheme): MermaidStyle {
            val dark = scheme.primaryBackground.luminance() < DARK_LUMINANCE
            val syntax = scheme.codeSyntax
            return MermaidStyle(
                dark = dark,
                background = scheme.codeBlockBg.toCss(),
                text = scheme.primaryText.toCss(),
                line = scheme.secondaryText.toCss(),
                border = scheme.codeBlockBorder.toCss(),
                slices = if (dark) {
                    // Each one carries a slice label drawn in the panel colour at 4.5:1 or more; the code
                    // tokens' purple (`function`, 4.42:1) and red (`error`, 4.07:1) do not, so the purple
                    // is the footnote marker's. Ordered so blue and cyan are not neighbours in the first six slices.
                    listOfNotNull(
                        syntax.keyword,
                        syntax.number,
                        syntax.string,
                        scheme.footnoteMarker,
                        syntax.emphasis,
                        syntax.builtin,
                    ).map { it.toCss() }.distinct()
                } else {
                    emptyList()
                },
            )
        }

        private const val DARK_LUMINANCE = 0.5f
        private const val RGB_MASK = 0xFFFFFF

        /** `#RRGGBB`, alpha dropped. */
        private fun Color.toCss(): String = String.format(Locale.ROOT, "#%06X", toArgb() and RGB_MASK)
    }
}
