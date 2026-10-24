// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
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
@Suppress("LongParameterList") // The reader's settings, forwarded from the adapter builder.
class ProseBlockEntry(
    private val context: Context,
    private val fontScale: Float = 1.0f,
    private val fontSet: FontSet = FontSets.DEFAULT,
    private val searchHighlight: SearchHighlight = SearchHighlight(),
    private val colorScheme: PilcrowColorScheme = DarkColorScheme,
    /** M-161: which `<details>` sections are open. Null (the PDF) draws every header open, untappable. */
    private val details: DetailsState? = null,
    /** M-328: where a split paragraph's pieces meet. Null (the PDF keeps the paragraph whole) means no seams. */
    private val seams: ParagraphSeamState? = null,
) : MarkwonAdapter.Entry<Node, ProseBlockEntry.Holder>() {

    private val headerStyle by lazy {
        DetailsHeaderStyle(
            boldFace = ResourcesCompat.getFont(context, fontSet.readingBold) ?: android.graphics.Typeface.DEFAULT_BOLD,
            chevronColor = colorScheme.accent.toArgb(),
            fontScale = fontScale,
        )
    }

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): Holder {
        val tv = inflater.inflate(R.layout.adapter_default_prose, parent, false) as TextView
        tv.setTextColor(colorScheme.primaryText.toArgb())
        // Apply fontScale multiplier to body text size (17sp base)
        val scaledBodySize = PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledBodySize)
        tv.setLineSpacing(0f, PreviewLineHeightMultiplier)
        tv.typeface = ResourcesCompat.getFont(context, fontSet.readingRegular)
        tv.setSpannableFactory(KeepSpannableFactory) // M-322, see the object's KDoc
        return Holder(tv)
    }

    @Suppress("TooGenericExceptionCaught") // Safeguard 3: any render failure must degrade, not crash
    override fun bindHolder(markwon: Markwon, holder: Holder, node: Node) {
        // Tighten the gap between body paragraphs only. The prose layout's 8dp top+bottom puts
        // consecutive paragraphs 16dp apart, which reads as over-spaced; settled on 6dp (→12dp between
        // two paragraphs). Headings get their own asymmetric padding (M-164); lists, blockquotes and
        // rules keep the 8dp default.
        // Set on every bind so a recycled holder never carries a stale paragraph padding.
        // Only top-level body paragraphs are tightened. MarkwonAdapter dispatches only top-level
        // document blocks to this default entry (and UNLINKS each node from the tree before binding,
        // so `node.parent` is null here — a `parent is Document` guard would wrongly disable this).
        // Paragraphs nested in lists/blockquotes render inside their parent block's single TextView,
        // never via bindHolder, so a plain `node is Paragraph` already scopes to body paragraphs.
        // Re-apply the size on EVERY bind, for the same reason the padding and colour are
        // re-applied: a recycled holder must not carry state from its previous life. The live
        // pinch-zoom writes a PX size straight onto the attached TextView, and the end-of-gesture
        // rebuild uses `swapAdapter(_, false)`, which RE-BINDS existing holders instead of
        // recreating them — so `createHolder` never runs for them and the gesture's size would
        // survive at rest, permanently. That is M-04's visible symptom: after releasing a pinch the
        // title stays huge while the paragraph under it stays tiny. Identical value to the one
        // `createHolder` sets, so a freshly created holder is unaffected and resting layout is
        // byte-identical (measured: zero goldens move). Before the padding, because a split
        // paragraph's seam padding is measured from the final font metrics.
        holder.textView.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            PilcrowTypography.PROSE_BODY_FONT_SIZE_SP * fontScale,
        )
        // M-328: a piece of a split paragraph drops font padding at its top seam; every other bind
        // restores it, since a recycled holder may have shown a piece.
        holder.textView.includeFontPadding = !(node is Paragraph && continuesPrevious(node))
        val density = context.resources.displayMetrics.density
        val (top, bottom) = when (node) {
            is Paragraph -> paragraphPadding(holder.textView, node, (PARAGRAPH_VERTICAL_PADDING_DP * density).toInt())
            is Heading -> (HEADING_TOP_PADDING_DP * density).toInt() to (HEADING_BOTTOM_PADDING_DP * density).toInt()
            else -> (PROSE_VERTICAL_PADDING_DP * density).toInt() to (PROSE_VERTICAL_PADDING_DP * density).toInt()
        }
        // M-161: a callout draws its box and a `<details>` header takes a tap; a recycled holder must
        // shed both, so every bind starts from the plain block and adds only what this node needs.
        val callout = node as? CalloutBlock
        val detailsHeader = (node as? HtmlBlock)?.let { Details.parseHeader(it.literal) }
        applyChrome(holder, callout, detailsHeader, top, bottom)
        try {
            // Reset shared-holder state: a recycled holder may carry the fallback's secondaryText
            // color from a previous failed bind — restore primaryText on every healthy bind.
            holder.textView.setTextColor(colorScheme.primaryText.toArgb())
            // Footnote markers take `footnoteMarker` from the ACTIVE scheme (Dark/Light/Print). The
            // Markwon visitor that emits them is one shared singleton across all three, so it
            // cannot pick the colour itself — the entry, which knows the scheme, does (Safeguard 4).
            if (detailsHeader != null) {
                bindDetailsHeader(holder, detailsHeader)
            } else {
                val rendered = tintFootnoteMarkers(markwon.render(node), colorScheme.footnoteMarker.toArgb())
                if (callout != null) CalloutStyle.decorateTitles(context, rendered, colorScheme, fontScale)
                markwon.setParsedMarkdown(holder.textView, rendered)
            }
            // A footnote marker paints at 0.75 of body size — about 20 px, which missed two taps
            // in three during UAT. This widens the HIT AREA only; the glyph is untouched (M-06).
            holder.textView.enableGenerousFootnoteTaps()
            // Prose blocks are a single TextView, so occurrenceBase is 0.
            SearchHighlighter.highlight(
                holder.textView,
                searchHighlight,
                blockIsFocused = holder.bindingAdapterPosition == searchHighlight.focusedPosition,
                occurrenceBase = 0,
            )
        } catch (e: Exception) {
            // Graceful-ignore: never crash the adapter on a bad/unsupported block (Safeguard 3) —
            // same degradation shape as FencedCodeBlockEntry.bindHolder.
            Log.e("ProseBlockEntry", "render failed: ${e.message}", e)
            holder.textView.text = context.getString(R.string.render_content_failed)
            holder.textView.setTextColor(colorScheme.secondaryText.toArgb())
        }
        // M-157: last, after the text and the tap target are in place. A header takes a tap instead.
        if (detailsHeader == null) holder.textView.enableBlockSelection()
    }

    private fun continuesPrevious(node: Paragraph) = seams?.seams?.continuesPrevious(node) == true

    /**
     * M-328 (docs/proposals/m328-reader-big-content.md): a paragraph's top and bottom padding in px.
     * An ordinary paragraph gets [gap] on both sides, as it always has. A piece of a split paragraph
     * drops the gap at its seam, with the same arithmetic as a plain-text chunk (see
     * [PlainTextBlockEntry] and [seamBottomPadding], the M-365 seam), so the cut reads as a line break.
     */
    private fun paragraphPadding(tv: TextView, node: Paragraph, gap: Int): Pair<Int, Int> {
        val continuesPrevious = continuesPrevious(node)
        val continuesNext = seams?.seams?.continuesNext(node) == true
        val seamBottom =
            seamBottomPadding(tv.paint.fontMetricsInt, tv.lineSpacingMultiplier, continuesPrevious, continuesNext)
        return (if (continuesPrevious) 0 else gap) to seamBottom + (if (continuesNext) 0 else gap)
    }

    /** M-161: the plain block's padding, or a callout's box, or a `<details>` header's inset. */
    private fun applyChrome(
        holder: Holder,
        callout: CalloutBlock?,
        detailsHeader: com.pilcrowmd.domain.markdown.DetailsHeader?,
        top: Int,
        bottom: Int,
    ) {
        val density = context.resources.displayMetrics.density
        holder.textView.background = null
        holder.textView.clearDetailsToggle()
        when {
            callout != null ->
                CalloutStyle.applyBox(
                    holder.textView,
                    CalloutStyle.color(callout.kind, colorScheme),
                    holder.pageMargin,
                    top,
                    bottom,
                )
            detailsHeader != null -> {
                val inner = (DETAILS_HEADER_INNER_DP * density).toInt()
                val vertical = (DETAILS_HEADER_VERTICAL_DP * density).toInt()
                holder.textView.setPaddingRelative(
                    holder.pageMargin + inner,
                    vertical,
                    holder.pageMargin + inner,
                    vertical,
                )
            }
            else -> holder.textView.setPaddingRelative(holder.pageMargin, top, holder.pageMargin, bottom)
        }
    }

    /** M-161: the summary row of a `<details>` section. A tap opens or closes the section. */
    private fun bindDetailsHeader(holder: Holder, header: com.pilcrowmd.domain.markdown.DetailsHeader) {
        val state = details
        val expanded = state?.isExpanded(holder.bindingAdapterPosition) ?: true
        // A header is tapped, not selected (M-157). A selectable view takes focus on its first tap
        // instead of clicking, so the section would need two taps. Before the toggle, which sets the
        // click this would otherwise clear.
        holder.textView.setTextIsSelectable(false)
        holder.textView.text = detailsHeaderText(context, header, expanded, headerStyle)
        holder.textView.bindDetailsToggle(expanded, state?.let { { toggleSection(holder, it) } })
    }

    private fun toggleSection(holder: Holder, state: DetailsState) {
        val position = holder.bindingAdapterPosition
        if (position < 0) return
        val changed = state.toggle(position)
        if (!changed.isEmpty()) holder.bindingAdapter?.notifyItemRangeChanged(changed.first, changed.count())
    }

    class Holder(val textView: TextView) : MarkwonAdapter.Holder(textView) {
        /** The layout's side padding (adapter_default_prose.xml), which a callout's box replaces. */
        val pageMargin: Int = textView.paddingStart
    }

    internal companion object {
        // Vertical padding for prose blocks. Paragraphs use 6dp → 12dp between two body paragraphs;
        // lists, blockquotes and rules keep 8dp
        // (adapter_default_prose.xml default) so their spacing is unaffected.
        const val PARAGRAPH_VERTICAL_PADDING_DP = 6f
        const val PROSE_VERTICAL_PADDING_DP = 8f

        // M-164: headings sit further from the text above than from the text they
        // introduce. With a paragraph's 6dp either side: 30dp above a heading, 18dp below it.
        const val HEADING_TOP_PADDING_DP = 24f
        const val HEADING_BOTTOM_PADDING_DP = 12f

        // Shown when a prose block fails to render (Safeguard 3 fallback). The reader shows
        // render_content_failed; this English twin stays so the failure-path tests can assert the
        // exact degradation contract without a Context.
        internal const val RENDER_FALLBACK_TEXT = "[content could not be rendered]"
    }
}

/**
 * M-322. `TextView.setText(_, SPANNABLE)` (Markwon's default buffer type) copies the text through the
 * view's `Spannable.Factory`, and the default one makes a flat `SpannableString`, whose `getSpans` and
 * `nextSpanTransition` test EVERY span with `Class.isInstance`. StaticLayout calls them per span run
 * and Layout.draw per line, so one paragraph's cost grows with spans x lines: measured on the
 * emulator, a paragraph with 4,000 inline spans paid ~0.9 s of layout and one with 2,000 inline
 * formulas ~0.2 s of UI draw per frame, the shape of the second Vitals ANR. A `SpannableStringBuilder`
 * keeps its spans in an interval tree, so the same lookups cost O(log n + k). It is a COPY, not
 * Markwon's own builder: that one reverses `getSpans`, and spans added after `setText` (the search
 * highlight) must keep landing last, exactly as they do in the flat copy.
 */
private object KeepSpannableFactory : Spannable.Factory() {
    override fun newSpannable(source: CharSequence): Spannable = SpannableStringBuilder(source)
}
