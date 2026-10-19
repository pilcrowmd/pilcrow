// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-132 — every code-token colour the Light theme draws must reach 4.5:1 on the Light code-block
 * background, and every role must be set in Dark and Light. The PDF draws its code on the Light code
 * panel (M-178), so every Print role must reach the same 4.5:1 on that panel. AMOLED's roles (M-92)
 * are held to the same 4.5:1 on AMOLED's own code-block background.
 *
 * Contrast is the WCAG 2.x ratio from [wcagContrast], written out in the tests rather than taken
 * from a library, so the test does not share its method with anything it checks.
 */
class CodeSyntaxContrastTest {

    private fun contrast(a: Color, b: Color): Double = wcagContrast(a, b)

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
    fun everyAmoledTokenColourReachesFourAndAHalfToOneOnTheAmoledCodeBlock() {
        val background = AmoledColorScheme.codeBlockBg
        for ((role, color) in roles(AmoledColorScheme.codeSyntax)) {
            assertNotNull("AMOLED role '$role' is unset", color)
            val ratio = contrast(color!!, background)
            assertTrue("AMOLED '$role' is %.2f:1 on the code block, below 4.5:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun everyDarkRoleIsSet() {
        for ((role, color) in roles(DarkColorScheme.codeSyntax)) assertNotNull("Dark role '$role' is unset", color)
    }

    @Test
    fun everyPrintTokenColourReachesFourAndAHalfToOneOnTheLightCodeBlock() {
        val background = LightColorScheme.codeBlockBg
        for ((role, color) in roles(PrintColorScheme.codeSyntax)) {
            assertNotNull("Print role '$role' is unset", color)
            val ratio = contrast(color!!, background)
            assertTrue("Print '$role' is %.2f:1 on the PDF code panel, below 4.5:1".format(ratio), ratio >= 4.5)
        }
    }

    /**
     * M-216: a diff's added and removed text is drawn on its line tint, and that pair is held to
     * the same 4.5:1 as every other code colour. Dark is measured too: its `deleted` was nudged for this.
     */
    private fun assertDiffPairs(label: String, syntax: CodeSyntaxColors) {
        val pairs = mapOf(
            "inserted" to (syntax.inserted to syntax.insertedLineBg),
            "deleted" to (syntax.deleted to syntax.deletedLineBg),
        )
        for ((role, pair) in pairs) {
            val (fg, bg) = pair
            assertNotNull("$label '$role' is unset", fg)
            assertNotNull("$label '$role' line tint is unset", bg)
            val ratio = contrast(fg!!, bg!!)
            println("M-216 contrast $label $role on its tint: %.2f:1".format(ratio))
            assertTrue("$label '$role' is %.2f:1 on its line tint, below 4.5:1".format(ratio), ratio >= 4.5)
        }
    }

    @Test
    fun diffTextReachesFourAndAHalfToOneOnItsLineTintInDark() = assertDiffPairs("Dark", DarkColorScheme.codeSyntax)

    @Test
    fun diffTextReachesFourAndAHalfToOneOnItsLineTintInLight() = assertDiffPairs("Light", LightColorScheme.codeSyntax)

    @Test
    fun diffTextReachesFourAndAHalfToOneOnItsLineTintInPrint() = assertDiffPairs("Print", PrintColorScheme.codeSyntax)
}
