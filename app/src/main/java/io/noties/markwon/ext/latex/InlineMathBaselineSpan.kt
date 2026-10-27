// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package io.noties.markwon.ext.latex

import android.graphics.Canvas
import android.graphics.Paint
import io.noties.markwon.core.MarkwonTheme
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * An inline formula whose own baseline sits on the text baseline (S8-A42).
 *
 * ext-latex's inline span centres the formula image on the line box (`ALIGN_CENTER`) and reports
 * `ascent = -h/2`, `descent = h/2`, ignoring the formula's TeX depth, so a formula with a subscript
 * or a fraction sits off the text. This span reserves `h - depth` above the baseline and `depth`
 * below it, and draws with the image's baseline on the line's baseline.
 *
 * Lives in the library's package because `JLatextAsyncDrawable`, the type the parent constructor takes,
 * is package-private; nothing else of the library is touched.
 */
internal class InlineMathBaselineSpan private constructor(
    theme: MarkwonTheme,
    private val formula: JLatextAsyncDrawable,
    color: Int,
) : JLatexAsyncDrawableSpan(theme, formula, color) {
    constructor(theme: MarkwonTheme, plugin: JLatexAsyncDrawableSpan) : this(theme, plugin.drawable(), plugin.color())

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (!formula.hasResult()) return (paint.measureText(text, start, end) + HALF_PX).toInt()
        if (fm != null) {
            val depth = depth()
            fm.ascent = -(formula.bounds.bottom - depth)
            fm.descent = depth
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return formula.bounds.right
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        if (!formula.hasResult()) return super.draw(canvas, text, start, end, x, top, y, bottom, paint)
        // The parent centres the image between `top` and `bottom`; a box exactly as tall as the
        // image, hung from the baseline by its depth, puts the image's baseline on `y`.
        val depth = depth()
        super.draw(canvas, text, start, end, x, y + depth - formula.bounds.bottom, y, y + depth, paint)
    }

    /** The formula's TeX depth in pixels, scaled like the image if it was shrunk to fit the width. */
    private fun depth(): Int {
        val icon = (formula.result as? JLatexMathDrawable)?.icon() ?: return 0
        val height = icon.iconHeight
        return if (height <= 0) 0 else (icon.iconDepth * formula.bounds.height() / height.toFloat() + HALF_PX).toInt()
    }

    private companion object {
        const val HALF_PX = 0.5f
    }
}
