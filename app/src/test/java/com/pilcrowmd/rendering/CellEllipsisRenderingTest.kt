// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.TableSplitter
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import io.noties.markwon.Markwon
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A61: the "…" [TableSplitter] puts where it caps a cell is a subclass of Text, and Markwon finds a
 * node's visitor by its exact class, so without a visitor of its own the "…" drew nothing while search
 * still counted it. The cell is rendered here the way `TableBlockEntry.renderCell` does, with
 * `markwon.render(cell)`; binding a whole table would not show it, because the entry falls back to the
 * cell's plain text when the render comes back empty.
 */
@RunWith(RobolectricTestRunner::class)
class CellEllipsisRenderingTest {

    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        markwon = buildPilcrowMarkwon(context)
    }

    @Test
    fun `a capped cell paints its ellipsis, and search counts exactly that one`() {
        val markdown = "| H |\n|---|\n| ${"z".repeat(TableSplitter.MAX_CELL_CHARS + 1)} |\n"
        val cells = ArrayList<TableCell>()
        ReaderDocument.transform(markwon.parse(markdown)).accept(
            object : AbstractVisitor() {
                override fun visit(customNode: CustomNode) {
                    if (customNode is TableCell) cells += customNode
                    super.visit(customNode)
                }
            },
        )
        val capped = cells.last()

        val painted = markwon.render(capped).toString()
        val searched = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase()).findSearchMatches(markdown, "…")

        assertTrue("the capped cell ends with the ellipsis", painted.endsWith("…"))
        assertEquals("search counts the ellipsis once", 1, searched.size)
        assertEquals("what search counts is what is painted", searched.size, painted.count { it == '…' })
    }
}
