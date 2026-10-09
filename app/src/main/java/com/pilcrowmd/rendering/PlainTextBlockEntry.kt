// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter

/**
 * Adapter entry for [PlainTextChunk]: sets the chunk's literal directly on a
 * prose-styled TextView — no Markwon render, no parsing, every character verbatim. Typography
 * matches [ProseBlockEntry] (reading font, 17sp × scale, primaryText, 1.35 line height) so a
 * `.txt` reads like body prose; vertical padding is zero except next to a forced split of a
 * blank-line-free run (see [seamBottomPadding]).
 */
class PlainTextBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: com.pilcrowmd.ui.theme.FontSet = com.pilcrowmd.ui.theme.FontSets.DEFAULT,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
    private val colorScheme: com.pilcrowmd.ui.theme.PilcrowColorScheme = com.pilcrowmd.ui.theme.DarkColorScheme,
) : MarkwonAdapter.Entry<PlainTextChunk, PlainTextBlockEntry.Holder>() {

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val tv = inflater.inflate(R.layout.adapter_plain_text, parent, false) as TextView
        tv.setTextColor(colorScheme.primaryText.toArgb())
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale)
        tv.setLineSpacing(0f, PreviewLineHeightMultiplier)
        tv.typeface = ResourcesCompat.getFont(context, fontSet.readingRegular)
        return Holder(tv)
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: PlainTextChunk) {
        // Re-applied on every bind: the pinch writes a PX size onto the attached TextView and the
        // end-of-gesture rebuild re-binds rather than recreates, so without this the gesture's size
        // would survive at rest. Same value `createHolder` sets, so nothing moves at rest. See M-04.
        holder.textView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale,
        )
        // A blank-line-free run is split into several TextViews (M-365). A TextView's last line gets
        // no line spacing and, with font padding, its first line starts at `top` not `ascent`, so
        // stacked chunks would jump at the seam. These paddings make the baseline-to-baseline
        // distance across a forced seam equal the one inside a block, and leave the block's bottom
        // where one unsplit TextView would put it. Re-applied on every bind: holders are recycled.
        // A chunk with both flags false keeps font padding and zero padding, exactly as before.
        val tv = holder.textView
        tv.includeFontPadding = !node.continuesPrevious
        val seamBottom = seamBottomPadding(
            tv.paint.fontMetricsInt,
            tv.lineSpacingMultiplier,
            node.continuesPrevious,
            node.continuesNext,
        )
        tv.setPaddingRelative(tv.paddingStart, 0, tv.paddingEnd, seamBottom)
        // Reset shared-holder state (mirrors ProseBlockEntry), then set the literal directly —
        // no markwon.render, no parsing: the text IS the content.
        holder.textView.setTextColor(colorScheme.primaryText.toArgb())
        holder.textView.text = node.literal
        SearchHighlighter.highlight(
            holder.textView,
            searchHighlight,
            blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
            occurrenceBase = 0,
        )
    }

    class Holder(val textView: TextView) : MarkwonAdapter.Holder(textView)
}

/**
 * Bottom padding for a chunk next to a forced seam (see [PlainTextBlockEntry.bindHolder]). [pitch] is
 * the line height StaticLayout gives a line that is followed by another: the font height plus its own
 * rounding of the extra spacing. 0 when the chunk touches no forced seam, and never negative (a font
 * whose `bottom` lies further below `descent` than the spacing extra gets no padding, not a pull-up).
 */
internal fun seamBottomPadding(
    fm: Paint.FontMetricsInt,
    lineSpacingMultiplier: Float,
    continuesPrevious: Boolean,
    continuesNext: Boolean,
): Int {
    val height = fm.descent - fm.ascent
    val pitch = height + (height * (lineSpacingMultiplier - 1f) + ROUND_HALF_UP).toInt()
    // Without font padding the block's last line ends at `descent`; with it, at `bottom`.
    val lastLineEnd = if (continuesPrevious) fm.descent else fm.bottom
    val padding = when {
        continuesNext -> pitch + fm.ascent - lastLineEnd
        continuesPrevious -> fm.bottom - fm.descent
        else -> 0
    }
    return padding.coerceAtLeast(0)
}

/** StaticLayout rounds its extra line spacing half up; mirrored so the seam arithmetic matches it. */
private const val ROUND_HALF_UP = 0.5f
