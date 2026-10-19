// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.internal.util.Html5Entities

/**
 * M-247: where each character of a decoded `Text` literal sits in the raw source. commonmark-java
 * keeps no source spans, and a literal is not always verbatim: `\:` is decoded to `:` and `&amp;` to
 * `&`. Search needs the true position of every character to tell a `:shortcode:` from an escaped
 * one in such a literal. Entities are decoded with commonmark's own table, so the two cannot differ.
 */
object SourceAlignment {

    /**
     * The raw offset of each character of [literal], plus one last entry for the offset just past it,
     * trying each start from [from] to [from] + [window]. Null when no start lines up, for example a
     * literal the parser changed in some other way.
     */
    fun align(content: String, literal: String, from: Int, window: Int): IntArray? =
        firstStart(content, from, window) { alignAt(content, literal, it) }

    /**
     * M-401: as [align], for an inline code span's [literal]. A code span decodes no escapes or
     * entities, but the parser turns each line break inside it into a space, so a space may stand for
     * `\n`, `\r\n` or `\r` in the source.
     */
    fun alignCodeSpan(content: String, literal: String, from: Int, window: Int): IntArray? =
        firstStart(content, from, window) { alignCodeSpanAt(content, literal, it) }

    private inline fun firstStart(content: String, from: Int, window: Int, attempt: (Int) -> IntArray?): IntArray? {
        val last = minOf(content.length - 1, from + window)
        for (start in from..last) {
            attempt(start)?.let { return it }
        }
        return null
    }

    private fun alignCodeSpanAt(content: String, literal: String, start: Int): IntArray? {
        val raws = IntArray(literal.length + 1)
        var j = start
        for (i in literal.indices) {
            raws[i] = j
            j += when {
                j >= content.length -> return null
                content[j] == literal[i] -> 1
                literal[i] != ' ' -> return null
                content.startsWith("\r\n", j) -> 2
                content[j] == '\n' || content[j] == '\r' -> 1
                else -> return null
            }
        }
        raws[literal.length] = j
        return raws
    }

    private fun alignAt(content: String, literal: String, start: Int): IntArray? {
        val raws = IntArray(literal.length + 1)
        var i = 0
        var j = start
        while (i < literal.length) {
            if (j >= content.length) return null
            val entity = if (content[j] == '&') ENTITY.matchAt(content, j)?.value else null
            val decoded = entity?.let(Html5Entities::entityToString)?.takeIf { it != entity }
            when {
                // `\x` for ASCII punctuation decodes to `x`.
                isEscape(content, j) && content[j + 1] == literal[i] -> {
                    raws[i++] = j + 1
                    j += 2
                }
                // A known entity decodes to one or two characters, all anchored on its `&`.
                decoded != null && entity != null && literal.startsWith(decoded, i) -> {
                    repeat(decoded.length) { raws[i++] = j }
                    j += entity.length
                }
                content[j] == literal[i] -> {
                    raws[i++] = j
                    j++
                }
                else -> return null
            }
        }
        raws[literal.length] = j
        return raws
    }

    private fun isEscape(content: String, j: Int): Boolean =
        content[j] == '\\' && j + 1 < content.length && content[j + 1] in ESCAPABLE

    private const val ESCAPABLE = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** commonmark's entity reference syntax (`Escaping.ENTITY`). */
    private val ENTITY = Regex("&(?:#x[a-f0-9]{1,6}|#[0-9]{1,7}|[a-z][a-z0-9]{1,31});", RegexOption.IGNORE_CASE)
}
