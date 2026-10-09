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
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import io.noties.markwon.Markwon
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The search model and the reader must agree, block by block, on how many matches there are: the
 * highlighter re-searches the painted text and focuses the match whose ordinal the model chose, so
 * any model match the reader does not paint shifts every later ordinal in that block.
 *
 * Each block is painted through the real renderer, as the reader paints it: the reader's plugin
 * chain (with its image loader), the reader's tree, and [ProseBlockEntry], or [TableBlockEntry] for
 * a table. The domain-side cases are in `domain/usecase/SearchModelParityTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SearchPaintedParityTest {

    private lateinit var context: Context
    private val search = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A table cell's maths is resolved during the bind, and Robolectric never runs the library's
        // init provider.
        ru.noties.jlatexmath.JLatexMathAndroid.init(context)
    }

    /**
     * A new instance for every document: the HTML support keeps one piece of state across renders
     * (a closed block-level tag puts the NEXT tag on a new line, even in a later render), so a shared
     * instance would make each case depend on the one before it.
     */
    private fun readerMarkwon(): Markwon = buildPilcrowMarkwon(
        context,
        images = ReaderImages(MarkdownImageLoader.createImageLoader(context), ImageBase()),
    )

    /** The searchable match count of each top-level block, as the reader paints it. */
    private fun paintedCounts(markdown: String, query: String): List<Int> {
        val markwon = readerMarkwon()
        val blocks = AdapterBlocks.of(ReaderTree.build(markwon, markdown, plain = false))
        // The adapter binds each block on its own, detached from the document.
        blocks.forEach { it.unlink() }
        return blocks.map { block ->
            paintedTexts(markwon, block).sumOf { searchableMatchOffsets(it, query).size }
        }
    }

    /** The text of every TextView [block] paints: one for a prose block, one per cell for a table. */
    private fun paintedTexts(markwon: Markwon, block: Node): List<CharSequence> {
        if (block !is TableBlock) {
            val entry = ProseBlockEntry(context)
            val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
            entry.bindHolder(markwon, holder, block)
            return listOf(holder.textView.text)
        }
        val entry = TableBlockEntry(context)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, block)
        val texts = mutableListOf<CharSequence>()
        fun collect(view: View) {
            if (view is TextView) texts.add(view.text)
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(holder.table)
        return texts
    }

    private fun modelCounts(markdown: String, query: String, blocks: Int): List<Int> {
        val matches = search.findSearchMatches(markdown, query)
        return List(blocks) { position -> matches.count { it.adapterPosition == position } }
    }

    private fun assertParity(markdown: String, query: String, expectedPainted: List<Int>) {
        val painted = paintedCounts(markdown, query)
        // Pins the painted side too, so a change to the renderer cannot make both sides agree on nothing.
        assertEquals("painted counts for <$query>", expectedPainted, painted)
        assertEquals("model counts for <$query>", painted, modelCounts(markdown, query, painted.size))
    }

    // --- M-28 ---

    @Test
    fun `a quote's paragraphs are counted as the reader paints them, on separate lines`() {
        assertParity("> one\n>\n> two", "etw", listOf(0))
        assertParity("> xa\n>\n> ab aa", "aa", listOf(1))
    }

    @Test
    fun `list items are counted as the reader paints them, on separate lines`() {
        assertParity("- foo\n- bar", "obar", listOf(0))
        assertParity("- foo\n- bar", "o", listOf(2))
    }

    // --- M-33 ---

    @Test
    fun `code inside a formula is counted as the reader paints it, as part of the image`() {
        assertParity("aa \$b `czz` d\$ ee", "czz", listOf(0))
        assertParity("aa \$b `czz` d\$ ee czz", "czz", listOf(1))
    }

    @Test
    fun `a code span showing dollars is counted as the reader paints it`() {
        assertParity("`\$x\$`", "x", listOf(1))
        assertParity("\$a\$`\$b\$`", "b", listOf(1))
    }

    @Test
    fun `an inline tag inside a formula is counted as the reader paints it, as part of the image`() {
        // Outside a formula this CDATA section paints "zz"; inside one it is part of the image.
        assertParity("aa \$b <![CDATA[zz]]> d\$ ee", "zz", listOf(0))
        assertParity("aa <![CDATA[zz]]> ee", "zz", listOf(1))
    }

    @Test
    fun `a dollar inside a code span is counted as the reader paints it, as code`() {
        val content = "`\$HOME/bin`, `PATH`, and `\$PWD`"
        assertParity(content, "PATH", listOf(1))
        assertParity(content, "\$PWD", listOf(1))
        assertParity(content, "and", listOf(1))
        // A formula that opens first still closes on a dollar inside a later code span.
        assertParity("\$a `b\$` c", "c", listOf(1))
        assertParity("\$a `b\$` c", "b", listOf(0))
    }

    // --- M-331 ---

    @Test
    fun `inline HTML is counted as the reader paints it, without its markup`() {
        assertParity("Press <kbd>Ctrl</kbd> now", "kbd", listOf(0))
        assertParity("Press <kbd>Ctrl</kbd> now", "ctrl", listOf(1))
        assertParity("x<b></b>y", "xy", listOf(1))
        assertParity("a <!-- hid --> b", "hid", listOf(0))
        assertParity("one<br>two", "onetwo", listOf(0))
        assertParity("one</br>two", "onetwo", listOf(1))
        assertParity("a<iframe src=\"x\"></iframe>b", "a\u00A0b", listOf(1))
        assertParity("x <![CDATA[ zz ]]> y", "zz", listOf(1))
        assertParity("foo<img src=\"a.png\">bar", "foobar", listOf(0))
    }

    @Test
    fun `a block-level tag inside a paragraph is counted as the reader paints it, on a new line`() {
        assertParity("a<div>x</div>b", "ax", listOf(0))
        assertParity("a<div>x</div>b", "xb", listOf(1))
        assertParity("a<div></div>b", "ab", listOf(0))
        assertParity("a<hr>b", "ab", listOf(0))
        assertParity("a<p>x</p>b", "xb", listOf(0))
        assertParity("a<p>x<p>y", "xy", listOf(0))
        // Painted "a\nx\ny": a tag that is not block-level still closes an open <p>, with a '\n'.
        assertParity("a<p>x<mark>y", "xy", listOf(0))
        // A second <li> closes the first, so the second </li> finds no open li and leaves the next tag
        // on the same line: "cd" is painted.
        assertParity("a<li>x<li>y</li><b>z</b></li>c<b>d</b>", "cd", listOf(1))
        assertParity("a<mark>x</mark>b", "axb", listOf(1))
        assertParity("a<details>x</details>b", "axb", listOf(1))
        assertParity("a<div>x</div>b<b>c</b>", "bc", listOf(0))
        assertParity("a<div>x</div>b<mark>c</mark>", "bc", listOf(0))
    }

    @Test
    fun `a CDATA section is counted as the reader paints it, its whitespace collapsed`() {
        assertParity("x <![CDATA[ a   b ]]> y", "a b", listOf(1))
        assertParity("a<pre><![CDATA[ p  q ]]></pre>b", "p  q", listOf(1))
    }

    // --- HTML in a table cell: the cell is rendered through Markwon, so its HTML is painted ---

    @Test
    fun `HTML in a table cell is counted as the cell paints it`() {
        // Painted "a\nb" and "b": the br is a line break, and no "b" comes from its markup.
        assertParity("| a<br>b | b |\n|---|---|\n| x | y |\n", "b", listOf(2))
        // Painted "x kbd": the tag's markup is not painted.
        assertParity("| <kbd>x</kbd> kbd |\n|---|\n| z |\n", "kbd", listOf(1))
        // Painted "a\nxb" and "q\nr": a cell paints a block-level tag as a paragraph does.
        assertParity("| H |\n|---|\n| a<div>x</div>b | <p>q</p>r |\n", "xb", listOf(1))
    }

    // --- M-332 ---

    @Test
    fun `an image's alt text is not counted, as the model does not count it`() {
        // The reader draws the picture, or its placeholder, over the alt text.
        assertParity("see ![apple](missing.png) apple", "apple", listOf(1))
        assertParity("see <img src=\"a.png\" alt=\"cat\"> cat", "cat", listOf(1))
        assertParity("[<img src=\"a.png\" alt=\"cat\">](u) cat", "cat", listOf(1))
    }

    @Test
    fun `a formula is still not counted once images are excluded too`() {
        assertParity("x \$x\$ x ![x](missing.png)", "x", listOf(2))
    }
}
