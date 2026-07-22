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
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Node
import org.commonmark.node.Paragraph

/**
 * Default block entry for prose (headings, paragraphs, lists, blockquotes, rules, …) — every
 * top-level node not handled by a more specific entry (table/code) lands here.
 *
 * It reproduces the exact reading styling the single-TextView preview applied, so the
 * block-level rewrite looks identical: Source Serif 4, 17sp body, primaryText, line-height 1.35.
 * Markwon's heading/emphasis spans scale relative to this base size, so headings stay
 * proportional. Color comes only from the token layer (Safeguard 4).
 */
class ProseBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
) : MarkwonAdapter.Entry<Node, ProseBlockEntry.Holder>() {

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val tv = inflater.inflate(R.layout.adapter_default_prose, parent, false) as TextView
        tv.setTextColor(colorScheme.primaryText.toArgb())
        // Apply fontScale multiplier to body text size (17sp base)
        val scaledBodySize = PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledBodySize)
        tv.setLineSpacing(0f, PreviewLineHeightMultiplier)
        tv.typeface = ResourcesCompat.getFont(context, fontSet.readingRegular)
        return Holder(tv)
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: Node) {
        // Tighten the gap between body paragraphs only. The prose layout's 8dp top+bottom puts
        // consecutive paragraphs 16dp apart, which reads as over-spaced; settled on 6dp (→12dp between
        // two paragraphs). Headings, lists, blockquotes and rules keep the 8dp default.
        // Set on every bind so a recycled holder never carries a stale paragraph padding.
        // Only top-level body paragraphs are tightened. MarkwonAdapter dispatches only top-level
        // document blocks to this default entry (and UNLINKS each node from the tree before binding,
        // so `node.parent` is null here — a `parent is Document` guard would wrongly disable this).
        // Paragraphs nested in lists/blockquotes render inside their parent block's single TextView,
        // never via bindHolder, so a plain `node is Paragraph` already scopes to body paragraphs.
        val verticalPaddingDp = if (node is Paragraph) PARAGRAPH_VERTICAL_PADDING_DP else PROSE_VERTICAL_PADDING_DP
        val verticalPaddingPx = (verticalPaddingDp * context.resources.displayMetrics.density).toInt()
        holder.textView.setPadding(
            holder.textView.paddingLeft,
            verticalPaddingPx,
            holder.textView.paddingRight,
            verticalPaddingPx,
        )
        markwon.setParsedMarkdown(holder.textView, markwon.render(node))
        // Prose blocks are a single TextView, so occurrenceBase is 0.
        SearchHighlighter.highlight(
            holder.textView,
            searchHighlight,
            blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
            occurrenceBase = 0,
        )
    }

    class Holder(val textView: TextView) : MarkwonAdapter.Holder(textView)

    private companion object {
        // Vertical padding for prose blocks. Paragraphs use 6dp → 12dp between two body paragraphs;
        // all other prose blocks (headings, lists, blockquotes, rules) keep 8dp
        // (adapter_default_prose.xml default) so their spacing is unaffected.
        const val PARAGRAPH_VERTICAL_PADDING_DP = 6f
        const val PROSE_VERTICAL_PADDING_DP = 8f
    }
}
