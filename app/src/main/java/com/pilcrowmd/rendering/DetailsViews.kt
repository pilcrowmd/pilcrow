// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.DetailsHeader
import com.pilcrowmd.domain.markdown.DetailsSection
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.Markwon
import io.noties.markwon.recycler.MarkwonAdapter
import org.commonmark.node.Node

/**
 * The `<details>` header's text (M-161): the summary in the reading font's bold face after a
 * chevron, and — while open — any body text written inside the same HTML block. The painted text
 * is exactly what `SearchMarkdownUseCase` models for the block (summary, then a newline and the
 * inline body); a closed header paints only the summary, and search opens it before landing there.
 */
internal fun detailsHeaderText(
    context: Context,
    header: DetailsHeader,
    expanded: Boolean,
    style: DetailsHeaderStyle,
): CharSequence {
    val text = SpannableStringBuilder(header.summary)
    text.setSpan(FaceSpan(style.boldFace), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    val chevron = ContextCompat.getDrawable(
        context,
        if (expanded) R.drawable.ic_details_open else R.drawable.ic_details_closed,
    )?.mutate()
    if (expanded && header.inlineBody.isNotEmpty()) text.append('\n').append(header.inlineBody)
    // Over the whole text: the chevron is drawn once, on the first line, and every line — a wrapped
    // summary and any inline body — hangs after it.
    if (chevron != null) {
        chevron.setTint(style.chevronColor)
        text.setSpan(
            LeadingIconSpan(
                chevron,
                dp(context, CHEVRON_DP * style.fontScale),
                dp(context, CHEVRON_GAP_DP * style.fontScale),
            ),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return text
}

/** What a header's text is drawn with: the reading set's bold face, the chevron's colour, the scale. */
internal class DetailsHeaderStyle(val boldFace: Typeface, @ColorInt val chevronColor: Int, val fontScale: Float)

/** Tap target and screen-reader state for a header; [onToggle] null means not interactive (PDF). */
internal fun TextView.bindDetailsToggle(expanded: Boolean, onToggle: (() -> Unit)?) {
    if (onToggle == null) {
        clearDetailsToggle()
        return
    }
    setOnClickListener { onToggle() }
    ViewCompat.setStateDescription(this, if (expanded) "Expanded" else "Collapsed")
}

/** Undo [bindDetailsToggle] on a recycled prose view that is now showing something else. */
internal fun TextView.clearDetailsToggle() {
    setOnClickListener(null)
    isClickable = false
    ViewCompat.setStateDescription(this, null)
}

/** A typeface, as a span: the header's summary is bold while any inline body stays regular. */
private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) {
        tp.typeface = face
    }

    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = face
    }
}

/**
 * Wraps an adapter entry so a block inside a closed `<details>` section takes no space (M-161). Every
 * entry is wrapped — a section can hold a table, code or maths as well as prose — and the entry's own
 * binding is untouched: hiding is applied after it, on the item view alone.
 */
internal class HideableEntry<N : Node, H : MarkwonAdapter.Holder>(
    private val inner: MarkwonAdapter.Entry<N, H>,
    private val details: DetailsState,
) : MarkwonAdapter.Entry<N, H>() {

    override fun createHolder(inflater: LayoutInflater, parent: ViewGroup): H = inner.createHolder(inflater, parent)

    override fun bindHolder(markwon: Markwon, holder: H, node: N) {
        val hidden = details.isHidden(holder.bindingAdapterPosition)
        // A hidden block takes no space, so the list fills the screen past a closed section by binding
        // every block in it. Rendering those would be wasted work: whatever shows a block (a tap, a
        // search, a jump) notifies its range, which binds it again, visible this time.
        if (!hidden) inner.bindHolder(markwon, holder, node)
        holder.itemView.setHidden(hidden)
    }

    override fun onViewRecycled(holder: H) = inner.onViewRecycled(holder)

    override fun id(node: N): Long = inner.id(node)

    override fun clear() = inner.clear()

    private fun View.setHidden(hidden: Boolean) {
        val params = layoutParams ?: return
        // Every item layout's root is wrap_content (adapter_*.xml), so that is what showing restores.
        val height = if (hidden) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.height != height) {
            params.height = height
            layoutParams = params
        }
        visibility = if (hidden) View.GONE else View.VISIBLE
    }
}

/**
 * Draws each `<details>` section as one bordered box around its header and, while open, its body
 * blocks, and indents what sits inside it (M-161). A decoration rather than a container view because
 * the body is sibling blocks: they keep their own entries and their adapter positions.
 */
internal class DetailsDecoration(
    context: Context,
    private val details: DetailsState,
    /** Set by the reader on every theme change; the box takes its colours from it when drawn. */
    var scheme: PilcrowColorScheme,
) : RecyclerView.ItemDecoration() {

    private val pageMargin = dp(context, PAGE_MARGIN_DP)
    private val insetStart = dp(context, BODY_INSET_START_DP)
    private val insetEnd = dp(context, BODY_INSET_END_DP)
    private val boxGap = dp(context, BOX_GAP_DP).toFloat()
    private val closePad = dp(context, CLOSE_PAD_DP)
    private val radius = dp(context, CORNER_DP).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(context, 1f).toFloat()
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val closing = details.sections.firstOrNull { it.closeBlock == position }
        if (closing != null) {
            // The `</details>` block paints nothing; while its section is open it is the box's
            // bottom padding, and while closed it takes no space at all.
            if (details.isExpanded(closing.header) && !details.isHidden(closing.header)) outRect.bottom = closePad
            return
        }
        if (details.isHidden(position)) return
        val depth = details.depth(position)
        outRect.left = depth * insetStart
        outRect.right = depth * insetEnd
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val layoutManager = parent.layoutManager ?: return
        val onScreen = laidOutPositions(parent) ?: return
        fill.color = scheme.secondarySurface.toArgb()
        stroke.color = scheme.toolbarBorder.toArgb()
        // Every frame of a scroll draws here, and a box costs a scan of all sections per row, so a
        // section that reaches none of the laid-out rows is skipped before any of that work.
        val reachingScreen = details.sections.filter { it.last >= onScreen.first && it.header <= onScreen.last }
        for (section in reachingScreen) {
            val box = boxOf(section, parent, layoutManager) ?: continue
            c.drawRoundRect(box, radius, radius, fill)
            c.drawRoundRect(box, radius, radius, stroke)
        }
    }

    /** The adapter positions from the first laid-out row to the last, or null when none is laid out. */
    private fun laidOutPositions(parent: RecyclerView): IntRange? {
        val positions = (0 until parent.childCount)
            .map { parent.getChildAdapterPosition(parent.getChildAt(it)) }
            .filter { it != RecyclerView.NO_POSITION }
        return if (positions.isEmpty()) null else positions.min()..positions.max()
    }

    /** The section's box over the children laid out now, or null when none of it is on screen. */
    private fun boxOf(
        section: DetailsSection,
        parent: RecyclerView,
        layoutManager: RecyclerView.LayoutManager,
    ): RectF? {
        if (details.isHidden(section.header)) return null
        val inBox = (0 until parent.childCount).map { parent.getChildAt(it) }.filter { child ->
            val position = parent.getChildAdapterPosition(child)
            val inRange = position == section.header ||
                (details.isExpanded(section.header) && position in section.header..section.last)
            // A close block is hidden but still carries the box's bottom padding (getItemOffsets).
            inRange && (position == section.closeBlock || !details.isHidden(position))
        }
        if (inBox.isEmpty()) return null
        // A header scrolled off the top: start the box above the viewport so no corner shows.
        val headerShown = inBox.any { parent.getChildAdapterPosition(it) == section.header }
        val top = if (headerShown) inBox.minOf { layoutManager.getDecoratedTop(it) }.toFloat() else -radius * 2
        val bottom = inBox.maxOf { layoutManager.getDecoratedBottom(it) }.toFloat()
        val depth = details.depth(section.header)
        return RectF(
            (parent.paddingLeft + pageMargin + depth * insetStart).toFloat(),
            top + boxGap,
            (parent.width - parent.paddingRight - pageMargin - depth * insetEnd).toFloat(),
            bottom - boxGap,
        )
    }

    private companion object {
        const val PAGE_MARGIN_DP = 20f // adapter_default_prose.xml's side padding
        const val BODY_INSET_START_DP = 32f // lines body text up with the summary after the chevron
        const val BODY_INSET_END_DP = 16f
        const val BOX_GAP_DP = 4f
        const val CLOSE_PAD_DP = 8f
        const val CORNER_DP = 8f
    }
}

/** The header's inner padding inside its box (the chevron sits here); see [DetailsDecoration]. */
internal const val DETAILS_HEADER_INNER_DP = 12f
internal const val DETAILS_HEADER_VERTICAL_DP = 18f
private const val CHEVRON_DP = 16f
private const val CHEVRON_GAP_DP = 4f

private fun dp(context: Context, value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
