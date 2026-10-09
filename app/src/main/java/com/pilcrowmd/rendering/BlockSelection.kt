// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * M-157: long-press selects text inside ONE reader block, with the platform's handles and copy menu.
 * Selection across blocks is M-163 and is not this.
 *
 * Call at the END of a bind, after the text, the footnote tap target and the search highlight are in
 * place. Every bind calls it, so a recycled holder is selectable whatever it showed before.
 *
 * Why the movement method is installed by hand: `setTextIsSelectable` installs a plain
 * ArrowKeyMovementMethod, and Markwon then leaves a non-null method alone, so links, footnote markers
 * and image taps would all go dead. [ReaderMovementMethod] is that same selection method plus the tap.
 *
 * Why the accessibility delegate: a focused selectable TextView offers TalkBack a click action (on
 * API 29 and 30; measured, 34 and later leave it out), so every
 * paragraph would be announced "double-tap to activate" although a tap on it does nothing (its links
 * are spans, which TalkBack lists on their own). The delegate drops that action from any block with
 * no click listener. A `<details>` header has one, is not selectable, and keeps its click.
 */
fun TextView.enableBlockSelection() {
    setTextIsSelectable(true)
    movementMethod = ReaderMovementMethod
    ViewCompat.setAccessibilityDelegate(this, NoClickWithoutListener)
}

/** Hides the click action a selectable block would otherwise announce, unless the view has a click. */
private object NoClickWithoutListener : AccessibilityDelegateCompat() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        if (!host.hasOnClickListeners()) {
            info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
            info.isClickable = false
        }
    }
}

/**
 * True when [event] ends a plain tap: no selection showing, and released before a long-press would
 * have fired. Anything else belongs to the selection, so neither a link nor a footnote may act on it.
 * The duration check is what stops a long-press that selected nothing (on blank space, say) from
 * opening the link under the finger when it lifts.
 */
internal fun TextView.isPlainTap(event: MotionEvent): Boolean =
    !hasSelection() && event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()

/**
 * The selection movement `setTextIsSelectable` installs, plus a tap on a [ClickableSpan] (links,
 * footnote markers, relative-image taps).
 *
 * The span is found the way the platform's LinkMovementMethod finds it, so what counts as a hit on a
 * link is unchanged. Two differences, both deliberate: a press does not select the link (that would
 * fight a real selection), and only a [isPlainTap] fires it, so a long-press on a link selects it and
 * does not open it. It acts on ACTION_UP alone, so a lone UP still taps, as the footnote tests send it.
 *
 * A second finger on this block is a pinch starting, not a selection: see [endBlockSelection].
 */
object ReaderMovementMethod : ArrowKeyMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            endBlockSelection(widget)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && widget.isPlainTap(event)) {
            val span = clickableSpanUnder(widget, buffer, event)
            if (span != null) {
                span.onClick(widget)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun clickableSpanUnder(widget: TextView, buffer: Spannable, event: MotionEvent): ClickableSpan? {
        val layout = widget.layout ?: return null
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val offset = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x.toFloat())
        return buffer.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull()
    }
}

/**
 * End any text selection under [root], and any long-press still waiting to start one.
 *
 * Called when a second finger lands and when a pinch begins. Long-press must not eat a pinch: two
 * fingers held still for half a second would otherwise select a word under the first. And a block
 * left mid-selection is a view carrying gesture state into the pinch's re-bind (see M-106).
 */
internal fun endBlockSelection(root: View) {
    if (root is TextView) {
        root.cancelLongPress()
        if (root.hasSelection()) {
            (root.text as? Spannable)?.let { Selection.removeSelection(it) }
            root.clearFocus()
        }
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) endBlockSelection(root.getChildAt(i))
    }
}

/**
 * Ends selection when a second finger lands ANYWHERE in the reader. The movement method sees a second
 * finger only on its own block: touch events are split per child, so a pinch whose fingers land on two
 * blocks sends the second finger to the other block, and only the list sees both. Never intercepts.
 */
object SecondFingerEndsSelection : RecyclerView.SimpleOnItemTouchListener() {
    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) endBlockSelection(rv)
        return false
    }
}

/**
 * The reader list's layout manager. A selectable block takes focus on its first tap, and a stock
 * RecyclerView answers focus by scrolling the whole focused block on screen: tap a paragraph whose
 * top is above the viewport and the page jumps to that top. In touch mode focus comes only from a tap
 * or a long-press, on text the reader is already looking at, so it does not scroll. Out of touch mode
 * (keyboard, D-pad) focus moves the reader's place, so the list scrolls to it as usual. A moving cursor
 * or selection handle still brings its own line into view; that path is a rectangle request, not this.
 */
class ReaderLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun onRequestChildFocus(
        parent: RecyclerView,
        state: RecyclerView.State,
        child: View,
        focused: View?,
    ): Boolean = parent.isInTouchMode || super.onRequestChildFocus(parent, state, child, focused)
}
