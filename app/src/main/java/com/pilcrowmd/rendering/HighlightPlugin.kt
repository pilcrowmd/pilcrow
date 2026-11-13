// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.style.BackgroundColorSpan
import com.pilcrowmd.domain.markdown.Highlight
import com.pilcrowmd.domain.markdown.HighlightDelimiterProcessor
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * S8-A88: `==text==` paints on [PilcrowColorScheme.highlight], the markers hidden. The parse half
 * is [HighlightDelimiterProcessor], which the search parser registers too; this hooks it into the
 * inline parser and paints the node's children on a background span.
 *
 * The span is applied while the node is visited, so the search highlight, added to the finished
 * text afterwards, lands on top of it: the search colour wins while searching. Text inside keeps
 * its own spans (bold, links, maths), because the children are visited first.
 */
internal class HighlightPlugin(private val colorScheme: PilcrowColorScheme) : AbstractMarkwonPlugin() {

    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(MarkwonInlineParserPlugin::class.java) {
            it.factoryBuilder().addDelimiterProcessor(HighlightDelimiterProcessor())
        }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        val colour = colorScheme.highlight.toArgb()
        builder.on(Highlight::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            visitor.setSpans(start, BackgroundColorSpan(colour))
        }
    }
}
