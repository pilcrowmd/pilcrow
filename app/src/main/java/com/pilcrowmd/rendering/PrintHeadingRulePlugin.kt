// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.utils.LeadingMarginUtils
import org.commonmark.node.Heading
import kotlin.math.roundToInt

/**
 * M-191: in the PDF export, the rule under a level-1 or level-2 heading runs to the right edge of
 * the text.
 *
 * Markwon's [HeadingSpan] ends the rule at the canvas width. On screen that is the text view's own
 * width, and the view clips the rule at its padding, so it spans the text. The export draws each
 * block on the page canvas, which is 595 units wide and scaled down from pixels to points, so the
 * rule stopped about a quarter of the way across. The layout's width is the text's width on any
 * canvas. Used by the export's instance only; the reader keeps Markwon's span.
 */
internal class PrintHeadingRulePlugin : AbstractMarkwonPlugin() {
    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(
            Heading::class.java,
            SpanFactory { configuration, props ->
                LayoutWidthHeadingSpan(configuration.theme(), CoreProps.HEADING_LEVEL.require(props))
            },
        )
    }
}

/** [HeadingSpan] whose rule ends at the layout's width. Size, weight and rule colour are Markwon's. */
internal class LayoutWidthHeadingSpan(private val theme: MarkwonTheme, level: Int) : HeadingSpan(theme, level) {
    private val rulePaint = Paint()
    private val rule = Rect()

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        if (level > LAST_RULED_LEVEL || !LeadingMarginUtils.selfEnd(end, text, this)) return
        rulePaint.set(p)
        theme.applyHeadingBreakStyle(rulePaint)
        val height = rulePaint.strokeWidth
        if (height <= 0f) return
        val ruleTop = (bottom - height).roundToInt()
        if (dir > 0) rule.set(x, ruleTop, layout.width, bottom) else rule.set(x - layout.width, ruleTop, x, bottom)
        c.drawRect(rule, rulePaint)
    }

    private companion object {
        /** Markwon rules levels 1 and 2 only. */
        const val LAST_RULED_LEVEL = 2
    }
}
