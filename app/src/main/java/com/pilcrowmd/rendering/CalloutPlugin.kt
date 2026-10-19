// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import com.pilcrowmd.domain.markdown.AdmonitionBlockParserFactory
import com.pilcrowmd.domain.markdown.AdmonitionFences
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.domain.markdown.EmojiShortcode
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * Paints a [CalloutBlock] (M-161): its title on the first line, then its content as usual. The
 * quote bar Markwon draws for a blockquote is never applied, because the node is no longer one.
 *
 * The title's colour depends on the screen scheme, which this shared visitor cannot know, so the
 * [CalloutTitleSpan] only marks it; the reader entry colours it at bind time (see `CalloutStyle`).
 */
class CalloutPlugin : AbstractMarkwonPlugin() {

    // Which `:::` openers of the document being parsed are closed. Set per parse, as FrontmatterPlugin
    // sets its gate: `processMarkdown` runs immediately before `parse` on the same thread.
    private val fences = ThreadLocal.withInitial { AdmonitionFences.scan("") }

    override fun processMarkdown(markdown: String): String {
        fences.set(AdmonitionFences.scan(markdown))
        return markdown // never mutate (Safeguard 2)
    }

    /**
     * M-251: `!!! type "Title"` and `::: type` admonitions are parsed into [CalloutBlock]s by a block
     * parser, because their indented bodies cannot be recovered after the fact. The search/TOC parity
     * parser registers the same factory (`ParseParityTest`).
     */
    override fun configureParser(builder: Parser.Builder) {
        builder.customBlockParserFactory(AdmonitionBlockParserFactory { fences.get()!! })
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(CalloutBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            visitor.builder().append(node.paintedTitle)
            visitor.setSpans(start, CalloutTitleSpan(node.kind))
            if (node.firstChild != null) {
                visitor.ensureNewLine()
                visitor.visitChildren(node)
            }
            visitor.blockEnd(node)
        }
    }
}

/**
 * M-227: a formula in a callout's title is painted as its source, `$…$`, because a title is plain text
 * and search, whose parser has no maths, finds exactly that text there. An emoji shortcode keeps its
 * `:name:` for the same reason (M-247; the search parser has no emoji either), where it would
 * otherwise drop out of the title. Passed to [com.pilcrowmd.domain.markdown.ReaderDocument.transform]
 * by the reader (`PrepareReaderModelUseCase`) and the PDF ([ReaderTree]). ext-latex keeps no record of
 * the delimiters, so an inline `$$…$$` is painted with single ones.
 */
internal fun calloutTitleSource(node: Node): String? = when (node) {
    is JLatexMathNode -> "\$" + node.latex() + "\$"
    is EmojiShortcode -> ":${node.alias}:"
    else -> null
}

/** A callout's title: the UI sans face, bold, slightly smaller than the body, in [color]. */
class CalloutTitleSpan(val kind: CalloutKind) : MetricAffectingSpan() {

    /** Set at bind time from the active scheme; 0 leaves the text colour alone. */
    var color: Int = 0

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
        if (color != 0) tp.color = color
    }

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(tp: TextPaint) {
        tp.typeface = TITLE_FACE
        tp.textSize *= TITLE_SCALE
    }

    private companion object {
        val TITLE_FACE: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        const val TITLE_SCALE = 0.88f
    }
}
