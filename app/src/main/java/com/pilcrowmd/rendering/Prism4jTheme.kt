// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import androidx.compose.ui.graphics.Color
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.EditorSyntaxColors
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.prism4j.Prism4j

/**
 * Custom Prism4j theme for Pilcrow.
 * Implements Prism4jTheme and applies per-language syntax highlighting to fenced code blocks.
 * Color scheme is passed in and reads from the active theme (Dark or Light).
 */
class PilcrowTheme(private val colorScheme: PilcrowColorScheme = DarkColorScheme) : Prism4jTheme {

    override fun background(): Int {
        return colorScheme.codeBlockBg.toArgb()
    }

    override fun textColor(): Int {
        return colorScheme.editorText.toArgb()
    }

    /**
     * Maps Prism4j syntax token types to One Dark colors.
     * Called for each syntax token in a code block.
     * Sets the foreground color for the token span.
     */
    override fun apply(
        language: String,
        syntax: Prism4j.Syntax,
        builder: android.text.SpannableStringBuilder,
        start: Int,
        end: Int,
    ) {
        val color = mapTokenTypeToColor(syntax.type())

        // Apply foreground color span to this token
        builder.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * Map Prism4j syntax token type to One Dark color.
     * Token types from Prism4j: keyword, string, number, punctuation, comment, etc.
     */
    private fun mapTokenTypeToColor(tokenType: String): Int {
        return when (tokenType) {
            // Keywords (blue in One Dark)
            "keyword" -> EditorSyntaxColors.keywords.toArgb()
            "boolean" -> EditorSyntaxColors.keywords.toArgb()
            "operator" -> EditorSyntaxColors.keywords.toArgb()

            // Strings (green in One Dark)
            "string" -> EditorSyntaxColors.strings.toArgb()
            "char" -> EditorSyntaxColors.strings.toArgb()

            // Numbers (orange in One Dark)
            "number" -> EditorSyntaxColors.numbers.toArgb()
            "constant" -> EditorSyntaxColors.numbers.toArgb()

            // Comments (gray in One Dark)
            "comment" -> EditorSyntaxColors.comments.toArgb()

            // Errors/important (red in One Dark)
            "error" -> EditorSyntaxColors.errors.toArgb()
            "invalid" -> EditorSyntaxColors.errors.toArgb()

            // Function, class, attribute names (magenta in One Dark)
            "function" -> EditorSyntaxColors.headers.toArgb()
            "class-name" -> EditorSyntaxColors.headers.toArgb()
            "attr-name" -> EditorSyntaxColors.headers.toArgb()
            "tag" -> EditorSyntaxColors.headers.toArgb()

            // Punctuation and default (primary text color)
            "punctuation" -> colorScheme.editorText.toArgb()
            else -> colorScheme.editorText.toArgb()
        }
    }
}

/**
 * Convert Compose Color to Android ARGB int.
 * Extension function for convenience.
 */
fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (this.alpha * 255).toInt(),
        (this.red * 255).toInt(),
        (this.green * 255).toInt(),
        (this.blue * 255).toInt(),
    )
}
