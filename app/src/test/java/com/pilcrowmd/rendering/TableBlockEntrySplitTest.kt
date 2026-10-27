// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.TableSplitter
import io.mockk.every
import io.mockk.mockk
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
 * S8-A31 lead 3: a table with very many cells must not be bound as ONE reader item (one View per cell,
 * on the main thread). The reader cuts it ([TableSplitter]); these tests bind the blocks the reader
 * really gets, through the reader's own adapter, and count what each bind builds.
 *
 * The count is the guard, not a wall-clock time: a time bound would pass on a fast machine against the
 * unsplit table. The fixture has 1,000 rows against a limit of 200 per block, so the only thing that
 * can keep a block under the limit is the cut.
 *
 * The second half is the other defence: a bind that runs out of memory (an Error, which `catch
 * (e: Exception)` does not see) shows the table as text instead of ending the process. An OutOfMemory
 * cannot be provoked deterministically in a unit test, so the Markwon the entry draws through is made
 * to throw one.
 */
@RunWith(RobolectricTestRunner::class)
class TableBlockEntrySplitTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
        markwon = buildPilcrowMarkwon(context)
    }

    private fun table(rows: Int, columns: Int = 3): String = buildString {
        append((0 until columns).joinToString(" | ", "| ", " |\n") { "h$it" })
        append((0 until columns).joinToString(" | ", "| ", " |\n") { "---" })
        for (r in 0 until rows) append((0 until columns).joinToString(" | ", "| ", " |\n") { "r$r-$it" })
    }

    /** The reader's document for [markdown] and one bound holder for each of its tables, by position. */
    private fun bindTables(
        markdown: String,
        highlight: SearchHighlight = SearchHighlight(),
    ): List<TableBlockEntry.Holder> {
        val document: Node = ReaderDocument.transform(markwon.parse(markdown))
        val blocks = AdapterBlocks.of(document)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, searchHighlight = highlight)
        adapter.setParsedMarkdown(markwon, document)
        val parent = FrameLayout(context)
        return blocks.indices.filter { blocks[it] is TableBlock }.map { position ->
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            holder as TableBlockEntry.Holder
        }
    }

    private fun rowsOf(holder: TableBlockEntry.Holder): List<List<TextView>> =
        (0 until holder.table.childCount).map { i ->
            val row = holder.table.getChildAt(i) as LinearLayout
            (0 until row.childCount).map { row.getChildAt(it) as TextView }
        }

    private fun texts(row: List<TextView>) = row.map { it.text.toString() }

    @Test
    fun `a table of a thousand rows is bound as pieces, none with more cells than the limit`() {
        val rows = 1_000
        val columns = 3
        val holders = bindTables(table(rows, columns))

        val cellLimit = TableSplitter.PIECE_ROWS * columns + columns
        holders.forEachIndexed { i, holder ->
            val cells = rowsOf(holder).sumOf { it.size }
            assertTrue("block $i built $cells cells, limit $cellLimit", cells <= cellLimit)
        }
        assertEquals("the body is cut into 200-row pieces", 5, holders.size)
    }

    @Test
    fun `every piece is drawn with the header, and the rows run on without a gap or a repeat`() {
        val rows = 1_000
        val holders = bindTables(table(rows))

        holders.forEach { assertEquals(listOf("h0", "h1", "h2"), texts(rowsOf(it).first())) }
        val body = holders.flatMap { rowsOf(it).drop(1) }.map(::texts)
        assertEquals((0 until rows).map { r -> (0..2).map { "r$r-$it" } }, body)
    }

    @Test
    fun `a table that fits is still bound as one block`() {
        val holders = bindTables(table(TableSplitter.PIECE_ROWS))

        assertEquals(1, holders.size)
        assertEquals(TableSplitter.PIECE_ROWS + 1, rowsOf(holders.single()).size)
    }

    /**
     * Search counts a header once (its cells are in the first piece only), so a repeated header must
     * paint no match, or the occurrence ordinals of the rows under it would be off by the header's own
     * matches. The first piece, which does count its header, is the control.
     */
    @Test
    fun `a header repeated in a later piece paints no search match, the first one does`() {
        val green = 0xFF00FF00.toInt()
        val highlight = SearchHighlight(query = "h1", otherColor = green, focusedColor = green)
        val holders = bindTables(table(TableSplitter.PIECE_ROWS * 2), highlight)

        fun headerMatches(holder: TableBlockEntry.Holder) = rowsOf(holder).first().sumOf { cell ->
            (cell.text as? Spanned)?.getSpans(0, cell.text.length, BackgroundColorSpan::class.java)?.size ?: 0
        }
        assertEquals("control: the first piece paints its header's match", 1, headerMatches(holders[0]))
        assertEquals("the repeated header paints none", 0, headerMatches(holders[1]))
    }

    // ---- the other defence: an Error in the bind ----

    /** A Markwon whose cell render throws [error]; a plain Exception there is caught per cell, an Error is not. */
    private fun failingMarkwon(error: Throwable): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.render(any()) } throws error
    }

    /** A Markwon that renders cells but throws [error] when a cell's text is set: only the bind's own guard sees it. */
    private fun failingOnSet(error: Throwable): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.setParsedMarkdown(any(), any<Spanned>()) } throws error
    }

    private fun bindDirect(markdown: String, with: Markwon): TableBlockEntry.Holder {
        val document = ReaderDocument.transform(markwon.parse(markdown))
        val table = generateSequence(document.firstChild) { it.next }.filterIsInstance<TableBlock>().first()
        val entry = TableBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(with, holder, table)
        return holder
    }

    private fun onlyChild(holder: TableBlockEntry.Holder): TextView {
        val group: ViewGroup = holder.table
        assertEquals("the table is replaced by one view", 1, group.childCount)
        return group.getChildAt(0) as TextView
    }

    @Test
    fun `a bind that runs out of memory shows the table as text instead of ending the process`() {
        val shown = onlyChild(bindDirect(table(3), failingMarkwon(OutOfMemoryError("test"))))

        val text = shown.text.toString()
        assertTrue("it says the table could not be drawn: $text", text.contains("[table could not be rendered]"))
        assertTrue("it keeps the header as tab-separated text: $text", text.contains("h0\th1\th2\n"))
        assertTrue("and the rows: $text", text.contains("r0-0\tr0-1\tr0-2\n") && text.contains("r2-0\tr2-1\tr2-2"))
    }

    @Test
    fun `the text of a table that ran out of memory is bounded`() {
        val shown = onlyChild(bindDirect(table(2_000), failingMarkwon(OutOfMemoryError("test"))))

        val text = shown.text.toString()
        assertTrue("bounded: ${text.length} chars", text.length < 12_000)
        assertTrue("it starts with the first rows", text.contains("r0-0\tr0-1\tr0-2\n"))
        assertTrue("and not the last", !text.contains("r1999-0"))
    }

    @Test
    fun `an ordinary failure still shows the same fallback`() {
        val shown = onlyChild(bindDirect(table(3), failingOnSet(IllegalStateException("test"))))

        assertTrue(shown.text.toString().contains("[table could not be rendered]"))
    }
}
