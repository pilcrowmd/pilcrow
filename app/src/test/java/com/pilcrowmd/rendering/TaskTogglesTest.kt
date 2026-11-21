// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import io.noties.markwon.ext.tasklist.TaskListSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task boxes in a rendered block become tap targets that name their block, place and count. */
@RunWith(RobolectricTestRunner::class)
class TaskTogglesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon = warmedMarkwonRenderer(context).markwon

    @Test
    fun `each box gets a toggle at the start of its item, in drawing order`() {
        val rendered = markwon.toMarkdown("- [ ] one\n- [x] two\n  - [ ] nested\n- plain")
        addTaskToggles(rendered)
        val toggles = rendered.getSpans(0, rendered.length, TaskToggleSpan::class.java)
            .sortedBy { rendered.getSpanStart(it) }
        val boxes = rendered.getSpans(0, rendered.length, TaskListSpan::class.java)
        assertEquals(3, boxes.size)
        assertEquals(listOf(0, 1, 2), toggles.map { it.ordinal })
        assertEquals(listOf(3, 3, 3), toggles.map { it.count })
        assertEquals(
            boxes.map { rendered.getSpanStart(it) }.sorted(),
            toggles.map { rendered.getSpanStart(it) },
        )
    }

    @Test
    fun `text with no task list gets no toggle`() {
        val rendered = markwon.toMarkdown("- a\n- b\n\n[ ] not a task")
        addTaskToggles(rendered)
        assertEquals(0, rendered.getSpans(0, rendered.length, TaskToggleSpan::class.java).size)
    }

    @Test
    fun `a tap reports the block it was drawn in to the list's handler`() {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setTag(R.id.task_toggle_handler, TaskToggleHandler { b, o, c -> calls += Triple(b, o, c) })
            adapter = TextAdapter(BLOCKS)
        }
        val host = FrameLayout(context).apply { addView(list, ViewGroup.LayoutParams(WIDTH, HEIGHT)) }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, WIDTH, HEIGHT)

        val third = list.findViewHolderForAdapterPosition(2)!!.itemView
        TaskToggleSpan(ordinal = 1, count = 2).onClick(third)

        assertEquals(listOf(Triple(2, 1, 2)), calls)
    }

    @Test
    fun `a tap off any reader list does nothing`() {
        TaskToggleSpan(ordinal = 0, count = 1).onClick(TextView(context))
    }

    private class TextAdapter(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(TextView(parent.context).apply { text = "block" }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = count
    }

    private companion object {
        const val BLOCKS = 4
        const val WIDTH = 1080
        const val HEIGHT = 2000
    }
}
