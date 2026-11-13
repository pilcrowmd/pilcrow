// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Rect
import android.os.Bundle
import android.text.Spanned
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.pilcrowmd.R
import kotlin.math.roundToInt

/**
 * Gives each footnote marker in a prose TextView its own TalkBack node (M-76): "Footnote 1, button",
 * focusable on its own and activated by a double tap, which opens the same sheet a finger tap does.
 *
 * The paragraph itself still reads as before: the host keeps its own node and text, and the markers
 * are virtual children of it. Everything is read from the TextView's current text and layout when
 * TalkBack asks, so a recycled holder needs only [invalidateRoot] after a bind.
 */
class FootnoteMarkerAccessibility private constructor(private val host: TextView) : ExploreByTouchHelper(host) {

    private fun markers(): List<FootnoteJumpSpan> {
        val spanned = host.text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, spanned.length, FootnoteJumpSpan::class.java).sortedBy(spanned::getSpanStart)
    }

    /** Where marker [id] is, in the host's coordinates, or null when it has no layout yet. */
    private fun boundsOf(id: Int): Rect? {
        val spanned = host.text as? Spanned ?: return null
        val layout = host.layout ?: return null
        val span = markers().getOrNull(id) ?: return null
        val bounds = spanned.footnoteMarkerBounds(layout, span)
        bounds.offset(
            (host.totalPaddingLeft - host.scrollX).toFloat(),
            (host.totalPaddingTop - host.scrollY).toFloat(),
        )
        return Rect(
            bounds.left.roundToInt(),
            bounds.top.roundToInt(),
            bounds.right.roundToInt(),
            bounds.bottom.roundToInt(),
        )
    }

    override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
        node.dropClickWithoutListener(host)
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int =
        markers().indices.firstOrNull { boundsOf(it)?.contains(x.roundToInt(), y.roundToInt()) == true } ?: INVALID_ID

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        virtualViewIds.addAll(markers().indices)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val spanned = host.text as? Spanned
        val span = markers().getOrNull(virtualViewId)
        // The marker's text is exactly its ordinal (see FootnoteSpans.kt), so it is the number to say.
        val ordinal = if (spanned != null && span != null) {
            spanned.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span)).toString()
        } else {
            ""
        }
        node.text = host.context.getString(R.string.footnote_marker, ordinal)
        node.className = Button::class.java.name
        node.isFocusable = true
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        // ExploreByTouchHelper requires parent bounds; an empty rect stands in before the first layout.
        @Suppress("DEPRECATION")
        node.setBoundsInParent(boundsOf(virtualViewId) ?: Rect())
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val span = markers().getOrNull(virtualViewId) ?: return false
        span.onClick(host)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    companion object {
        /** Attach to [textView]; hover events go to the helper so touch exploration finds the markers. */
        fun install(textView: TextView): FootnoteMarkerAccessibility {
            val helper = FootnoteMarkerAccessibility(textView)
            ViewCompat.setAccessibilityDelegate(textView, helper)
            textView.setOnHoverListener { _, event -> helper.dispatchHoverEvent(event) }
            return helper
        }
    }
}
