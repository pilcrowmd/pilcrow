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

    // Reader code-token colours for fenced code (M-135). One set per scheme so light-theme code
    // stays readable on its own background.
    val codeSyntax: CodeSyntaxColors,

    // Footnote markers and back-arrows (M-96). Separate from `accent`, which is a fill colour.
    val footnoteMarker: Color,
)

/**
 * Reader syntax-highlighting colours for fenced code, per token role.
 *
 * The first six roles are the original map. The rest were added for M-132/M-133 (Markdown, `git`/diff,
 * CSS and friends) and are nullable: a null role leaves its tokens in the default code text colour,
 * exactly as they rendered before those roles existed. Print leaves them all null, which is how the
 * exported PDF stays unchanged.
 */
data class CodeSyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val error: Color,
    val function: Color,
    /** Markdown heading text (`title`). */
    val heading: Color? = null,
    /** Markdown `bold` and `italic`: a colour only, the code font stays regular. */
    val emphasis: Color? = null,
    /** `url` and `url-reference`. */
    val link: Color? = null,
    /** Structural markers: Markdown `list`, `hr`, `blockquote`; a diff's `@@` hunk header (`coord`). */
    val marker: Color? = null,
    /** Literal-ish tokens with no role of their own: Markdown `code`, `regex`, `symbol`, `commit_sha1`. */
    val literal: Color? = null,
    /** `variable`, `property`, `entity`. */
    val variable: Color? = null,
    /** `builtin`, `namespace`, CSS `selector` and `atrule`, a git `command` line. */
    val builtin: Color? = null,
    /** A diff's added line (`inserted`). */
    val inserted: Color? = null,
    /** A diff's removed line (`deleted`). */
    val deleted: Color? = null,
)

/**
 * One Dark code tokens for the Dark theme. The added roles take the One Dark hues the editor's
 * `md-dark.json` already uses for the same Markdown constructs.
 */
val OneDarkCodeSyntax = CodeSyntaxColors(
    keyword = Color(0xFF61AFEF),
    string = Color(0xFF98C379),
    number = Color(0xFFD19A66),
    comment = Color(0xFFABB2BF),
    error = Color(0xFFE06C75),
    function = Color(0xFFC678DD),
    heading = Color(0xFF61AFEF),
    emphasis = Color(0xFFE5C07B),
    link = Color(0xFF98C379),
    marker = Color(0xFF56B6C2),
    literal = Color(0xFFD19A66),
    variable = Color(0xFFE06C75),
    builtin = Color(0xFF56B6C2),
    inserted = Color(0xFF98C379),
    deleted = Color(0xFFE06C75),
)

/**
 * Print code tokens: the six original One Dark roles and nothing else. The added roles stay null, so
 * the exported PDF colours exactly the tokens it always did (M-132 must not change the export).
 */
val PrintCodeSyntax = CodeSyntaxColors(
    keyword = Color(0xFF61AFEF),
    string = Color(0xFF98C379),
    number = Color(0xFFD19A66),
    comment = Color(0xFFABB2BF),
    error = Color(0xFFE06C75),
    function = Color(0xFFC678DD),
)

/**
 * Light-theme code tokens: the editor's `md-light.json` hues, darkened just enough to reach 4.5:1
 * on the light code-block background. Every role is held to that by `CodeSyntaxContrastTest`.
 */
val LightCodeSyntax = CodeSyntaxColors(
    keyword = Color(0xFF2A64A0),
    string = Color(0xFF3F6B2F),
    number = Color(0xFF9A4A36),
    comment = Color(0xFF6A5E4C),
    error = Color(0xFFA8352B),
    function = Color(0xFF7E449F),
    heading = Color(0xFF8049A2),
    emphasis = Color(0xFF29649E),
    link = Color(0xFF446A33),
    marker = Color(0xFF39696E),
    literal = Color(0xFF974C39),
    variable = Color(0xFFA33E62),
    builtin = Color(0xFF256C62),
    inserted = Color(0xFF3F6B2F),
    deleted = Color(0xFFA8352B),
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
    // Inline code shares the code-block surface in Dark (unchanged look).
    inlineCodeBg = Color(0xFF313131),
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
    codeSyntax = OneDarkCodeSyntax,
    footnoteMarker = Color(0xFF8E7CD6), // same as accent: it already reads in Dark
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
    // Inline code gets its own chip in Light, darker than both the page and the code block.
    inlineCodeBg = Color(0xFFE2D3B5),
    inlineCodeText = Color(0xFFA8543F),
    inlineCodeBorder = Color(0xFFDECFB6),
    codeBlockBg = Color(0xFFE9DEC6),
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
    codeSyntax = LightCodeSyntax,
    footnoteMarker = Color(0xFF5B3FC4), // more saturated than accent, so a small raised digit is findable
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
    codeSyntax = PrintCodeSyntax, // the PDF's code tokens stay as they were; the export must not change
    footnoteMarker = Color(0xFF5A4A8A), // same as the print accent: the export must not change
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
 * Opacity of an action that is on screen but cannot be used right now (M-117). Applied to the whole
 * control, so fill, border, icon and label dim together and every colour is still a token of the
 * active scheme. Same value as the transient banner's disabled state in `MainScreen`.
 */
const val DISABLED_ACTION_ALPHA = 0.5f
