// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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
 * Scrolls the reader to [targetBlockIndex] — a top-level block index, which IS the RecyclerView
 * position (see `FootnoteDefinitionBlock`). Used both ways round: forward from a marker to its
 * definition, back from a definition to its first reference.
 *
 * It resolves the RecyclerView from the clicked TextView rather than taking a callback, because the
 * marker is emitted by the shared singleton Markwon visitor — a callback there would have to be
 * mutable process-wide state, and the PDF export uses the SAME Markwon instance, so it would point at a
 * reader that may not even be on screen. Walking up from the widget asks the only question that
 * matters at click time: which list am I in? In the PDF export there is no RecyclerView ancestor and
 * nothing is ever clicked, so this is inert there — which is the correct behaviour for print.
 *
 * The underline is suppressed (and the colour left to the caller's token) so the marker reads as a
 * typographic superscript rather than a hyperlink.
 */
class FootnoteJumpSpan(private val targetBlockIndex: Int) : ClickableSpan() {

    override fun onClick(widget: View) = widget.jumpToBlock(targetBlockIndex)

    override fun updateDrawState(ds: TextPaint) {
        // Deliberately NOT calling super: ClickableSpan's default paints link-blue and underlines,
        // which fights the reading design. Colour comes from the token layer via the entries.
        ds.isUnderlineText = false
    }
}

/**
 * Scroll the list this view belongs to so [targetBlockIndex] sits at the top of the viewport — the
 * same call the TOC jump makes. Shared by the marker's span and the definition's back-link button,
 * so both directions of the jump are one behaviour.
 *
 * Silently does nothing when there is no target and when there is no list (the PDF export renders
 * these same views off-screen), which is what Safeguard 3 asks of an interaction.
 */
fun View.jumpToBlock(targetBlockIndex: Int) {
    if (targetBlockIndex < 0) return
    val recyclerView = findRecyclerViewAncestor() ?: return
    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
    if (layoutManager != null) {
        layoutManager.scrollToPositionWithOffset(targetBlockIndex, 0)
    } else {
        recyclerView.smoothScrollToPosition(targetBlockIndex)
    }
    // Landing on the block is not the same as finding it: a footnote definition looks like every
    // other paragraph, so the eye still has to hunt. Tint it briefly (M-06).
    highlightOnArrival(recyclerView, targetBlockIndex)
}

/**
 * How far either side of a footnote marker a tap still counts, in dp. The marker paints at
 * [FOOTNOTE_MARKER_SCALE] of body size — about 20 px wide on a phone, which measured two misses in
 * three during UAT. Widening the PAINTED marker was rejected: the ordinal's size is load-bearing
 * (search models the marker as exactly those digits) and a bigger glyph would change every footnote
 * golden. So the hit area grows and the pixels do not.
 */
private const val FOOTNOTE_TAP_SLOP_DP = 12f

/**
 * Let a tap NEAR a footnote marker count as a tap ON it.
 *
 * Runs only when the exact hit MISSED — a tap that lands on the marker is handed straight back to
 * Markwon's movement method, so the normal path is untouched and no tap can fire twice. Only
 * [FootnoteJumpSpan] is searched: widening every link in the document would make ordinary prose
 * taps unpredictable, which is a worse bug than the one being fixed.
 */
fun TextView.enableGenerousFootnoteTaps() {
    val slopPx = FOOTNOTE_TAP_SLOP_DP * resources.displayMetrics.density
    setOnTouchListener { view, event ->
        if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
        val textView = view as? TextView ?: return@setOnTouchListener false
        val spanned = textView.text as? Spanned ?: return@setOnTouchListener false
        val x = event.x - textView.totalPaddingLeft + textView.scrollX
        val y = event.y - textView.totalPaddingTop + textView.scrollY
        val layout = textView.layout ?: return@setOnTouchListener false
        val line = layout.getLineForVertical(y.toInt())
        // ANY exact hit wins, not just a footnote's. A regular link sitting within the slop of a
        // marker would otherwise be swallowed: the footnote check alone would miss it, the widened
        // search would then find the neighbouring marker, and the link the reader actually tapped
        // would never fire. Raised in review; the slop exists to rescue a MISS, never to overrule a
        // hit.
        if (spanned.clickableSpanAt(layout, line, x) != null) return@setOnTouchListener false
        val nearby = spanned.footnoteSpanNear(layout, line, x, slopPx) ?: return@setOnTouchListener false
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
        val left = layout.getPrimaryHorizontal(getSpanStart(span).coerceIn(lineStart, lineEnd))
        val right = layout.getPrimaryHorizontal(getSpanEnd(span).coerceIn(lineStart, lineEnd))
        x >= minOf(left, right) && x <= maxOf(left, right)
    }
}

/**
 * The [FootnoteJumpSpan] on [line] whose painted extent comes within [slopPx] of [x], or null.
 *
 * Measures the distance to each MARKER rather than converting the tap into a character offset and
 * looking there. Two earlier versions did the latter and both were wrong in ways only a test found:
 * probing the two points `x ± slopPx` misses a marker lying between them, and clamping the tap into
 * the line's horizontal bounds depends on `getLineLeft`/`getLineRight`, which are not dependable.
 * Asking "is the tap within slop of this marker" is the requirement stated directly, and a line
 * holds at most a handful of markers.
 *
 * With `slopPx = 0` this is the plain "is the tap ON the marker" question.
 */
private fun Spanned.footnoteSpanNear(layout: Layout, line: Int, x: Float, slopPx: Float): FootnoteJumpSpan? {
    val lineStart = layout.getLineStart(line)
    val lineEnd = layout.getLineEnd(line)
    if (lineStart >= lineEnd) return null
    return getSpans(lineStart, lineEnd, FootnoteJumpSpan::class.java).firstOrNull { span ->
        val left = layout.getPrimaryHorizontal(getSpanStart(span).coerceIn(lineStart, lineEnd))
        val right = layout.getPrimaryHorizontal(getSpanEnd(span).coerceIn(lineStart, lineEnd))
        x >= minOf(left, right) - slopPx && x <= maxOf(left, right) + slopPx
    }
}

/** Nearest enclosing RecyclerView, or null when this view is not inside one (e.g. PDF export). */
private fun View.findRecyclerViewAncestor(): RecyclerView? {
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
