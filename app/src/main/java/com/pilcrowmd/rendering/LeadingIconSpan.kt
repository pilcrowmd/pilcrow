// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan

/**
 * Draws [icon] in the leading margin of the first line the span covers, and indents every line it
 * covers by the icon's width — a hanging indent, so a wrapped `<details>` summary lines up after its
 * chevron (M-161: a callout's type icon, a `<details>` chevron).
 *
 * A margin, not an `ImageSpan`, on purpose: an image span adds an object character to the text, and
 * search models each block's painted text character for character. A margin adds none.
 */
class LeadingIconSpan(private val icon: Drawable, private val sizePx: Int, private val gapPx: Int) :
    LeadingMarginSpan {

    override fun getLeadingMargin(first: Boolean): Int = sizePx + gapPx

    @Suppress("LongParameterList") // The framework's signature.
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
        layout: Layout?,
    ) {
        if (!first || (text as? Spanned)?.getSpanStart(this) != start) return
        // Centre on the line's text, not its box: the box carries the line spacing below the text.
        val fm = p.fontMetricsInt
        val centre = baseline + (fm.ascent + fm.descent) / 2
        val left = if (dir > 0) x else x - sizePx
        icon.setBounds(left, centre - sizePx / 2, left + sizePx, centre + sizePx / 2)
        icon.draw(c)
    }
}
