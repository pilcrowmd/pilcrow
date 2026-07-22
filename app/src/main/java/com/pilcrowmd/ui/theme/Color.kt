// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Swappable color scheme for Pilcrow. Contains all 21 color tokens.
 * Dark and Light schemes available.
 */
data class PilcrowColorScheme(
    // Primary backgrounds and surfaces
    val primaryBackground: Color,
    val secondarySurface: Color,

    // Borders and separators
    val border: Color,
    val lightBorder: Color,

    // Text colors
    val primaryText: Color,
    val secondaryText: Color,
    val editorText: Color,
    val lineNumbers: Color,
    val gutterBg: Color,

    // Inline code
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBorder: Color,

    // Code blocks
    val codeBlockBg: Color,
    val codeBlockBorder: Color,

    // UI elements
    val toolbarBorder: Color,

    // Search highlight
    val searchHighlight: Color,
    val searchHighlightFocused: Color,

    // Brand accent
    val accent: Color,
    val onAccent: Color,

    // Status — transient save toast ("Saved" on accent, "Save failed" on error)
    val error: Color,
    val onError: Color,

    // Cream surface (button colors)
    val creamButton: Color,
    val onCreamButton: Color,

    // Scrim overlay
    val scrimOverlay: Color,
)

/**
 * Dark color scheme — exact copy of current PilcrowColors object values.
 * Default theme.
 */
val DarkColorScheme = PilcrowColorScheme(
    primaryBackground = Color(0xFF2C2C2B),
    secondarySurface = Color(0xFF313131),
    border = Color(0xFF4B4B4B),
    lightBorder = Color(0xFF525252),
    primaryText = Color(0xFFE4E1DC),
    secondaryText = Color(0xFFB4B4B4),
    editorText = Color(0xFFD6D6D6),
    lineNumbers = Color(0xFF7A7A7A),
    // Gutter sits a touch LIGHTER than the editor area (#2C2C2B) so the line-number column
    // reads as a distinct strip (matching the Sora demo look).
    gutterBg = Color(0xFF353534),
    inlineCodeBg = Color(0xFF3A3535),
    inlineCodeText = Color(0xFFE8A39A),
    inlineCodeBorder = Color(0xFF524949),
    codeBlockBg = Color(0xFF313131),
    codeBlockBorder = Color(0xFF4A4A4A),
    toolbarBorder = Color(0xFF3B3B3B),
    searchHighlight = Color(0xFF4A4327),
    searchHighlightFocused = Color(0xFF8A7322),
    accent = Color(0xFF8E7CD6),
    onAccent = Color(0xFF221C33), // dark text reads on the light-purple accent
    error = Color(0xFFC0564C), // warm red status surface
    onError = Color(0xFFFBEFEC), // near-white reads on the red surface
    creamButton = Color(0xFFE4E1DC),
    onCreamButton = Color(0xFF2C2C2B),
    scrimOverlay = Color.Black.copy(alpha = 0.32f),
)

/**
 * Light color scheme — Warm Cream palette.
 * Currently unused; Dark is default.
 */
val LightColorScheme = PilcrowColorScheme(
    primaryBackground = Color(0xFFF6EFE1),
    secondarySurface = Color(0xFFFBF6EC),
    border = Color(0xFFD9CFB9),
    lightBorder = Color(0xFFE2D9C5),
    primaryText = Color(0xFF33302A),
    secondaryText = Color(0xFF6E6555),
    editorText = Color(0xFF3A352C),
    lineNumbers = Color(0xFFA99F88),
    // Gutter a touch LIGHTER (toward white) than the cream editor area (#F6EFE1); the
    // current-line band (#EFE7D5 in md-light.json) stays distinct below it.
    gutterBg = Color(0xFFFCF8EF),
    inlineCodeBg = Color(0xFFECE1CE),
    inlineCodeText = Color(0xFFA8543F),
    inlineCodeBorder = Color(0xFFDECFB6),
    codeBlockBg = Color(0xFFEFE6D2),
    codeBlockBorder = Color(0xFFDDD0B6),
    toolbarBorder = Color(0xFFE0D6C2),
    searchHighlight = Color(0xFFEAD9A0),
    searchHighlightFocused = Color(0xFFE3B94E),
    accent = Color(0xFF6B5CA8),
    onAccent = Color(0xFFFBF6EC), // light text reads on the deeper light-theme accent
    error = Color(0xFFB23B30), // red status surface
    onError = Color(0xFFFBF6EC),
    creamButton = Color(0xFF2E2A24),
    onCreamButton = Color(0xFFF6EFE1),
    // No scrimOverlay is specified for light; use same as Dark (soft dark still reads on cream)
    // May need a visual pass if adjustment is needed.
    scrimOverlay = Color.Black.copy(alpha = 0.32f),
)

/**
 * Print color scheme — pure white page, near-black text, light-gray code panels.
 * Used for PDF export to reduce ink consumption and match printed readability.
 * Distinct from on-screen Dark and Light themes; white page is unsuitable for on-screen reading.
 */
val PrintColorScheme = PilcrowColorScheme(
    primaryBackground = Color(0xFFFFFFFF), // Pure white page
    secondarySurface = Color(0xFFFBFBFB), // Near-white surface (minimal difference)
    border = Color(0xFFE0E0E0), // Very light gray border
    lightBorder = Color(0xFFE8E8E8), // Slightly lighter gray
    primaryText = Color(0xFF1A1A1A), // Near-black text (not pure black to avoid harsh edges)
    secondaryText = Color(0xFF4A4A4A), // Medium-dark gray for secondary text
    editorText = Color(0xFF2A2A2A), // Dark gray for code content
    lineNumbers = Color(0xFF888888), // Medium gray for line numbers (subtle in print)
    gutterBg = Color(0xFFF5F5F5), // Very light gray gutter background
    inlineCodeBg = Color(0xFFF0F0F0), // Light gray for inline code background
    inlineCodeText = Color(0xFF8B4513), // Warm brown for inline code (print-safe, not pink)
    inlineCodeBorder = Color(0xFFD0D0D0), // Light gray border
    // Code blocks are syntax-highlighted with the fixed One Dark theme (PilcrowTheme is always
    // DarkColorScheme), whose token colors are only legible on a dark surface — so the print code
    // container must be dark too. Matches DarkColorScheme exactly so the container and the
    // markwon code-background span are seamless; on screen the same dark code block shows in every theme.
    codeBlockBg = Color(0xFF313131), // Dark code block background (matches the One Dark syntax theme)
    codeBlockBorder = Color(0xFF4A4A4A), // Dark code block border
    toolbarBorder = Color(0xFFE0E0E0), // Light gray toolbar border (hidden in PDF)
    searchHighlight = Color(0xFFFFFFCC), // Pale yellow highlight (not used in PDF)
    searchHighlightFocused = Color(0xFFFFDD00), // Bright yellow (not used in PDF)
    accent = Color(0xFF5A4A8A), // Muted purple (desaturated for print safety)
    onAccent = Color(0xFFFFFFFF), // save-toast tokens are on-screen only; set for completeness
    error = Color(0xFFB23B30),
    onError = Color(0xFFFFFFFF),
    creamButton = Color(0xFF2A2A2A), // Dark text on white for buttons
    onCreamButton = Color(0xFFFFFFFF), // White text on dark buttons
    scrimOverlay = Color.Black.copy(alpha = 0.32f), // Not used in PDF, for consistency
)

/**
 * CompositionLocal for theme colors. Provides the current active color scheme to all descendants.
 * Default is DarkColorScheme.
 * Updated by MainScreen's theme provider based on ViewModel.themeMode.
 */
val LocalMDColors = staticCompositionLocalOf { DarkColorScheme }

/**
 * Read-only accessor to the current color scheme.
 * Use this in Composables instead of hardcoding color hex.
 */
@Composable
@ReadOnlyComposable
fun mdColors(): PilcrowColorScheme = LocalMDColors.current

/**
 * One Dark syntax highlighting palette for Markdown source tokens.
 * Derived from One Dark theme (Atom).
 */
object EditorSyntaxColors {
    // Markdown source token colors (not for per-language code highlighting)
    val headers = Color(0xFFC678DD)
    val codeFenceContent = Color(0xFF98C379)
    val keywords = Color(0xFF61AFEF)
    val strings = Color(0xFF98C379)
    val numbers = Color(0xFFD19A66)
    val errors = Color(0xFFE06C75)
    val comments = Color(0xFFABB2BF)
}
