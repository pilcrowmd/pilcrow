// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The reader's marker size relative to the surrounding text (M-76; print keeps [FOOTNOTE_MARKER_SCALE]). */
const val FOOTNOTE_MARKER_BOX_SCALE: Float = 0.78f

/** How far the digits sit above the baseline, in ems of the marker's own size. */
private const val RAISE_EM = 0.25f

/** The box behind the digits is the marker colour at this alpha, as a callout's tint is (M-76). */
private const val FILL_ALPHA = 0.22f

private const val PADDING_H_DP = 6f
private const val PADDING_V_DP = 1f
private const val MARGIN_DP = 2f
private const val CORNER_DP = 6f

/** Semi-bold where the platform can ask for a weight (API 28), bold before that. */
private const val SEMI_BOLD = 600

/**
 * The reader's footnote marker (M-76): the ordinal digits, smaller and raised a little, in a rounded
 * box filled with the marker colour at [FILL_ALPHA]. The PDF keeps its superscript digits: the plugin
 * builds this span only for the reader's Markwon instances.
 *
 * The digits stay the underlying characters, so search's model of the marker (exactly the ordinal)
 * still holds. Two things a replacement span would otherwise lose are read back from the text here:
 * - the COLOUR. `TextLine` applies only metric-affecting spans to a replacement's paint, so the
 *   `ForegroundColorSpan` that `tintFootnoteMarkers` puts over the marker never reaches it.
 * - the SEARCH HIGHLIGHT. A `BackgroundColorSpan` is not drawn under a replacement either, so a
 *   match on the digits would be invisible. It is drawn here, behind the box.
 *
 * [getSize] reports the SURROUNDING text's font metrics, as a replacement span must (a line holding
 * only the marker would otherwise have no height), and never the box's: the box must never make its
 * line taller, or every line holding a marker would stand out from its paragraph. The box is drawn
 * inside the line the text already has, never given extra room.
 */
class FootnoteMarkerSpan(private val density: Float) : ReplacementSpan() {

    private val digits = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    private fun digitsPaint(paint: Paint): TextPaint = digits.apply {
        set(paint)
        textSize = paint.textSize * FOOTNOTE_MARKER_BOX_SCALE
        typeface = semiBoldSans
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        val inset = (PADDING_H_DP + MARGIN_DP) * density
        return ceil(digitsPaint(paint).measureText(text, start, end) + 2 * inset).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = getSize(paint, text, start, end, null)
        val spanned = text as? Spanned
        // Any overlap counts: a one-digit query lights a two-digit marker too, rather than vanishing.
        spanned?.getSpans(start, end, BackgroundColorSpan::class.java)?.lastOrNull()?.let {
            fill.color = it.backgroundColor
            canvas.drawRect(x, top.toFloat(), x + width, bottom.toFloat(), fill)
        }
        val textPaint = digitsPaint(paint)
        textPaint.color = spanned?.spanOver<ForegroundColorSpan>(start, end)?.foregroundColor ?: paint.color
        val baseline = y - RAISE_EM * textPaint.textSize
        val metrics = textPaint.fontMetrics
        val margin = MARGIN_DP * density
        val padV = PADDING_V_DP * density
        box.set(x + margin, baseline + metrics.ascent - padV, x + width - margin, baseline + metrics.descent + padV)
        fill.color = ColorUtils.setAlphaComponent(textPaint.color, (FILL_ALPHA * ALPHA_MAX).roundToInt())
        val corner = CORNER_DP * density
        canvas.drawRoundRect(box, corner, corner, fill)
        canvas.drawText(text, start, end, x + margin + PADDING_H_DP * density, baseline, textPaint)
    }

    private companion object {
        const val ALPHA_MAX = 255

        val semiBoldSans: Typeface by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.SANS_SERIF, SEMI_BOLD, false)
            } else {
                Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }
    }
}

/** The last span of type [T] covering all of [start, end), as `tintFootnoteMarkers` lays its colour. */
private inline fun <reified T> Spanned.spanOver(start: Int, end: Int): T? =
    getSpans(start, end, T::class.java).lastOrNull { getSpanStart(it) <= start && getSpanEnd(it) >= end }
