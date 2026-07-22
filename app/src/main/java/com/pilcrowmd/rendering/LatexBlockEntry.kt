// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Node

/**
 * MarkwonAdapter.Entry for LaTeX block math (Node type created by JLatexMathPlugin for `$$...$$`).
 * Renders each block formula into its own row with a HorizontalScrollView so very wide
 * formulas (e.g., long integrals, matrices) can pan horizontally without breaking prose wrapping.
 *
 * Note on node type: The exact node type depends on the ext-latex version. This entry is
 * registered for any Node type that the JLatexMathPlugin creates for block math. The adapter
 * will call bindHolder only for matching node types.
 *
 * CRITICAL: Uses `markwon.setParsedMarkdown(textView, spanned)` to set text on the
 * TextView, NOT `textView.text = spanned`. This enables Markwon's async JLatexMath drawable
 * callback to invalidate the correct TextView when the bitmap is ready, ensuring placeholders
 * update to final bitmaps.
 *
 * Try/catch in bindHolder degrades gracefully on LaTeX parse errors (Safeguard 3):
 * if JLatexMath fails to parse, shows fallback text instead of crashing.
 *
 * If the block-math node type is not emitted by ext-latex 4.6.2, this entry will not be
 * invoked, and block math will render inline via ProseBlockEntry (acceptable graceful
 * fallback).
 */
class LatexBlockEntry(
    private val context: Context,
    private val nodeClass: Class<out Node>,
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
) : MarkwonAdapter.Entry<Node, LatexBlockEntry.Holder>() {

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).toInt()

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val root = inflater.inflate(R.layout.adapter_latex_block, parent, false)
        // Let a wide LaTeX formula pan sideways inside the vertical RecyclerView.
        root.findViewById<HorizontalScrollView>(R.id.latex_scroll)?.enableHorizontalNestedScroll()
        return Holder(root)
    }

    override fun bindHolder(markwon: Markwon, holder: Holder, node: Node) {
        try {
            // Render the block-math node to a Spanned via Markwon.
            // JLatexMathPlugin will insert placeholder and async drawable (if enabled).
            // CRITICAL: use markwon.setParsedMarkdown(textView, spanned) NOT textView.text = spanned
            // so async invalidation works.
            val rendered = markwon.render(node)
            markwon.setParsedMarkdown(holder.latexView, rendered)

            // Code-block surface styling for consistency with code blocks.
            holder.latexView.typeface =
                ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular)
            holder.latexView.textSize = PilcrowTypography.CODE_BLOCK_FONT_SIZE_SP
            holder.latexView.setLineSpacing(0f, PreviewLineHeightMultiplier)

            // Token-driven colors (Safeguard 4: no hardcoded hex).
            holder.latexView.setTextColor(colorScheme.primaryText.toArgb())

            // Subtle background border to visually separate the formula block.
            holder.latexScroll.background = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(colorScheme.codeBlockBg.toArgb())
                setStroke(dp(1f), colorScheme.codeBlockBorder.toArgb())
            }
        } catch (e: Exception) {
            // Graceful-ignore: if LaTeX parsing fails, show fallback (Safeguard 3).
            // Do NOT crash the adapter on a malformed formula.
            Log.e("LatexBlockEntry", "LaTeX render failed: ${e.message}", e)
            holder.latexView.text = "[LaTeX block could not be rendered]"
            holder.latexView.setTextColor(colorScheme.secondaryText.toArgb())
        }
    }

    /** ViewHolder: the scrollable LaTeX formula TextView. */
    class Holder(itemView: View) : MarkwonAdapter.Holder(itemView) {
        val latexScroll: HorizontalScrollView = requireView(R.id.latex_scroll)
        val latexView: TextView = requireView(R.id.latex_text)
    }
}
