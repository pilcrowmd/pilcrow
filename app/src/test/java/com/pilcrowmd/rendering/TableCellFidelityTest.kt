// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.Footnotes
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import io.noties.markwon.Markwon
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A table cell must never SILENTLY DROP content (Safeguard 1).
 *
 * `TableBlockEntry` renders each cell through Markwon and falls back to a plain-text walk of the
 * cell's nodes when that returns nothing. The fallback used to collect `Text` literals only, so any
 * node carrying its text as a PROPERTY rather than as a `Text` child vanished from the page — the
 * author's content deleted on screen with no error.
 *
 * The fallback's contract is therefore the same one `SearchMarkdownUseCase.appendVisible` implements:
 * it must reproduce exactly what the cell paints, because search offsets depend on that
 * agreement. These tests pin the node types whose literal lives off the `Text` path.
 */
@RunWith(RobolectricTestRunner::class)
class TableCellFidelityTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    /** The painted text of every cell, row-major — the exact order `TableBlockEntry` lays them out. */
    private fun cellTexts(markdown: String): List<String> {
        val document: Node = Footnotes.transform(markwon.parse(markdown))
        val table = generateSequence(document.firstChild) { it.next }
            .filterIsInstance<TableBlock>()
            .first()
        val entry = TableBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, table)
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts.add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(holder.table)
        return texts
    }

    @Test
    fun `a footnote reference in a table cell paints its ordinal, not nothing`() {
        val texts = cellTexts(
            "| H |\n|---|\n| planet[^t] then Venus |\n\n[^t]: table note.\n",
        )
        assertEquals(listOf("H", "planet1 then Venus"), texts)
    }

    @Test
    fun `inline code in a table cell is not dropped`() {
        val texts = cellTexts("| H |\n|---|\n| a `code` b |\n")
        assertEquals(listOf("H", "a code b"), texts)
    }

    /**
     * The offset trap, pinned. `SearchMarkdownUseCase` models a resolved marker as its ORDINAL, so a
     * cell that paints anything else hands search an offset into text the user cannot see: the match
     * counts, but the highlight lands on nothing. Before the non-lossy fallback this failed with the
     * cell painting "planet then Venus" while search insisted the block contained a "1".
     */
    @Test
    fun `search models a footnote marker in a table cell exactly as the cell paints it`() {
        val markdown = "| H |\n|---|\n| planet[^t] then Venus |\n\n[^t]: table note.\n"
        val painted = cellTexts(markdown).last()
        val matches = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())
            .findSearchMatches(markdown, "1")
        assertEquals("search must see the ordinal in the table block", 1, matches.count { it.adapterPosition == 0 })
        assertTrue("cell must paint the ordinal search modelled, but painted <$painted>", painted.contains("1"))
    }
}
