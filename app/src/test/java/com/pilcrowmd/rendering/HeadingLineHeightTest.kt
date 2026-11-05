// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.noties.markwon.Markwon
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * S8-A75 — the space between the lines of a wrapped reader heading.
 *
 * A heading is its own TextView, so the line pitch inside it is the multiplier times the font's own
 * line height. The entry sets the multiplier per bind so that pitch is [PilcrowTypography.HEADING_LINE_HEIGHT]
 * of the font size; a paragraph bound into the same (recycled) holder must get the reading multiplier
 * back. Both asserts read the TextView after the synchronous `bindHolder`, which sets the spacing
 * itself — there is no asynchronous hop to wait on.
 *
 * Seen failing with the `setLineSpacing` call in `bindHolder` removed: the heading keeps the 1.35
 * the holder was created with.
 */
@RunWith(RobolectricTestRunner::class)
// Real font metrics: the legacy graphics mode reports zero ascent and descent.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeadingLineHeightTest {

    private lateinit var context: Context
    private lateinit var markwon: Markwon

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        markwon = buildPilcrowMarkwon(context)
    }

    private fun block(markdown: String): Node {
        val node = markwon.parse(markdown).firstChild
        node.unlink() // the adapter binds each block detached from the document
        return node
    }

    /** Line pitch of [tv] as a multiple of its font size: multiplier x natural line height / size. */
    private fun pitchInEm(tv: TextView): Float {
        val fm = tv.paint.fontMetrics
        return tv.lineSpacingMultiplier * (fm.descent - fm.ascent) / tv.textSize
    }

    @Test
    fun `a heading's lines sit 1_2 x the font size apart`() {
        val entry = ProseBlockEntry(context, ReaderWords.ENGLISH)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, block("# A heading"))
        val tv = holder.textView
        println("S8-A75 heading multiplier=${tv.lineSpacingMultiplier} pitch=${pitchInEm(tv)}")
        assertEquals(PilcrowTypography.HEADING_LINE_HEIGHT, pitchInEm(holder.textView), 0.01f)
    }

    @Test
    fun `a paragraph bound into the same holder gets the reading multiplier back`() {
        val entry = ProseBlockEntry(context, ReaderWords.ENGLISH)
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, block("# A heading"))
        entry.bindHolder(markwon, holder, block("A paragraph."))
        println("S8-A75 paragraph multiplier=${holder.textView.lineSpacingMultiplier}")
        assertEquals(PreviewLineHeightMultiplier, holder.textView.lineSpacingMultiplier, 0.0001f)
    }
}
