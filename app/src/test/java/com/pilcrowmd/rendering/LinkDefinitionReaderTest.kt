// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-214 in a real reader list: the TOC and search positions for a document that starts with a link
 * reference definition, looked up in the production adapter. The definition has no item there, so a
 * position that counts it lands one block late.
 */
@RunWith(RobolectricTestRunner::class)
class LinkDefinitionReaderTest {

    private val markdown = "[r]: https://x.test\n\n# Head\n\nneedle here"

    private fun reader(): RecyclerView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val markwon = buildPilcrowMarkwon(activity)
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(activity, markwon)
        adapter.setParsedMarkdown(markwon, ReaderDocument.transform(markwon.parse(markdown)))
        list.adapter = adapter
        activity.setContentView(list)
        shadowOf(Looper.getMainLooper()).idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, 4000)
        shadowOf(Looper.getMainLooper()).idle()
        return list
    }

    private fun text(list: RecyclerView, position: Int): String =
        (list.findViewHolderForAdapterPosition(position)!!.itemView as TextView).text.toString()

    @Test
    fun `the contents entry and the search match land on their own blocks`() {
        val list = reader()
        val parse = ParseMarkdownHeadingsUseCase()
        val heading = parse.extractHeadings(markdown).single()
        val match = SearchMarkdownUseCase(parse).findSearchMatches(markdown, "needle").single()

        assertEquals("contents entry", "Head", text(list, heading.adapterPosition).trim())
        assertEquals("search match", "needle here", text(list, match.adapterPosition).trim())
    }
}
