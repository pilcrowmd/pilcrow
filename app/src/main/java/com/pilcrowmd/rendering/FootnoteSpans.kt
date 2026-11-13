// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.RectF
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The spans a footnote is made of on screen — the marker in prose and the definition's
 * back-link are the same two ideas, so they share one span type.
 *
 * The marker text is the ORDINAL and nothing else. That is not cosmetic: `SearchMarkdownUseCase`
 * models a `FootnoteReference` as exactly those digits, and the invariant is that search's
 * per-block text matches character-for-character what the TextView paints. Adding a bracket or a
 * separator here would silently re-introduce phantom matches.
 */

/** Marker size relative to the surrounding body text. Spiked at 0.85/1.0/1.6 font scale. */
const val FOOTNOTE_MARKER_SCALE: Float = 0.75f

/**
 * View tag marking a TextView as CHROME rather than document content: it paints something search
 * does not model, so the intra-block scroll must not count its matches when threading a block's
 * occurrence ordinals. Set on the footnote definition's marker, whose "1" or literal label would
 * otherwise be counted as a match the search use case never saw.
 */
const val SEARCH_EXCLUDED_TAG: String = "search-excluded"

/**
 * The tappable part of a footnote marker. A tap opens the note in a sheet over the page (M-76); the
 * reader itself never moves.
 *
 * The tap goes to the handler the reader put on its RecyclerView ([setFootnoteTapHandler]), with the
 * definition's top-level block index. It does not take a callback directly, because the marker is
 * emitted by the shared Markwon visitor, which the PDF export uses too: a callback there would have to
 * be mutable process-wide state pointing at a reader that may not be on screen. Walking up from the
 * widget asks the only question that matters at click time: which reader am I in? With no handler
 * above it (the PDF export, a bare TextView) the tap does nothing, which is what Safeguard 3 asks of
 * an interaction.
 *
 * The underline is suppressed (and the colour left to the caller's token) so the marker does not read
 * as a hyperlink.
 */
class FootnoteJumpSpan(private val definitionBlockIndex: Int) : ClickableSpan() {

    override fun onClick(widget: View) {
        var view: View? = widget
        while (view != null) {
            @Suppress("UNCHECKED_CAST")
            (view.getTag(R.id.footnote_tap_handler) as? (Int) -> Unit)?.let { return it(definitionBlockIndex) }
            view = view.parent as? View
        }
    }

    override fun updateDrawState(ds: TextPaint) {
        // Deliberately NOT calling super: ClickableSpan's default paints link-blue and underlines,
        // which fights the reading design. Colour comes from the token layer via the entries.
        ds.isUnderlineText = false
    }
}

/** Where the reader's footnote taps go, with the definition's block index (M-76). Set on its RecyclerView. */
fun View.setFootnoteTapHandler(handler: (definitionBlockIndex: Int) -> Unit) {
    setTag(R.id.footnote_tap_handler, handler)
}

/**
 * Scroll the list this view belongs to so [targetBlockIndex] sits at the top of the viewport — the
 * same call the TOC jump makes. Used by the definition's back-link button and by `#heading` links;
 * the marker itself opens a sheet instead (M-76).
 *
 * Silently does nothing when there is no target and when there is no list (the PDF export renders
 * these same views off-screen), which is what Safeguard 3 asks of an interaction.
 */
fun View.jumpToBlock(targetBlockIndex: Int) {
    if (targetBlockIndex < 0) return
    val recyclerView = findRecyclerViewAncestor() ?: return
    // A target inside a closed <details> section opens it first, or the jump lands on nothing (M-161).
    (recyclerView.getTag(R.id.details_state) as? DetailsState)?.reveal(targetBlockIndex)?.let {
        recyclerView.adapter?.notifyItemRangeChanged(it.first, it.count())
    }
    smoothJumpTo(recyclerView, targetBlockIndex)
    // Landing on the block is not the same as finding it: a footnote definition looks like every
    // other paragraph, so the eye still has to hunt. Tint it briefly (M-06).
    highlightOnArrival(recyclerView, targetBlockIndex)
}

/**
 * Half the side of the square around a marker that still counts as a tap on it, in dp: 48 dp, a
 * thumb (M-76). Vertical as well as horizontal, so a tap on the line above or below still finds it.
 * The painted marker is about 20 px wide on a phone, which missed two taps in three during UAT.
 */
private const val FOOTNOTE_TAP_REACH_DP = 24f

/**
 * Let a tap NEAR a footnote marker count as a tap ON it.
 *
 * Runs only when the exact hit MISSED — a tap that lands on the marker is handed straight back to
 * Markwon's movement method, so the normal path is untouched and no tap can fire twice. Only
 * [FootnoteJumpSpan] is searched: widening every link in the document would make ordinary prose
 * taps unpredictable, which is a worse bug than the one being fixed.
 */
fun TextView.enableGenerousFootnoteTaps() {
    val reachPx = FOOTNOTE_TAP_REACH_DP * resources.displayMetrics.density
    setOnTouchListener { view, event ->
        if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
        val textView = view as? TextView ?: return@setOnTouchListener false
        // M-157: lifting a finger after a long-press, or while text is selected, belongs to the
        // selection. Without this, ending a selection near a marker would jump to the note.
        if (!textView.isPlainTap(event)) return@setOnTouchListener false
        val spanned = textView.text as? Spanned ?: return@setOnTouchListener false
        val x = event.x - textView.totalPaddingLeft + textView.scrollX
        val y = event.y - textView.totalPaddingTop + textView.scrollY
        val layout = textView.layout ?: return@setOnTouchListener false
        val line = layout.getLineForVertical(y.toInt())
        // ANY exact hit wins, not just a footnote's. A regular link sitting within reach of a
        // marker would otherwise be swallowed: the footnote check alone would miss it, the widened
        // search would then find the neighbouring marker, and the link the reader actually tapped
        // would never fire. Raised in review; the reach exists to rescue a MISS, never to overrule a
        // hit.
        if (spanned.clickableSpanAt(layout, line, x) != null) return@setOnTouchListener false
        val nearby = spanned.footnoteSpanNear(layout, x, y, reachPx) ?: return@setOnTouchListener false
        // Consuming the UP keeps it from the TextView, which would have cancelled the long-press it
        // armed on DOWN; left armed, it selects a word in this block half a second after the tap.
        textView.cancelLongPress()
        nearby.onClick(textView)
        true
    }
}

/** Any [ClickableSpan] painted directly under [x] on [line] — a link as much as a footnote marker. */
private fun Spanned.clickableSpanAt(layout: Layout, line: Int, x: Float): ClickableSpan? {
    val lineStart = layout.getLineStart(line)
    val lineEnd = layout.getLineEnd(line)
    if (lineStart >= lineEnd) return null
    return getSpans(lineStart, lineEnd, ClickableSpan::class.java).firstOrNull { span ->
        val (left, right) = horizontalExtentOn(layout, line, getSpanStart(span), getSpanEnd(span))
        x >= left && x <= right
    }
}

/**
 * The horizontal extent of [start, end) on [line], in layout coordinates. A span that runs past the
 * line (a wrapping link) ends at the line's right edge: its end offset belongs to a LATER line, and
 * measuring it there would place the right edge at that line's start, so the span would seem to run
 * from the left margin to its own first word.
 */
private fun horizontalExtentOn(layout: Layout, line: Int, start: Int, end: Int): Pair<Float, Float> {
    val left = layout.getPrimaryHorizontal(start.coerceAtLeast(layout.getLineStart(line)))
    val right = if (layout.getLineForOffset(end) == line) {
        layout.getPrimaryHorizontal(end)
    } else {
        layout.getLineRight(line)
    }
    return minOf(left, right) to maxOf(left, right)
}

/**
 * Where [span]'s marker is painted, in layout coordinates: its horizontal extent on its line, and the
 * line's height. A marker never breaks across lines in the reader (a replacement span is one unit).
 */
internal fun Spanned.footnoteMarkerBounds(layout: Layout, span: FootnoteJumpSpan): RectF {
    val start = getSpanStart(span)
    val line = layout.getLineForOffset(start)
    val (left, right) = horizontalExtentOn(layout, line, start, getSpanEnd(span))
    return RectF(left, layout.getLineTop(line).toFloat(), right, layout.getLineBottom(line).toFloat())
}

/**
 * The [FootnoteJumpSpan] whose marker is nearest ([x], [y]), among those within a square of side
 * `2 * reachPx` centred on the marker (or the marker itself, if that is bigger), or null.
 *
 * Measures the distance to each MARKER rather than converting the tap into a character offset and
 * looking there. Two earlier versions did the latter and both were wrong in ways only a test found:
 * probing the two points `x ± slop` misses a marker lying between them, and clamping the tap into
 * the line's horizontal bounds depends on `getLineLeft`/`getLineRight`, which are not dependable.
 * Asking "is the tap within reach of this marker" is the requirement stated directly, and a
 * paragraph holds at most a handful of markers.
 */
private fun Spanned.footnoteSpanNear(layout: Layout, x: Float, y: Float, reachPx: Float): FootnoteJumpSpan? =
    getSpans(0, length, FootnoteJumpSpan::class.java)
        .map { span -> span to footnoteMarkerBounds(layout, span) }
        .filter { (_, bounds) ->
            abs(x - bounds.centerX()) <= maxOf(reachPx, bounds.width() / 2) &&
                abs(y - bounds.centerY()) <= maxOf(reachPx, bounds.height() / 2)
        }
        .minByOrNull { (_, bounds) -> hypot(x - bounds.centerX(), y - bounds.centerY()) }
        ?.first

/** Nearest enclosing RecyclerView, or null when this view is not inside one (e.g. PDF export). */
internal fun View.findRecyclerViewAncestor(): RecyclerView? {
    var candidate: ViewParent? = parent
    while (candidate != null) {
        if (candidate is RecyclerView) return candidate
        candidate = candidate.parent
    }
    return null
}

/**
 * Paint every footnote marker in [text] in [color]. Called by the entries, which are the only place
 * that knows the active scheme — the Markwon visitor that emits the marker is shared by the reader
 * (Dark/Light) and the PDF export (Print), so it cannot pick a colour itself (Safeguard 4).
 *
 * A no-op on immutable text, and idempotent in practice because each bind renders a fresh Spanned.
 */
fun tintFootnoteMarkers(text: Spanned, @ColorInt color: Int): Spanned {
    val spannable = text as? Spannable ?: return text
    for (span in spannable.getSpans(0, spannable.length, FootnoteJumpSpan::class.java)) {
        val start = spannable.getSpanStart(span)
        val end = spannable.getSpanEnd(span)
        if (start in 0..<end) {
            spannable.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return text
}
