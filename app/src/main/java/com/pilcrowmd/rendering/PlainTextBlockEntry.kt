// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
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
 * `.txt` reads like body prose; vertical padding is zero (see adapter_plain_text.xml).
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
