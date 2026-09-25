// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * M-132 — every code-token colour the Light theme draws must reach 4.5:1 on the Light code-block
 * background, and every role must be set in Dark and Light. Print is the opposite case: its added
 * roles must stay unset, because that is what keeps the exported PDF unchanged.
 *
 * Contrast is the WCAG 2.x ratio computed from the sRGB values, written out here rather than taken
 * from a library, so the test does not share its method with anything it checks.
 */
class CodeSyntaxContrastTest {

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun roles(syntax: CodeSyntaxColors): Map<String, Color?> = mapOf(
        "keyword" to syntax.keyword,
        "string" to syntax.string,
        "number" to syntax.number,
        "comment" to syntax.comment,
        "error" to syntax.error,
        "function" to syntax.function,
        "heading" to syntax.heading,
        "emphasis" to syntax.emphasis,
        "link" to syntax.link,
        "marker" to syntax.marker,
        "literal" to syntax.literal,
        "variable" to syntax.variable,
        "builtin" to syntax.builtin,
        "inserted" to syntax.inserted,
        "deleted" to syntax.deleted,
    )

    @Test
    fun everyLightTokenColourReachesFourAndAHalfToOneOnTheLightCodeBlock() {
        val background = LightColorScheme.codeBlockBg
        for ((role, color) in roles(LightColorScheme.codeSyntax)) {
            assertNotNull("Light role '$role' is unset", color)
            val ratio = contrast(color!!, background)
            assertTrue("Light '$role' is %.2f:1 on the code block, below 4.5:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun everyDarkRoleIsSet() {
        for ((role, color) in roles(DarkColorScheme.codeSyntax)) assertNotNull("Dark role '$role' is unset", color)
    }

    @Test
    fun printLeavesEveryAddedRoleUnset() {
        val added = roles(PrintColorScheme.codeSyntax).filterKeys {
            it !in setOf("keyword", "string", "number", "comment", "error", "function")
        }
        for ((role, color) in added) assertNull("Print role '$role' is set, which would change the PDF", color)
    }
}
