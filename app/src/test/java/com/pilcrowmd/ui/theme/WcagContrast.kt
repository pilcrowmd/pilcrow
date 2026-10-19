// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The WCAG 2.x contrast ratio of two opaque colours, computed from their sRGB values. Written out
 * here rather than taken from a library, so a contrast test does not share its method with anything
 * it checks. Shared by [CodeSyntaxContrastTest] and [AmoledContrastTest].
 */
internal fun wcagContrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun relativeLuminance(color: Color): Double =
    0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
