// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Guards **M-134** — "Wrap long lines in code blocks", default OFF.
 *
 * Every assertion is on the MEASURED layout, not on the flag: the row's first fix direction
 * (`code_text` at `match_parent`) sets a plausible-looking property and wraps nothing, because a
 * HorizontalScrollView measures its child with an UNSPECIFIED width whatever its layout params say.
 * A test that read the flag back would have passed against that.
 *
 * The holder is shared by the code, Mermaid and YAML lanes and survives `swapAdapter(_, false)`, so
 * a setting change re-binds holders that were bound under the old value — hence the recycle cases.
 *
 * NATIVE graphics: legacy Robolectric text layout bounds the TextView's width but never breaks the
 * line, so `lineCount` would read 1 even when the block is correctly wrapped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FencedCodeBlockEntryWrapTest {

    private lateinit var context: Context

    private val longLine = "val sample = listOf(" + (1..60).joinToString(", ") { "\"item$it\"" } + ")"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** A Markwon that renders the node's literal as plain text, so layout is the only variable. */
    private fun literalMarkwon(): Markwon = mockk<Markwon>(relaxed = true).also { m ->
        every { m.render(any()) } answers { android.text.SpannableString(firstArg<FencedCodeBlock>().literal) }
        every { m.setParsedMarkdown(any(), any()) } answers { firstArg<TextView>().text = secondArg() }
    }

    private fun codeNode(info: String? = null) = FencedCodeBlock().apply {
        literal = longLine
        this.info = info
    }

    private fun holderOf(entry: FencedCodeBlockEntry) =
        entry.createHolder(LayoutInflater.from(context), FrameLayout(context))

    /** Lay the block out at a phone-ish card width, as the RecyclerView would. */
    private fun layOut(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    private fun assertWrapped(holder: FencedCodeBlockEntry.Holder) {
        layOut(holder.itemView)
        assertTrue("a wrapped block spans several lines", holder.codeView.lineCount > 1)
        assertTrue(
            "a wrapped block fits its viewport (nothing to side-scroll)",
            holder.codeView.width <= holder.codeScroll.width,
        )
    }

    private fun assertSideScrolls(holder: FencedCodeBlockEntry.Holder) {
        layOut(holder.itemView)
        assertEquals("an unwrapped block keeps its long line on one line", 1, holder.codeView.lineCount)
        assertTrue(
            "an unwrapped block is wider than its viewport, so it side-scrolls",
            holder.codeView.width > holder.codeScroll.width,
        )
    }

    @Test
    fun `default is side-scroll, exactly as before`() {
        val entry = FencedCodeBlockEntry(context)
        val holder = holderOf(entry)
        entry.bindHolder(literalMarkwon(), holder, codeNode())
        assertSideScrolls(holder)
    }

    @Test
    fun `with the setting on, a long line wraps inside the block`() {
        val entry = FencedCodeBlockEntry(context, wrapLines = true)
        val holder = holderOf(entry)
        entry.bindHolder(literalMarkwon(), holder, codeNode())
        assertWrapped(holder)
    }

    @Test
    fun `a holder recycled from a wrapped bind side-scrolls again once the setting is off`() {
        val holder = holderOf(FencedCodeBlockEntry(context))
        FencedCodeBlockEntry(context, wrapLines = true).bindHolder(literalMarkwon(), holder, codeNode())
        assertWrapped(holder)

        FencedCodeBlockEntry(context, wrapLines = false).bindHolder(literalMarkwon(), holder, codeNode())
        assertSideScrolls(holder)
    }

    @Test
    fun `a failed bind still clears a wrap left on the holder`() {
        val holder = holderOf(FencedCodeBlockEntry(context))
        FencedCodeBlockEntry(context, wrapLines = true).bindHolder(literalMarkwon(), holder, codeNode())

        val throwing = mockk<Markwon>()
        every { throwing.render(any()) } throws RuntimeException("boom")
        every { throwing.setParsedMarkdown(any(), any()) } throws RuntimeException("boom")
        FencedCodeBlockEntry(context, wrapLines = false).bindHolder(throwing, holder, codeNode())
        // The degraded text is short, so line count proves nothing here — read the scroller's mode by
        // what it does to a long line placed back into the view.
        holder.codeView.text = longLine
        assertSideScrolls(holder)
    }

    @Test
    fun `the Mermaid-source lane follows the setting`() {
        val entry = FencedCodeBlockEntry(context, wrapLines = true)
        val holder = holderOf(entry)
        entry.bindMermaidOff(literalMarkwon(), holder, codeNode("mermaid"))
        assertWrapped(holder)
    }

    @Test
    fun `the Mermaid-image lane clears a wrap, so its source fallback side-scrolls`() {
        val holder = holderOf(FencedCodeBlockEntry(context))
        FencedCodeBlockEntry(context, wrapLines = true).bindHolder(literalMarkwon(), holder, codeNode())

        FencedCodeBlockEntry(context, wrapLines = false).bindMermaid(literalMarkwon(), holder, codeNode("mermaid"))
        // A failed fetch lands in fallbackToSource asynchronously, which re-shows the code views
        // with the source; stand in for it here so the scroller's mode is what gets measured.
        holder.codeScroll.visibility = View.VISIBLE
        holder.codeView.text = longLine
        assertSideScrolls(holder)
    }

    @Test
    fun `the frontmatter card clears a wrap left on the shared holder`() {
        val holder = holderOf(FencedCodeBlockEntry(context))
        FencedCodeBlockEntry(context, wrapLines = true).bindHolder(literalMarkwon(), holder, codeNode())
        assertWrapped(holder)

        val yaml = FencedCodeBlock().apply {
            info = "yaml"
            literal = "title: $longLine\n"
        }
        FrontmatterBlockEntry(context).bindHolder(literalMarkwon(), holder, yaml)
        layOut(holder.itemView)
        assertTrue(
            "the frontmatter card keeps side-scroll whatever the setting",
            holder.codeView.width > holder.codeScroll.width,
        )
    }

    @Test
    fun `the reader adapter passes the setting through to code blocks`() {
        // End-to-end through RecyclerAdapterEntries, so a dropped parameter anywhere between the
        // adapter builder and the entry fails here rather than only on a device.
        val markwon = Markwon.create(context)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(context, markwon, wrapCodeLines = true)
        adapter.setMarkdown(markwon, "```kotlin\n$longLine\n```\n")
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        assertWrapped(holder as FencedCodeBlockEntry.Holder)
    }

    private companion object {
        const val WIDTH_PX = 480
    }
}
