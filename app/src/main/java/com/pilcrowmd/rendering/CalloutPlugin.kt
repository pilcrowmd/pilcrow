// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.CalloutKind
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor

/**
 * Paints a [CalloutBlock] (M-161): its title on the first line, then its content as usual. The
 * quote bar Markwon draws for a blockquote is never applied, because the node is no longer one.
 *
 * The title's colour depends on the screen scheme, which this shared visitor cannot know, so the
 * [CalloutTitleSpan] only marks it; the reader entry colours it at bind time (see `CalloutStyle`).
 */
class CalloutPlugin : AbstractMarkwonPlugin() {

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(CalloutBlock::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            visitor.builder().append(node.kind.title)
            visitor.setSpans(start, CalloutTitleSpan(node.kind))
            if (node.firstChild != null) {
                visitor.ensureNewLine()
                visitor.visitChildren(node)
            }
            visitor.blockEnd(node)
        }
    }
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
