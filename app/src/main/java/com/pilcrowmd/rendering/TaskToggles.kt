// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import io.noties.markwon.ext.tasklist.TaskListSpan

/**
 * What a tap on a task-list box does: [block] is the reader block (adapter position), [ordinal] the
 * box's place among the block's boxes in drawing order, [count] how many the block draws. Set as
 * the reader list's `R.id.task_toggle_handler` tag.
 */
fun interface TaskToggleHandler {
    fun toggle(block: Int, ordinal: Int, count: Int)
}

/**
 * Makes every task box in [text] (a rendered block) tappable: a [TaskToggleSpan] over the first
 * character of each item, which is where a tap on the box in the leading margin lands
 * (`Layout.getOffsetForHorizontal` gives the line's first offset for an x left of the text). The
 * words of the item stay plain, so a link inside a task item is still a link, and selecting the text
 * still works. Nothing happens off the reader: the span finds its handler on the list it is drawn in.
 */
fun addTaskToggles(text: Spanned) {
    val spannable = text as? Spannable ?: return
    val boxes = spannable.getSpans(0, spannable.length, TaskListSpan::class.java).sortedBy {
        spannable.getSpanStart(it)
    }
    boxes.forEachIndexed { ordinal, box ->
        val start = spannable.getSpanStart(box)
        if (start < spannable.length) {
            spannable.setSpan(TaskToggleSpan(ordinal, boxes.size), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

/** The tap target of one task box; undecorated, the box itself is the only thing drawn. */
class TaskToggleSpan(val ordinal: Int, val count: Int) : ClickableSpan() {
    override fun onClick(widget: View) {
        val list = widget.findRecyclerViewAncestor() ?: return
        val handler = list.getTag(R.id.task_toggle_handler) as? TaskToggleHandler ?: return
        val item = widget.itemViewIn(list) ?: return
        val position = list.getChildAdapterPosition(item)
        if (position != RecyclerView.NO_POSITION) handler.toggle(position, ordinal, count)
    }

    override fun updateDrawState(ds: TextPaint) = Unit
}

/** The direct child of [list] that holds this view (the view itself for a prose block). */
private fun View.itemViewIn(list: RecyclerView): View? {
    var view: View = this
    while (view.parent !== list) view = view.parent as? View ?: return null
    return view
}
