// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R

/**
 * Bundled OFL fonts for Pilcrow.
 * Font files in res/font/:
 * - source_serif_4_regular.ttf (weight 400, OFL) ✓ Bundled
 * - source_serif_4_bold.ttf (weight 700, OFL) ✓ Bundled
 * - jetbrains_mono_regular.ttf (weight 400, OFL) ✓ Bundled
 * - jetbrains_mono_bold.ttf (weight 700, OFL) ✓ Bundled
 */
val sourceSerif4Family = FontFamily(
    Font(R.font.source_serif_4_regular, FontWeight.Normal),
    Font(R.font.source_serif_4_bold, FontWeight.Bold),
)

val jetbrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * Preview line-height multiplier applied to the Markwon TextView.
 * The reference design specified 1.7, but on a single TextView that multiplier also inflates wrapped
 * heading leading and inter-paragraph gaps. Adjusted to 1.35 after visual
 * review (≈halves the extra leading). Tune here.
 */
const val PreviewLineHeightMultiplier = 1.35f

/**
 * Pilcrow Type Scale — Mobile.
 * All sizes in sp (scale-independent pixels). Font scaling control will multiply these.
 */
object PilcrowTypography {
    /**
     * DEAD CODE PRESERVED:
     * The TextStyle tokens below (editorStyle, bodyStyle, codeBlockStyle) are not currently
     * consumed by the content rendering paths (block entries) or the editor. They were part
     * of an earlier Compose-native design token approach; the render paths now use raw Float
     * SP constants (EditorBaseFontSizeSp, ProseBodyFontSizeSp, etc.) to directly set Markwon
     * and Sora font sizes. These TextStyle tokens are preserved as-is
     * (do not delete pre-existing dead code). If the design system shifts back to Compose
     * TextStyle-driven rendering, these will be re-activated. For now, they are harmless and
     * kept for historical reference.
     */

    // Body text — 17sp, weight 400, line height 1.7
    val bodyStyle = TextStyle(
        fontFamily = sourceSerif4Family,
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.7.em,
    )

    // H1 — 29sp, weight 700, line height 1.2
    val h1Style = TextStyle(
        fontFamily = sourceSerif4Family,
        fontSize = 29.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 1.2.em,
    )

    // H2 — 23sp, weight 700, line height 1.2
    val h2Style = TextStyle(
        fontFamily = sourceSerif4Family,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 1.2.em,
    )

    // H3 — 19sp, weight 700, line height 1.2
    val h3Style = TextStyle(
        fontFamily = sourceSerif4Family,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 1.2.em,
    )

    // Code block — 14sp, weight 400, line height 1.7
    val codeBlockStyle = TextStyle(
        fontFamily = jetbrainsMonoFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.7.em,
    )

    // Editor source — 14sp, weight 400, line height 1.75
    val editorStyle = TextStyle(
        fontFamily = jetbrainsMonoFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 1.75.em,
    )

    // Inline code — 0.9× of surrounding (relative sizing)
    val inlineCodeStyle = TextStyle(
        fontFamily = sourceSerif4Family,
        fontSize = 15.3.sp, // 0.9 × 17sp
        fontWeight = FontWeight.Normal,
    )

    // ===== Raw Float SP Constants (for block-entry Markwon rendering) =====
    // These are NOT Compose TextStyle objects. Block-entry renderers call
    // setTextSize(TypedValue.COMPLEX_UNIT_SP, Float) (Markwon) and Sora's
    // setTextSize(Float), which take a float SP value, not a TextStyle.
    // Each token below supplies the base font size in SP; consumers multiply
    // by fontScale or density as appropriate.
    // Declared `const val` so the compiler inlines the value at each call site —
    // matches the "constant token" intent.

    /**
     * Editor base font size (Sora CodeEditor).
     * Base value: 13sp (smaller than the 14sp design baseline).
     * Consumed: Editor.kt line 83, with fontScale multiplier.
     */
    const val EDITOR_BASE_FONT_SIZE_SP: Float = 13f

    /**
     * Prose body font size for preview rendering.
     * Base value: 17sp.
     * Consumed: ProseBlockEntry.kt line 42, with fontScale multiplier.
     * Also consumed: MarkwonRenderer.kt line 54, with density multiplier.
     */
    const val PROSE_BODY_FONT_SIZE_SP: Float = 17f

    /**
     * Code block font size (fenced code, frontmatter, LaTeX blocks).
     * Base value: 14sp.
     * Consumed: FencedCodeBlockEntry.kt lines 72, 161 (with fontScale multiplier).
     * Consumed: FrontmatterBlockEntry.kt line 71 (with fontScale multiplier).
     * Consumed: LatexBlockEntry.kt line 70 (WITHOUT fontScale — preserved exact).
     */
    const val CODE_BLOCK_FONT_SIZE_SP: Float = 14f

    /**
     * Table cell font size.
     * Base value: 15sp.
     * Consumed: TableBlockEntry.kt line 136, with fontScale multiplier.
     */
    const val TABLE_FONT_SIZE_SP: Float = 15f
}
