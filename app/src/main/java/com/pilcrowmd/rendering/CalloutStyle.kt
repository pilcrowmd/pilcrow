// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.ui.theme.CALLOUT_TINT_ALPHA
import com.pilcrowmd.ui.theme.PilcrowColorScheme

/**
 * How a callout block looks (M-161): a faint tint of its type's colour, a bar of that colour on the
 * leading edge, and the title and icon in it. Every colour comes from the token layer (Safeguard 4).
 */
internal object CalloutStyle {

    fun color(kind: CalloutKind, scheme: PilcrowColorScheme): Color = when (kind) {
        CalloutKind.NOTE -> scheme.callouts.note
        CalloutKind.TIP -> scheme.callouts.tip
        CalloutKind.IMPORTANT -> scheme.callouts.important
        CalloutKind.WARNING -> scheme.callouts.warning
        CalloutKind.CAUTION -> scheme.callouts.caution
    }

    @DrawableRes
    private fun icon(kind: CalloutKind): Int = when (kind) {
        CalloutKind.NOTE -> R.drawable.ic_callout_note
        CalloutKind.TIP -> R.drawable.ic_callout_tip
        CalloutKind.IMPORTANT -> R.drawable.ic_callout_important
        CalloutKind.WARNING -> R.drawable.ic_callout_warning
        CalloutKind.CAUTION -> R.drawable.ic_callout_caution
    }

    /** Colour each title in [text] and put its type icon in front of it. Returns [text]. */
    fun decorateTitles(context: Context, text: Spanned, scheme: PilcrowColorScheme, fontScale: Float): Spanned {
        val spannable = text as? Spannable ?: return text
        for (title in spannable.getSpans(0, spannable.length, CalloutTitleSpan::class.java)) {
            val color = color(title.kind, scheme).toArgb()
            title.color = color
            val icon = ContextCompat.getDrawable(context, icon(title.kind))?.mutate() ?: continue
            icon.setTint(color)
            spannable.setSpan(
                LeadingIconSpan(icon, dp(context, ICON_DP * fontScale), dp(context, ICON_GAP_DP * fontScale)),
                spannable.getSpanStart(title),
                spannable.getSpanEnd(title),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return text
    }

    /**
     * Give [view] the callout box in [color]: page margins kept outside it, the text inset inside
     * it. [top] and [bottom] are the block's usual vertical padding, which the box sits within.
     */
    fun applyBox(view: TextView, color: Color, pageMarginPx: Int, top: Int, bottom: Int) {
        val context = view.context
        val radius = dp(context, CORNER_DP).toFloat()
        val tint = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color.copy(alpha = CALLOUT_TINT_ALPHA).toArgb())
        }
        val bar = GradientDrawable().apply {
            cornerRadii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            setColor(color.toArgb())
        }
        val box = LayerDrawable(arrayOf<Drawable>(tint, bar)).apply {
            setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            setLayerWidth(1, dp(context, BAR_DP))
        }
        view.background = InsetDrawable(box, pageMarginPx, top, pageMarginPx, bottom)
        val inner = dp(context, INNER_DP)
        view.setPaddingRelative(
            pageMarginPx + dp(context, BAR_DP) + inner,
            top + inner,
            pageMarginPx + inner,
            bottom + inner,
        )
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private const val ICON_DP = 16f
    private const val ICON_GAP_DP = 8f
    private const val CORNER_DP = 6f
    private const val BAR_DP = 3f
    private const val INNER_DP = 14f
}
