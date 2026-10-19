// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * M-92 — the AMOLED contrast matrix: every foreground token reaches 7:1
 * (WCAG AAA) on every surface it can appear on, and the text on the accent reaches 7:1 on the accent.
 *
 * The one pair below the floor, the accent on the code-block surface (6.81:1), is not skipped: it is
 * an [EXCLUSIONS] entry with its reason, and [theExclusionIsStillNeeded] fails if it ever passes, so
 * the list cannot outlive the reason. The rule it encodes: the accent never paints inside a code block.
 */
class AmoledContrastTest {

    private data class ColourPair(val foreground: String, val surface: String)

    /** The palette's foregrounds. `lineNumbers` is the disabled-text role, `editorText` the code text. */
    private fun foregrounds(scheme: PilcrowColorScheme): Map<String, Color> = mapOf(
        "primaryText" to scheme.primaryText,
        "secondaryText" to scheme.secondaryText,
        "lineNumbers" to scheme.lineNumbers,
        "editorText" to scheme.editorText,
        "accent" to scheme.accent,
        "footnoteMarker" to scheme.footnoteMarker,
    )

    private fun surfaces(scheme: PilcrowColorScheme): Map<String, Color> = mapOf(
        "primaryBackground" to scheme.primaryBackground,
        "secondarySurface" to scheme.secondarySurface,
        "codeBlockBg" to scheme.codeBlockBg,
    )

    /** Every pair below [FLOOR] that is not an exclusion, as readable lines. Empty is a pass. */
    private fun failures(scheme: PilcrowColorScheme): List<String> {
        val pairs = foregrounds(scheme).flatMap { (fgName, fg) ->
            surfaces(scheme).mapNotNull { (bgName, bg) ->
                if (ColourPair(fgName, bgName) in EXCLUSIONS) null else Triple("$fgName on $bgName", fg, bg)
            }
        } + Triple("onAccent on accent", scheme.onAccent, scheme.accent)
        return pairs.mapNotNull { (name, fg, bg) ->
            val ratio = wcagContrast(fg, bg)
            if (ratio >= FLOOR) null else String.format(Locale.ROOT, "%s is %.2f:1, below %.1f:1", name, ratio, FLOOR)
        }
    }

    @Test
    fun everyAmoledForegroundReachesSevenToOneOnEverySurfaceItCanAppearOn() {
        val failures = failures(AmoledColorScheme)
        assertTrue("AMOLED contrast below the AAA floor:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun theExclusionIsStillNeeded() {
        for (pair in EXCLUSIONS.keys) {
            val fg = foregrounds(AmoledColorScheme).getValue(pair.foreground)
            val bg = surfaces(AmoledColorScheme).getValue(pair.surface)
            val ratio = wcagContrast(fg, bg)
            val message = String.format(Locale.ROOT, "%s is %.2f:1, so its exclusion can go", pair, ratio)
            assertTrue(message, ratio < FLOOR)
        }
    }

    /**
     * The check is seen to fail: the accent Dark ships, `#8E7CD6`, on AMOLED's card is
     * 5.66:1, and the checker must report exactly that.
     */
    @Test
    fun theCheckRejectsDarksAccentOnTheAmoledCard() {
        val failures = failures(AmoledColorScheme.copy(accent = Color(0xFF8E7CD6)))
        assertTrue(failures.toString(), "accent on secondarySurface is 5.66:1, below 7.0:1" in failures)
    }

    @Test
    fun theExclusionsAreTheAccentOnTheCodeBlockOnly() {
        assertEquals(
            setOf(ColourPair("accent", "codeBlockBg"), ColourPair("footnoteMarker", "codeBlockBg")),
            EXCLUSIONS.keys,
        )
    }

    private companion object {
        const val FLOOR = 7.0

        const val ACCENT_NOT_IN_CODE = "spec section 4: accent never paints inside code blocks"

        /** Pairs exempt from [FLOOR], each with its reason. Nothing else is skipped. */
        val EXCLUSIONS: Map<ColourPair, String> = mapOf(
            ColourPair("accent", "codeBlockBg") to ACCENT_NOT_IN_CODE,
            ColourPair("footnoteMarker", "codeBlockBg") to ACCENT_NOT_IN_CODE,
        )
    }
}
