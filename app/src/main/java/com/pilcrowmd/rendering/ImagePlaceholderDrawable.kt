// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import kotlin.math.ceil

/**
 * M-93: what the reader draws where a markdown image cannot be shown — a missing or unreadable file,
 * a corrupt or unsupported one, a remote URL, a path it has no access to. One neutral look for all of
 * them: a code-panel box with a picture glyph and the image's alt text ([LABEL_NO_ALT] when it has
 * none). Colours come from the scheme's tokens only (Safeguard 4).
 *
 * Its intrinsic size is the box that fits the label, never wider than [maxWidthPx]; a longer alt
 * text is ellipsized. With [actionLabel] (a picture a folder grant would show), the label ends in
 * " · Tap to show" in the accent colour; the alt text gives way to it first, and it is cut only
 * when it alone is wider than the box.
 *
 * The label, the glyph beside it and the box's height are in sp, converted the way the reader's body
 * text is, so they follow Android's font size with the text around them; padding, gaps and lines stay
 * in dp. The same drawable stands in for every picture that cannot be shown, remote ones included.
 */
internal class ImagePlaceholderDrawable(
    altText: String,
    colorScheme: PilcrowColorScheme,
    private val metrics: DisplayMetrics,
    scale: Float,
    maxWidthPx: Int,
    /** " · Tap to show" after the alt text, in the accent colour; null for none. */
    val actionLabel: String? = null,
) : Drawable() {

    private val density = metrics.density

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorScheme.codeBlockBg.toArgb() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = colorScheme.codeBlockBorder.toArgb()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GLYPH_STROKE_DP * density
        strokeJoin = Paint.Join.ROUND
        color = colorScheme.secondaryText.toArgb()
    }

    /** The label's text size in px: [LABEL_SP] at the reading size, as body text converts it. */
    internal val labelSizePx = sp(LABEL_SP * scale)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = labelSizePx
        color = colorScheme.secondaryText.toArgb()
    }
    private val actionPaint = TextPaint(textPaint).apply { color = colorScheme.accent.toArgb() }
    private val action = actionLabel?.let { "$ACTION_SEPARATOR$it" }.orEmpty()

    private val padding = PADDING_DP * density
    private val glyphSize = sp(GLYPH_SP)
    private val gap = GAP_DP * density
    private val height = sp(HEIGHT_SP * scale).toInt()

    /** The label as drawn: the alt text, ellipsized to fit beside the glyph. */
    val label: String
    private val width: Int

    // Worked out when the bounds change, not on every draw: the reader redraws while it scrolls.
    private val box = RectF()
    private var drawnText = "" to ""
    private val glyphFrame = RectF()
    private val glyphMountain = Path()
    private var glyphSunX = 0f
    private var glyphSunY = 0f

    init {
        val textRoom = (maxWidthPx - 2 * padding - glyphSize - gap).coerceAtLeast(0f)
        val fittedAction = fit(action, actionPaint, textRoom)
        val room = (textRoom - actionPaint.measureText(fittedAction)).coerceAtLeast(0f)
        val full = altText.ifBlank { LABEL_NO_ALT }
        label = fit(full, textPaint, room)
        val textWidth = textPaint.measureText(label) + actionPaint.measureText(fittedAction)
        // Rounded up: rounding down leaves draw() a fraction of a pixel short and cuts the label.
        width = ceil(2 * padding + glyphSize + gap + textWidth).toInt().coerceAtMost(maxWidthPx)
        setBounds(0, 0, width, height)
    }

    override fun getIntrinsicWidth(): Int = width
    override fun getIntrinsicHeight(): Int = height

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds)
        box.inset(density / 2, density / 2)
        drawnText = textFor(bounds.width())
        layoutGlyph(bounds.left + padding, bounds.exactCenterY() - glyphSize / 2)
    }

    override fun draw(canvas: Canvas) {
        val radius = CORNER_DP * density
        canvas.drawRoundRect(box, radius, radius, fill)
        canvas.drawRoundRect(box, radius, radius, stroke)
        val corner = glyphSize * GLYPH_CORNER
        canvas.drawRoundRect(glyphFrame, corner, corner, glyph)
        canvas.drawPath(glyphMountain, glyph)
        canvas.drawCircle(glyphSunX, glyphSunY, glyphSize * GLYPH_SUN[2], glyph)
        val (text, fittedAction) = drawnText
        val baseline = bounds.exactCenterY() - (textPaint.ascent() + textPaint.descent()) / 2
        val textLeft = bounds.left + padding + glyphSize + gap
        canvas.drawText(text, textLeft, baseline, textPaint)
        if (fittedAction.isNotEmpty()) {
            canvas.drawText(fittedAction, textLeft + textPaint.measureText(text), baseline, actionPaint)
        }
    }

    /**
     * The label and the action as drawn in a box [boxWidth] wide. Markwon narrows the box to a
     * narrower text column; the text follows the box it is given.
     */
    internal fun textFor(boxWidth: Int): Pair<String, String> {
        val textRoom = (boxWidth - 2 * padding - glyphSize - gap).coerceAtLeast(0f)
        val fittedAction = fit(action, actionPaint, textRoom)
        val room = (textRoom - actionPaint.measureText(fittedAction)).coerceAtLeast(0f)
        return fit(label, textPaint, room) to fittedAction
    }

    /** A framed picture: a mountain and a sun, laid out from the GLYPH_ fractions below. */
    private fun layoutGlyph(left: Float, top: Float) {
        fun x(f: Float) = left + glyphSize * f
        fun y(f: Float) = top + glyphSize * f
        glyphFrame.set(left, y(GLYPH_FRAME_TOP), left + glyphSize, y(GLYPH_FRAME_BOTTOM))
        glyphMountain.reset()
        glyphMountain.moveTo(x(GLYPH_MOUNTAIN[0]), y(GLYPH_MOUNTAIN[1]))
        for (i in 2 until GLYPH_MOUNTAIN.size step 2) {
            glyphMountain.lineTo(x(GLYPH_MOUNTAIN[i]), y(GLYPH_MOUNTAIN[i + 1]))
        }
        glyphSunX = x(GLYPH_SUN[0])
        glyphSunY = y(GLYPH_SUN[1])
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
        glyph.alpha = alpha
        textPaint.alpha = alpha
        actionPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        listOf(fill, stroke, glyph, textPaint, actionPaint).forEach { it.colorFilter = colorFilter }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    internal companion object {
        /** Shown for an image written with no alt text, e.g. `![](photo.png)`. */
        const val LABEL_NO_ALT = "Image"

        /** Ends the label of a picture a folder grant would show. */
        const val TAP_TO_SHOW = "Tap to show"

        private const val ACTION_SEPARATOR = " · "

        private const val HEIGHT_SP = 36f
        private const val PADDING_DP = 12f
        private const val GLYPH_SP = 16f
        private const val GLYPH_STROKE_DP = 1.5f
        private const val GAP_DP = 8f
        private const val CORNER_DP = 6f
        internal const val LABEL_SP = 14f

        // The glyph in fractions of its size: the frame's top, bottom and corner (it spans the full
        // width), the mountain's points (x, y pairs) and the sun (x, y, radius).
        private const val GLYPH_FRAME_TOP = 0.12f
        private const val GLYPH_FRAME_BOTTOM = 0.88f
        private const val GLYPH_CORNER = 0.1f
        private val GLYPH_MOUNTAIN = floatArrayOf(0.12f, 0.76f, 0.42f, 0.44f, 0.62f, 0.64f, 0.74f, 0.54f, 0.88f, 0.76f)
        private val GLYPH_SUN = floatArrayOf(0.7f, 0.32f, 0.07f)
    }
}

/** [text] ellipsized at the end to fit [room] px in [paint]. */
private fun fit(text: String, paint: TextPaint, room: Float): String =
    TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()
