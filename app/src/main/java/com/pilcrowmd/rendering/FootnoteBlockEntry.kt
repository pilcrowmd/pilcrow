// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.FootnoteDefinitionBlock
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter

/**
 * Adapter entry for a footnote definition: the note reads as an aside — a thin leading
 * rule, the marker in the accent token, and the body in the reading font one step down in size and
 * in muted foreground. Restraint borrowed from the metadata card rather than a new visual language.
 *
 * **Rendered in place, where the definition sits** — not collected at the end of the document as
 * GitHub does. Collecting means reordering top-level blocks, which changes every adapter position
 * after it and would have to be replicated exactly in the search/TOC parse or scroll targets land on
 * the wrong block. In practice authors already put definitions at the bottom, so in-place
 * reads the same for almost every real file.
 *
 * **An unreferenced definition still renders**, showing its literal label where the number would be.
 * GitHub deletes such a block outright; deleting content the user wrote is not something this app
 * does. It has nowhere to jump back to, so its back-link is hidden.
 *
 * The body is the only text view search knows about: the marker and the back-link live in sibling
 * views precisely so the block's painted text still equals what `SearchMarkdownUseCase` models
 * (see `adapter_footnote.xml`).
 */
class FootnoteBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
) : MarkwonAdapter.Entry<FootnoteDefinitionBlock, FootnoteBlockEntry.Holder>() {

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val holder = Holder(inflater.inflate(R.layout.adapter_footnote, parent, false))
        val noteSize = PilcrowTypography.FOOTNOTE_FONT_SIZE_SP * fontScale
        val readingFont = ResourcesCompat.getFont(context, fontSet.readingRegular)

        holder.rule.setBackgroundColor(colorScheme.lightBorder.toArgb())

        // Chrome, not content: the marker paints an ordinal that search does not model as part of
        // this block, so the intra-block scroll must not count matches inside it.
        holder.marker.tag = SEARCH_EXCLUDED_TAG
        holder.marker.setTextColor(colorScheme.accent.toArgb())
        holder.marker.setTextSize(TypedValue.COMPLEX_UNIT_SP, noteSize)
        holder.marker.typeface = readingFont

        holder.body.setTextColor(colorScheme.secondaryText.toArgb())
        holder.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, noteSize)
        holder.body.setLineSpacing(0f, PreviewLineHeightMultiplier)
        holder.body.typeface = readingFont

        holder.backLink.setColorFilter(colorScheme.accent.toArgb())
        // The back-link scales with the reader's zoom like everything else on the page: a fixed-dp
        // icon looks oversized against 0.85 text and lost against 1.6 text. Sized to about one line
        // of note text so a one-line note is not forced taller than its own words.
        val boxPx = dp(BACK_LINK_BOX_DP * fontScale)
        holder.backLink.layoutParams = holder.backLink.layoutParams.apply {
            width = boxPx
            height = boxPx
        }
        val insetPx = dp(BACK_LINK_INSET_DP * fontScale)
        holder.backLink.setPadding(insetPx, insetPx, insetPx, insetPx)
        return holder
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

    @Suppress("TooGenericExceptionCaught") // Safeguard 3: a bad note must degrade, never crash
    override fun bindHolder(markwon: Markwon, holder: Holder, node: FootnoteDefinitionBlock) {
        // An unreferenced note has no number to show, so it shows what the author actually typed.
        holder.marker.text = node.ordinal?.toString() ?: node.label

        val backTarget = node.firstReferenceBlockIndex
        if (backTarget >= 0) {
            holder.backLink.visibility = View.VISIBLE
            holder.backLink.setOnClickListener { it.jumpToBlock(backTarget) }
        } else {
            // Nothing references this note — there is no "back" to offer. Recycled holders carry
            // the previous note's listener, so both sides of this branch must be explicit.
            holder.backLink.visibility = View.GONE
            holder.backLink.setOnClickListener(null)
        }

        try {
            holder.body.setTextColor(colorScheme.secondaryText.toArgb())
            val rendered = tintFootnoteMarkers(markwon.render(node), colorScheme.accent.toArgb())
            markwon.setParsedMarkdown(holder.body, rendered)
            SearchHighlighter.highlight(
                holder.body,
                searchHighlight,
                blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
                occurrenceBase = 0,
            )
        } catch (e: Exception) {
            // Same degradation shape as the prose lane: show something readable, never propagate.
            Log.e("FootnoteBlockEntry", "footnote render failed: ${e.message}", e)
            holder.body.text = ProseBlockEntry.RENDER_FALLBACK_TEXT
        }
    }

    private companion object {
        // Touch target and its inset; the glyph is what is left between them (16dp at scale 1.0).
        const val BACK_LINK_BOX_DP = 32f
        const val BACK_LINK_INSET_DP = 8f
    }

    class Holder(itemView: View) : MarkwonAdapter.Holder(itemView) {
        val rule: View = itemView.findViewById(R.id.footnote_rule)
        val marker: TextView = itemView.findViewById(R.id.footnote_marker)
        val body: TextView = itemView.findViewById(R.id.footnote_body)
        val backLink: ImageView = itemView.findViewById(R.id.footnote_back_link)
    }
}
