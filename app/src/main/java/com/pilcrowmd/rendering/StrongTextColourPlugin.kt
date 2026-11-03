// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.style.ForegroundColorSpan
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis

/**
 * Bold in [PilcrowColorScheme.strongText] (S8-A48). Markwon's bold is the platform's fake bold on the
 * regular font, which stays; this adds a colour on top, so nothing reflows. A scheme whose strong
 * colour equals its body colour (Light, Print) registers nothing, so it draws exactly as before.
 *
 * Bold inside a link (`[**x**](y)`) gets no colour, so the link colour stays: the platform applies a
 * paragraph's spans outermost first, and this colour, set on the inner bold, would paint over the
 * link's. A link inside bold (`**[x](y)**`) needs nothing, as its own colour is the inner one.
 * `StrongTextColourTest` covers both.
 *
 * Replaces CorePlugin's bold visitor (registered after it), with the same three calls plus the span.
 */
internal class StrongTextColourPlugin(private val colorScheme: PilcrowColorScheme) : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        if (colorScheme.strongText == colorScheme.primaryText) return
        val colour = colorScheme.strongText.toArgb()
        builder.on(StrongEmphasis::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            visitor.setSpansForNodeOptional(node, start)
            if (!node.isInsideLink()) visitor.setSpans(start, ForegroundColorSpan(colour))
        }
    }

    private fun Node.isInsideLink(): Boolean = generateSequence(parent) { it.parent }.any { it is Link }
}
