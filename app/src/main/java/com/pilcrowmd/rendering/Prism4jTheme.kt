// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import androidx.compose.ui.graphics.Color
import com.pilcrowmd.ui.theme.CodeSyntaxColors
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.prism4j.Prism4j

/**
 * Custom Prism4j theme for Pilcrow.
 * Implements Prism4jTheme and applies per-language syntax highlighting to fenced code blocks.
 * Every colour comes from [colorScheme]: the code-block background, the default text and the
 * token colours ([PilcrowColorScheme.codeSyntax]). [MarkwonRenderer] builds one instance per
 * theme, because a Markwon instance bakes its theme in when it is built.
 */
class PilcrowTheme(private val colorScheme: PilcrowColorScheme = DarkColorScheme) : Prism4jTheme {

    override fun background(): Int {
        return colorScheme.codeBlockBg.toArgb()
    }

    override fun textColor(): Int {
        return colorScheme.editorText.toArgb()
    }

    /**
     * Maps Prism4j syntax token types to the scheme's code-token colours.
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

        // M-216: a diff's added or removed line also gets a full-line tint, when the scheme sets one.
        val lineBg = when (syntax.type()) {
            "inserted" -> colorScheme.codeSyntax.insertedLineBg
            "deleted" -> colorScheme.codeSyntax.deletedLineBg
            else -> null
        }
        if (lineBg != null) {
            builder.setSpan(
                DiffLineBackgroundSpan(lineBg.toArgb()),
                start,
                end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * Map a Prism4j token type to its colour in the scheme's [PilcrowColorScheme.codeSyntax].
     * Punctuation, every unmapped token and every unset role take the default code text colour.
     */
    private fun mapTokenTypeToColor(tokenType: String): Int =
        (TOKEN_ROLES[tokenType]?.invoke(colorScheme.codeSyntax) ?: colorScheme.editorText).toArgb()

    private companion object {
        /**
         * Token type → role. Tokens added for M-132/M-133 map ONLY to the added, nullable roles, never to
         * one of the original six, so a scheme that leaves those roles null colours exactly the
         * tokens it did before.
         */
        val TOKEN_ROLES: Map<String, (CodeSyntaxColors) -> Color?> = buildMap {
            fun role(color: (CodeSyntaxColors) -> Color?, vararg tokens: String) = tokens.forEach { put(it, color) }
            role(CodeSyntaxColors::keyword, "keyword", "boolean", "operator")
            role(CodeSyntaxColors::string, "string", "char")
            role(CodeSyntaxColors::number, "number", "constant")
            role(CodeSyntaxColors::comment, "comment")
            role(CodeSyntaxColors::error, "error", "invalid")
            role(CodeSyntaxColors::function, "function", "class-name", "attr-name", "tag")
            role(CodeSyntaxColors::heading, "title")
            role(CodeSyntaxColors::emphasis, "bold", "italic")
            role(CodeSyntaxColors::link, "url", "url-reference")
            role(CodeSyntaxColors::marker, "list", "hr", "blockquote", "coord")
            role(CodeSyntaxColors::literal, "code", "regex", "symbol", "commit_sha1")
            role(CodeSyntaxColors::variable, "variable", "property", "entity")
            role(CodeSyntaxColors::builtin, "builtin", "namespace", "selector", "atrule", "command")
            role(CodeSyntaxColors::inserted, "inserted")
            role(CodeSyntaxColors::deleted, "deleted")
        }
    }
}

/**
 * Fills each line it covers, from the layout's left edge to its right edge, with an opaque [color]
 * (M-216). The text is untouched.
 *
 * A leading-margin span of width 0, not a `LineBackgroundSpan`: Markwon's code-block span repaints the
 * block's whole background in the text pass, after the layout's line-background pass, so a line
 * background is painted over. Leading margins are drawn in span order and this one comes after the
 * code-block span, so it lands on top of that fill and under the glyphs.
 */
internal class DiffLineBackgroundSpan(color: Int) : android.text.style.LeadingMarginSpan {
    private val fill = android.graphics.Paint().apply { this.color = color }

    override fun getLeadingMargin(first: Boolean): Int = 0

    override fun drawLeadingMargin(
        canvas: android.graphics.Canvas,
        paint: android.graphics.Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: android.text.Layout?,
    ) {
        canvas.drawRect(0f, top.toFloat(), (layout?.width ?: canvas.width).toFloat(), bottom.toFloat(), fill)
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
