// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TabStopSpan
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.parseFrontmatterFields
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.FencedCodeBlock

/**
 * MarkwonAdapter.Entry for YAML frontmatter, rendered as a **metadata card**: a soft surface
 * with a thin border, the reading font (NOT mono), muted `key   value` rows, and no Copy button
 * — so the document's title/author reads as metadata, not as a snippet of code. Arbitrary keys
 * are handled; a line without a usable `key:` degrades to a label-less value row (Safeguard 3).
 *
 * Frontmatter (`---…---` at document start) is emitted as a `FencedCodeBlock` with info string
 * "yaml" by FrontmatterPlugin's custom BlockParser (see domain `Frontmatter.kt`) — no source
 * mutation, so the editor/search/TOC see the identical string (Safeguard 2).
 *
 * Frontmatter, code, and mermaid are all `FencedCodeBlock` nodes, so MarkwonAdapter routes them
 * through one entry/holder ([FencedCodeBlockEntry.Holder] / `adapter_code_block`): this entry
 * repurposes that holder's single TextView for the card and hides the scroll-extras (Copy/mermaid).
 * The same entry is used for the PDF header (with [PrintColorScheme]) so print matches the reader.
 *
 * Try/catch in bindHolder ensures graceful fallback on any rendering error (Safeguard 3).
 */
class FrontmatterBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
) : MarkwonAdapter.Entry<FencedCodeBlock, FencedCodeBlockEntry.Holder>() {

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): FencedCodeBlockEntry.Holder {
        val root = inflater.inflate(R.layout.adapter_code_block, parent, false)
        // Enable horizontal scrolling for long YAML values (e.g. URLs); short title/author wrap-free.
        root.findViewById<HorizontalScrollView>(R.id.code_scroll)?.enableHorizontalNestedScroll()
        return FencedCodeBlockEntry.Holder(root)
    }

    override fun bindHolder(markwon: Markwon, holder: FencedCodeBlockEntry.Holder, node: FencedCodeBlock) {
        // Shared holder may have last shown a code block or mermaid diagram — restore the text view
        // and drop the code-only chrome (Copy button) so the card is clean.
        holder.codeScroll.visibility = View.VISIBLE
        holder.copyButton.visibility = View.GONE
        holder.mermaidImage.visibility = View.GONE
        try {
            holder.codeView.movementMethod = null
            // Reading font (not mono) + body size — the card reads like prose, not code.
            holder.codeView.typeface = ResourcesCompat.getFont(context, fontSet.readingRegular)
            holder.codeView.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale,
            )
            holder.codeView.setLineSpacing(0f, PreviewLineHeightMultiplier)
            // Reset the base colour every bind: the holder is recycled, and the row spans cover only
            // the key/value text (not the tab/newline separators), so unspanned chars must not inherit
            // a sibling's (or the catch-path's) colour.
            holder.codeView.setTextColor(colorScheme.primaryText.toArgb())
            holder.codeView.setText(buildCardText(node.literal ?: ""), android.widget.TextView.BufferType.SPANNABLE)
            // Highlight search matches inside the card (single TextView → base 0).
            SearchHighlighter.highlight(
                holder.codeView,
                searchHighlight,
                blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
                occurrenceBase = 0,
            )
            // Soft metadata-card surface: a light surface + thin border (token layer, no hardcoded hex).
            holder.codeScroll.background = GradientDrawable().apply {
                cornerRadius = dp(CARD_CORNER_DP).toFloat()
                setColor(colorScheme.secondarySurface.toArgb())
                setStroke(dp(1f), colorScheme.lightBorder.toArgb())
            }
        } catch (e: Exception) {
            // Graceful-ignore: if rendering fails, show raw content (Safeguard 3).
            Log.e("FrontmatterBlockEntry", "Metadata card render failed: ${e.message}", e)
            holder.codeView.text = node.literal ?: "[frontmatter could not be rendered]"
            holder.codeView.setTextColor(colorScheme.secondaryText.toArgb())
        }
    }

    /**
     * Build the card's spanned text: one `key<TAB>value` row per field, with the key muted, the
     * value in primary text, and a per-line tab stop so the value column aligns. A label-less field
     * (no usable key) is rendered full-width. Returns the raw literal if there are no fields.
     */
    private fun buildCardText(literal: String): CharSequence {
        val fields = parseFrontmatterFields(literal)
        if (fields.isEmpty()) return literal.trim()
        val keyColor = colorScheme.secondaryText.toArgb()
        val valueColor = colorScheme.primaryText.toArgb()
        val valueTabPx = dp(VALUE_COLUMN_DP)
        val sb = SpannableStringBuilder()
        fields.forEachIndexed { index, field ->
            val lineStart = sb.length
            if (field.key.isNotEmpty()) {
                sb.append(field.key)
                sb.setSpan(ForegroundColorSpan(keyColor), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append('\t')
                val valueStart = sb.length
                sb.append(field.value)
                sb.setSpan(ForegroundColorSpan(valueColor), valueStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // Tab stop aligns the value column for this row. EXCLUSIVE_EXCLUSIVE so the span binds
                // to THIS row only — INCLUSIVE end would expand as later rows are appended, stacking
                // one TabStopSpan per row onto the same text.
                sb.setSpan(TabStopSpan.Standard(valueTabPx), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(field.value)
                sb.setSpan(ForegroundColorSpan(valueColor), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (index < fields.lastIndex) sb.append('\n')
        }
        return sb as Spannable
    }

    companion object {
        private const val CARD_CORNER_DP = 8f
        private const val VALUE_COLUMN_DP = 96f // x-offset where the value column starts
    }
}
