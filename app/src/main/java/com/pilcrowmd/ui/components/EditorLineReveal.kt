// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Handler
import android.os.Looper
import android.view.View
import com.pilcrowmd.R
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.LayoutStateChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeAlways

/**
 * Issue #15: scroll so [line] sits in the middle of the editor's height. The editor is resized for an
 * open keyboard (imePadding), so the line stays above it. Sora's layout offset for a character is the
 * bottom of its row, so the row's top is one row height above it.
 *
 * The move is applied at once, not left pending for the next frame: Sora's word-wrap pass ends in
 * `scrollBy(0, 0)`, which cancels a move that has not been drawn yet (measured on a 329-line note:
 * the reveal was gone a few milliseconds after it).
 */
internal fun CodeEditor.revealLineInMiddle(line: Int) {
    val rowTop = getCharOffsetY(line, 0) + offsetY - rowHeight
    val target = (rowTop - (height - rowHeight) / 2f).toInt().coerceIn(0, scrollMaxY.coerceAtLeast(0))
    scroller.forceFinished(true)
    scroller.startScroll(offsetX, offsetY, 0, target - offsetY, 0)
    scroller.computeScrollOffset()
    invalidate()
}

/**
 * [revealLineInMiddle] for a line the editor has just been told to show, done when it can be right
 * and kept right while the editor settles, until the user takes over.
 *
 * The reveal runs from a `post` made before the editor is attached, which can run before its first
 * layout (size 0), and the word-wrap layout of a large note is finished on a background thread after
 * the editor first shows. So: wait for a real size, reveal, and reveal again each time Sora reports
 * its layout no longer busy. Stop at the first scroll that moves, tap or edit by the user, and after
 * [REVEAL_SETTLE_MILLIS] whatever happens. A newer reveal on the same editor replaces this one.
 */
internal fun CodeEditor.revealLineInMiddleWhenSettled(line: Int) {
    (getTag(R.id.editor_reveal) as? SettledReveal)?.stop()
    SettledReveal(this, line).also {
        setTag(R.id.editor_reveal, it)
        it.start()
    }
}

/** How long a landing reveal keeps watching the editor before it lets go. */
internal const val REVEAL_SETTLE_MILLIS = 10_000L

internal class SettledReveal(
    private val editor: CodeEditor,
    private val line: Int,
    private val reveal: (Int) -> Unit = editor::revealLineInMiddle,
) {
    private val receipts = mutableListOf<SubscriptionReceipt<*>>()
    private var layoutListener: View.OnLayoutChangeListener? = null
    private var stopped = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopAtCap = Runnable { stop() }

    fun start() {
        handler.postDelayed(stopAtCap, REVEAL_SETTLE_MILLIS)
        if (editor.isLaidOut && editor.width > 0 && editor.height > 0) {
            begin()
        } else {
            val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (right > left && bottom > top) {
                    editor.removeOnLayoutChangeListener(layoutListener)
                    layoutListener = null
                    if (!stopped) begin()
                }
            }
            layoutListener = listener
            editor.addOnLayoutChangeListener(listener)
        }
    }

    private fun begin() {
        revealHere()
        receipts += editor.subscribeAlways<LayoutStateChangeEvent> { event ->
            if (!event.isLayoutBusy) revealHere()
        }
        receipts += editor.subscribeAlways<ScrollEvent> { event ->
            // Sora's own scrollBy(0, 0) (after a layout pass or a resize) reports a "drag" that went
            // nowhere, and our own reveal may report its move; only a scroll that moved somewhere
            // else, and not to keep the caret in view, is the user's.
            val moved = event.endY != event.startY && event.endY != revealedY
            if (moved && event.cause != ScrollEvent.CAUSE_MAKE_POSITION_VISIBLE) stop()
        }
        receipts += editor.subscribeAlways<SelectionChangeEvent> { stop() }
        receipts += editor.subscribeAlways<ContentChangeEvent> { stop() }
    }

    private var revealedY = -1

    private fun revealHere() {
        reveal(line)
        revealedY = editor.offsetY
    }

    fun stop() {
        if (stopped) return
        stopped = true
        handler.removeCallbacks(stopAtCap)
        layoutListener?.let { editor.removeOnLayoutChangeListener(it) }
        layoutListener = null
        receipts.forEach { it.unsubscribe() }
        receipts.clear()
        if (editor.getTag(R.id.editor_reveal) === this) editor.setTag(R.id.editor_reveal, null)
    }
}
