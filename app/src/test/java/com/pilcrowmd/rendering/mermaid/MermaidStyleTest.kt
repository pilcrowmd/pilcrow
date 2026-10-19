// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering.mermaid

import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-70: the diagram's colours come from the scheme's tokens, in the form CSS takes them. */
class MermaidStyleTest {

    private val css = Regex("^#[0-9A-F]{6}$")

    @Test
    fun `dark scheme gives mermaid's dark theme`() {
        assertTrue(MermaidStyle.from(DarkColorScheme).dark)
    }

    @Test
    fun `light scheme gives mermaid's default theme`() {
        assertFalse(MermaidStyle.from(LightColorScheme).dark)
    }

    @Test
    fun `every colour is a six-digit CSS hex string`() {
        for (scheme in listOf(DarkColorScheme, LightColorScheme)) {
            val style = MermaidStyle.from(scheme)
            for (colour in listOf(style.background, style.text, style.line, style.border)) {
                assertTrue("$colour is not #RRGGBB", colour != null && css.matches(colour))
            }
        }
    }

    @Test
    fun `colours are the scheme's own tokens`() {
        val dark = MermaidStyle.from(DarkColorScheme)
        // Hand-converted from the tokens: codeBlockBg, primaryText, secondaryText, codeBlockBorder.
        assertEquals(hex(DarkColorScheme.codeBlockBg.value), dark.background)
        assertEquals(hex(DarkColorScheme.primaryText.value), dark.text)
        assertEquals(hex(DarkColorScheme.secondaryText.value), dark.line)
        assertEquals(hex(DarkColorScheme.codeBlockBorder.value), dark.border)
        assertNotEquals(dark, MermaidStyle.from(LightColorScheme))
    }

    /**
     * M-192: Mermaid's dark pie slices were near-black on the dark panel. Each dark slice must stand
     * out from the panel (3:1, a graphic) and carry its label, drawn in the panel colour, at 4.5:1.
     */
    @Test
    fun `dark pie slices read on the panel and are the scheme's code-token hues`() {
        val dark = MermaidStyle.from(DarkColorScheme)
        val syntax = DarkColorScheme.codeSyntax
        assertEquals(hex(syntax.keyword.value), dark.slices.first())
        assertTrue(
            "at least five distinct slices",
            dark.slices.size >= 5 && dark.slices.toSet().size == dark.slices.size,
        )
        for (slice in dark.slices) {
            val ratio = contrast(slice, dark.background)
            assertTrue("$slice on ${dark.background} is $ratio:1", ratio >= 4.5)
        }
    }

    @Test
    fun `light keeps mermaid's own pie palette`() {
        assertTrue(MermaidStyle.from(LightColorScheme).slices.isEmpty())
    }

    private fun contrast(a: String, b: String): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun relativeLuminance(css: String): Double {
        val rgb = css.removePrefix("#").toInt(16)
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** A Compose sRGB colour's packed value keeps ARGB in its top 32 bits. */
    private fun hex(packed: ULong): String = "#%06X".format(((packed shr 32).toLong() and 0xFFFFFF))
}
