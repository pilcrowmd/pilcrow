// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.ReaderDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * A hyphenated word too wide for the line breaks after one of its hyphens, in every kind of reader
 * text. Laid out in the production adapter at a phone's width, with native graphics: the default
 * gives every glyph the same width, and no line would be too narrow for the word.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HyphenBreaksTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val markwon = buildPilcrowMarkwon(activity)

    private fun reader(markdown: String): RecyclerView {
        val state = DetailsState()
        val list = RecyclerView(activity)
        list.layoutManager = ReaderLayoutManager(activity)
        list.setTag(R.id.details_state, state)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(activity, markwon, details = state)
        val document = ReaderDocument.transform(markwon.parse(markdown))
        state.load(markdown, Details.sections(document))
        adapter.setParsedMarkdown(markwon, document)
        list.adapter = adapter
        activity.setContentView(list)
        shadowOf(Looper.getMainLooper()).idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, WIDTH, HEIGHT)
        shadowOf(Looper.getMainLooper()).idle()
        return list
    }

    /** Every TextView under [root] whose text holds [word], top to bottom. */
    private fun textsHolding(root: View, word: String): List<TextView> = when {
        root is TextView -> if (root.text.contains(word)) listOf(root) else emptyList()
        root is ViewGroup -> (0 until root.childCount).flatMap { textsHolding(root.getChildAt(it), word) }
        else -> emptyList()
    }

    /** The lines [tv] broke its text into. */
    private fun lines(tv: TextView): List<String> {
        val layout = tv.layout
        val text = tv.text.toString()
        return (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)) }
    }

    /** Wherever a line's end cuts a hyphenated word (no space on either side), the cut is after a hyphen. */
    private fun assertBrokenAtHyphens(tv: TextView, where: String) {
        val text = tv.text.toString()
        val layout = tv.layout
        val cuts = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
            .filter { it < text.length && !text[it - 1].isWhitespace() && !text[it].isWhitespace() }
        assertTrue(
            "$where: the word must be too wide for one line, or the case proves nothing: ${lines(tv)}",
            cuts.isNotEmpty(),
        )
        cuts.forEach { cut -> assertEquals("$where: ${lines(tv)}", '-', text[cut - 1]) }
    }

    @Test
    fun `a heading breaks a long hyphenated word after a hyphen`() {
        val tv = textsHolding(reader("# $WORD quadrilateral\n"), WORD).single()
        assertBrokenAtHyphens(tv, "heading")
    }

    @Test
    fun `a paragraph, a list item and a quote break it after a hyphen`() {
        val filler = "Some words before it, then "
        val list = reader("$filler$WORD.\n\n- $filler$WORD\n\n> $filler$WORD\n")
        val texts = textsHolding(list, WORD)
        assertEquals(3, texts.size)
        texts.forEachIndexed { i, tv -> assertBrokenAtHyphens(tv, "block $i") }
    }

    @Test
    fun `a footnote breaks it after a hyphen`() {
        val list = reader("Text[^1].\n\n[^1]: A note with $WORD in it.\n")
        val tv = textsHolding(list, WORD).single()
        assertBrokenAtHyphens(tv, "footnote")
    }

    @Test
    fun `a table cell breaks it after a hyphen`() {
        val list = reader("| a | b |\n|---|---|\n| $WORD | x |\n")
        val tv = textsHolding(list, WORD).single()
        assertBrokenAtHyphens(tv, "table cell")
    }

    @Test
    fun `a plain text file breaks it after a hyphen`() {
        val entry = PlainTextBlockEntry(activity)
        val tv = entry.createHolder(activity.layoutInflater, android.widget.FrameLayout(activity)).textView
        tv.text = "Some words before it, then $WORD."
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tv.layout(0, 0, WIDTH, tv.measuredHeight)
        assertBrokenAtHyphens(tv, "plain text")
    }

    private fun measured(tv: TextView, text: String): TextView {
        tv.text = text
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tv.layout(0, 0, WIDTH, tv.measuredHeight)
        return tv
    }

    @Test
    fun `text that wraps between words keeps hyphenation off, so no word is split by dictionary`() {
        // Hyphenated words that fit, and long plain words that wrap: nothing is cut inside a word.
        val text = "A second-level heading that also runs on extraordinarily long enough to wrap twice"
        val tv = measured(ReaderTextView(activity), text)
        assertTrue("the text must wrap, or the case proves nothing", tv.layout.lineCount > 1)
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NONE, tv.hyphenationFrequency)
        lines(tv).dropLast(1).forEach { assertTrue("a line ends between words: '$it'", it.last().isWhitespace()) }
    }

    @Test
    fun `a view that hyphenated one text starts the next with hyphenation off`() {
        val heading = ReaderTextView(activity).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, HEADING_PX)
        }
        val tv = measured(heading, "Before $WORD after")
        assertBrokenAtHyphens(tv, "first text")
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL, tv.hyphenationFrequency)
        measured(tv, "Plain words that wrap onto a second line without any trouble at all")
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NONE, tv.hyphenationFrequency)
    }

    @Test
    fun `a long word with no hyphen is not a reason to hyphenate`() {
        val tv = measured(ReaderTextView(activity), "Pneumonoultramicroscopicsilicovolcanoconiosis")
        assertTrue("the word must be too wide, or the case proves nothing", tv.layout.lineCount > 1)
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NONE, tv.hyphenationFrequency)
    }

    @Test
    fun `code is left to its own rule`() {
        val list = reader("```\n$WORD\n```\n")
        val tv = textsHolding(list, WORD).single()
        assertEquals(android.text.Layout.HYPHENATION_FREQUENCY_NONE, tv.hyphenationFrequency)
    }

    private companion object {
        const val WORD = "Reader-editor-writer-publisher"
        const val WIDTH = 240
        const val HEIGHT = 4000
        const val HEADING_PX = 40f
    }
}
