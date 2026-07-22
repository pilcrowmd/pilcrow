// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Pilcrow Spacing Scale — 4–64dp.
 * All multiples of 4dp for consistent visual rhythm.
 */
object PilcrowSpacing {
    val xs = 4.dp // Icon gaps, inline padding (rare)
    val sm = 8.dp // Compact element spacing, list item gaps
    val md = 12.dp // Editor gutter width, specific purposes
    val lg = 16.dp // Default padding, element spacing
    val xl = 24.dp // Section spacing, paragraph spacing
    val xxl = 32.dp // Layout gaps, list indentation
    val xxxl = 48.dp // Major section breaks, horizontal rules margin
    val xxxxl = 64.dp // Page-level spacing

    // Layout-specific values
    val contentHorizontalPadding = 20.dp // Mobile horizontal padding
    val paragraphSpacing = 24.dp // Vertical rhythm
    val listItemSpacing = 8.dp // Vertical gap between list items
    val listItemIndent = 32.dp // Nested list left margin
    val editorGutterWidth = 12.dp // Gap between line number column and source text
    val lineNumberColumnWidth = 32.dp // Fixed width for line number display
}
