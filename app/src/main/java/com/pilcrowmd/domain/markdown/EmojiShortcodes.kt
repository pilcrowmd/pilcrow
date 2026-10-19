// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.CustomNode

/**
 * M-247: the emoji a `:alias:` names, or null when there is none to draw. An implementation already
 * leaves out every emoji the device cannot draw, so a name it returns null for stays literal on
 * screen, in the PDF and in search alike.
 */
fun interface EmojiTable {
    fun emojiFor(alias: String): String?
}

/** One `:alias:` in source that paints as [emoji]. [range] covers both colons. */
data class EmojiShortcodeMatch(val range: IntRange, val alias: String, val emoji: String)

/**
 * M-247: the `:shortcode:` rule, shared by the renderer's inline processor and the search model so
 * both replace exactly the same names. Pure Kotlin, no Android.
 *
 * A shortcode is `:`, a name of `[a-z0-9_+-]` (gemoji's alias spelling), then `:`, and it is replaced
 * only when [table] has the name. The opening `:` must not follow a letter or digit, so `10:30:45`
 * and `a:b:c` stay literal even though `30`-style and one-letter names exist, and it must not be
 * escaped (`\:`). Code spans, code blocks, link destinations, HTML and maths never reach the rule:
 * the renderer's other inline processors consume them first, and search applies it to prose text only.
 */
class EmojiShortcodes(private val table: EmojiTable) {

    /** The shortcode whose opening `:` is at [index] in [s], or null when that `:` opens none. */
    fun matchAt(s: CharSequence, index: Int): EmojiShortcodeMatch? {
        if (!opensAt(s, index)) return null
        var end = index + 1
        while (end < s.length && end - index <= MAX_ALIAS_LENGTH && isAliasChar(s[end])) end++
        val closed = end > index + 1 && end < s.length && s[end] == ':'
        val alias = if (closed) s.substring(index + 1, end) else null
        val emoji = alias?.let(table::emojiFor)
        return if (alias != null && emoji != null) EmojiShortcodeMatch(index..end, alias, emoji) else null
    }

    /** Every shortcode in [content], left to right and non-overlapping, as [matchAt] finds them. */
    fun ranges(content: String): List<EmojiShortcodeMatch> {
        val found = mutableListOf<EmojiShortcodeMatch>()
        var i = content.indexOf(':')
        while (i >= 0) {
            val match = matchAt(content, i)
            if (match != null) found.add(match)
            i = content.indexOf(':', if (match != null) match.range.last + 1 else i + 1)
        }
        return found
    }

    companion object {
        /** No table: every `:name:` stays literal. The default wherever no table is injected. */
        val NONE = EmojiShortcodes { null }

        /** Longer than any gemoji alias (40), so a long run of name characters stops scanning early. */
        const val MAX_ALIAS_LENGTH = 64

        fun isAliasChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '+' || c == '-'

        /** A `:` at [index] that is not escaped and does not follow a letter or digit. */
        private fun opensAt(s: CharSequence, index: Int): Boolean {
            val colon = index < s.length && s[index] == ':'
            val afterLetterOrDigit = index > 0 && s[index - 1].isLetterOrDigit()
            return colon && !afterLetterOrDigit && !isEscaped(s, index)
        }

        /** A `:` is escaped when an odd run of backslashes precedes it. */
        private fun isEscaped(s: CharSequence, index: Int): Boolean {
            var backslashes = 0
            var j = index - 1
            while (j >= 0 && s[j] == '\\') {
                backslashes++
                j--
            }
            return backslashes % 2 == 1
        }
    }
}

/**
 * M-247: a `:alias:` the reader paints as [emoji]. A node of its own, not a `Text`, so the inline
 * parser cannot merge it into the prose around it, and so a heading's anchor can still be built
 * from the name as written (`HeadingAnchors.plainText`).
 */
class EmojiShortcode(val alias: String, val emoji: String) : CustomNode()
