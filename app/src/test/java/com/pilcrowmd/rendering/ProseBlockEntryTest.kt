// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.ParagraphSplitter
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.noties.markwon.Markwon
import org.commonmark.node.Document
import org.commonmark.node.Heading
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the ProseBlockEntry render guard (Safeguard 3): the default prose lane was the one
 * render path without a try/catch, so a throwing render crashed the whole adapter. A failure must
 * degrade to the fallback text in secondaryText color — never propagate.
 */
@RunWith(RobolectricTestRunner::class)
class ProseBlockEntryTest {

    private lateinit var context: Context
    private lateinit var entry: ProseBlockEntry
    private lateinit var holder: ProseBlockEntry.Holder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entry = ProseBlockEntry(context, ReaderWords.ENGLISH)
        holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
    }

    private fun paragraphNode(text: String): Paragraph = Paragraph().apply { appendChild(Text(text)) }

    @Test
    fun `render failure degrades to fallback text instead of throwing`() {
        val markwon = mockk<Markwon>()
        every { markwon.render(any()) } throws RuntimeException("boom")

        // Must not throw (Safeguard 3) — a throw here crashes the whole RecyclerView adapter.
        entry.bindHolder(markwon, holder, paragraphNode("hello"))

        assertEquals(ProseBlockEntry.RENDER_FALLBACK_TEXT, holder.textView.text.toString())
        assertEquals(DarkColorScheme.secondaryText.toArgb(), holder.textView.currentTextColor)
    }

    @Test
    fun `setParsedMarkdown failure also degrades to fallback text`() {
        val markwon = mockk<Markwon>()
        every { markwon.render(any()) } returns android.text.SpannableString("rendered")
        every { markwon.setParsedMarkdown(any(), any()) } throws IllegalStateException("bad span")

        entry.bindHolder(markwon, holder, paragraphNode("hello"))

        assertEquals(ProseBlockEntry.RENDER_FALLBACK_TEXT, holder.textView.text.toString())
    }

    @Test
    fun `recycled holder recovers primaryText color after a failed bind`() {
        val throwing = mockk<Markwon>()
        every { throwing.render(any()) } throws RuntimeException("boom")
        entry.bindHolder(throwing, holder, paragraphNode("bad"))
        assertEquals(DarkColorScheme.secondaryText.toArgb(), holder.textView.currentTextColor)

        // Same holder, healthy node: the fallback's secondaryText must not bleed through.
        val markwon = Markwon.create(context)
        entry.bindHolder(markwon, holder, markwon.parse("recovered").firstChild!!)

        assertEquals("recovered", holder.textView.text.toString())
        assertEquals(DarkColorScheme.primaryText.toArgb(), holder.textView.currentTextColor)
    }

    @Test
    fun `happy path renders markdown text unchanged (guard is transparent)`() {
        val markwon = Markwon.create(context)
        val node = markwon.parse("plain prose line").firstChild!!

        entry.bindHolder(markwon, holder, node)

        assertEquals("plain prose line", holder.textView.text.toString())
        assertTrue(holder.textView.currentTextColor == DarkColorScheme.primaryText.toArgb())
    }

    @Test
    fun `recycled holder recovers its text size after the live pinch scaled it`() {
        // M-04. The pinch writes a PX size straight onto the attached TextView, and the
        // end-of-gesture rebuild calls `swapAdapter(_, false)`, which RE-BINDS existing holders
        // instead of recreating them — so `createHolder` never runs again for a reused holder. If
        // bindHolder does not re-apply the size, the gesture's size survives at rest forever, which
        // is the reported symptom: title huge, the paragraph under it tiny.
        val markwon = mockk<Markwon>(relaxed = true)
        every { markwon.render(any()) } returns android.text.SpannableString("rendered")
        val committed = holder.textView.textSize
        assertTrue("precondition: createHolder must have set a real size", committed > 0f)

        // Simulate the live pinch leaving the view at a wildly different size.
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, committed * 2.5f)
        assertNotEquals(committed, holder.textView.textSize, 0.001f)

        entry.bindHolder(markwon, holder, paragraphNode("hello"))

        assertEquals(
            "a rebound holder must come back at the committed size, not the gesture's",
            committed,
            holder.textView.textSize,
            0.001f,
        )
    }

    @Test
    fun `a failed bind still restores the text size`() {
        // The size reset must sit OUTSIDE the try, or the one path that already degrades (Safeguard
        // 3) would be the one path left carrying a stale size.
        val throwing = mockk<Markwon>()
        every { throwing.render(any()) } throws RuntimeException("boom")
        val committed = holder.textView.textSize
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, committed * 2.5f)

        entry.bindHolder(throwing, holder, paragraphNode("hello"))

        assertEquals(committed, holder.textView.textSize, 0.001f)
    }

    @Test
    fun `bound prose is a SpannableStringBuilder copy of Markwon's output, not a flat SpannableString`() {
        // M-322. TextView.setText(_, SPANNABLE) copies the text through its Spannable.Factory, whose
        // default is `new SpannableString(source)`: a flat array whose getSpans/nextSpanTransition
        // scan EVERY span with Class.isInstance. StaticLayout and Layout.draw call them per run and
        // per line, so one paragraph with 4,000 spans paid ~470 ms of layout and 2,000 inline
        // formulas ~200 ms of UI draw PER FRAME on the emulator (debug build) (profile: Class.isAssignableFrom
        // under StaticLayout and Layout.draw). Markwon's builder is an interval tree, O(log n).
        // The spy records the Spanned Markwon hands to the TextView, so the test can tell a copy from a
        // hand-over of the same object (the TextView holds a COPY, see KeepSpannableFactory's KDoc).
        val markwon = spyk(Markwon.create(context))
        val node = markwon.parse("**bold** and [link](https://example.com) and `code` and *em*").firstChild!!
        var handedOver: Spanned? = null
        every { markwon.setParsedMarkdown(any(), any()) } answers {
            handedOver = secondArg()
            callOriginal()
        }

        entry.bindHolder(markwon, holder, node)

        val text = holder.textView.text
        assertTrue("Markwon's own output is a SpannableStringBuilder", handedOver is SpannableStringBuilder)
        assertNotSame("the TextView must hold a copy, not Markwon's own builder", handedOver, text)
        assertTrue(
            "prose text must stay a SpannableStringBuilder (got ${text::class.java.name})",
            text is SpannableStringBuilder,
        )
        assertTrue("the text itself must be untouched", text.toString().startsWith("bold and link and"))
    }

    /**
     * A document holding one paragraph of 400 alternating text and strong children, cut by the splitter
     * into pieces that are then unlinked, as the adapter does before binding. Returns the pieces in
     * order, with the seams the splitter reported loaded into [state].
     */
    private fun splitPieces(state: ParagraphSeamState): List<Paragraph> {
        val document = Document()
        val paragraph = Paragraph().also { document.appendChild(it) }
        repeat(200) { i ->
            paragraph.appendChild(Text("word$i "))
            paragraph.appendChild(StrongEmphasis().apply { appendChild(Text("bold$i")) })
        }
        state.seams = ParagraphSplitter.split(document)
        val pieces = generateSequence(document.firstChild) { it.next }.filterIsInstance<Paragraph>().toList()
        pieces.forEach { it.unlink() }
        return pieces
    }

    @Test
    fun `the pieces of a split paragraph meet with no gap at the seam`() {
        val state = ParagraphSeamState()
        val pieces = splitPieces(state)
        assertTrue("400 children must have been cut into at least three pieces", pieces.size >= 3)
        val seamEntry = ProseBlockEntry(context, ReaderWords.ENGLISH, seams = state)
        val seamHolder = seamEntry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        val markwon = buildPilcrowMarkwon(context)
        val tv = seamHolder.textView
        val gap = (ProseBlockEntry.PARAGRAPH_VERTICAL_PADDING_DP * context.resources.displayMetrics.density).toInt()
        assertTrue("precondition: a real gap", gap > 0)

        seamEntry.bindHolder(markwon, seamHolder, pieces.first())
        assertEquals("first piece keeps the top gap", gap, tv.paddingTop)
        assertTrue("first piece keeps font padding", tv.includeFontPadding)
        assertEquals(
            "first piece ends flush with the next",
            seamBottomPadding(tv.paint.fontMetricsInt, tv.lineSpacingMultiplier, false, true),
            tv.paddingBottom,
        )

        seamEntry.bindHolder(markwon, seamHolder, pieces[1])
        assertEquals("middle piece has no top gap", 0, tv.paddingTop)
        assertFalse("middle piece drops font padding", tv.includeFontPadding)
        val fm = tv.paint.fontMetricsInt
        assertTrue(
            "precondition: the seam term is a real, non-zero padding under this graphics mode",
            seamBottomPadding(fm, tv.lineSpacingMultiplier, true, true) > 0,
        )
        assertEquals(
            "middle piece bottom is the seam arithmetic",
            seamBottomPadding(fm, tv.lineSpacingMultiplier, true, true),
            tv.paddingBottom,
        )

        seamEntry.bindHolder(markwon, seamHolder, pieces.last())
        assertEquals("last piece has no top gap", 0, tv.paddingTop)
        assertFalse("last piece drops font padding", tv.includeFontPadding)
        assertEquals(
            "last piece keeps the bottom gap plus the font padding it dropped at the top",
            gap + (fm.bottom - fm.descent),
            tv.paddingBottom,
        )
    }

    @Test
    fun `a holder that showed a piece recovers the usual paragraph gap and font padding`() {
        val state = ParagraphSeamState()
        val pieces = splitPieces(state)
        val seamEntry = ProseBlockEntry(context, ReaderWords.ENGLISH, seams = state)
        val seamHolder = seamEntry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        val markwon = buildPilcrowMarkwon(context)
        val tv = seamHolder.textView
        val gap = (ProseBlockEntry.PARAGRAPH_VERTICAL_PADDING_DP * context.resources.displayMetrics.density).toInt()

        seamEntry.bindHolder(markwon, seamHolder, pieces[1])
        assertFalse("precondition: the piece dropped font padding", tv.includeFontPadding)

        // The same recycled holder, now an ordinary paragraph: nothing of the piece may stay behind.
        seamEntry.bindHolder(markwon, seamHolder, paragraphNode("ordinary"))
        assertEquals(gap, tv.paddingTop)
        assertEquals(gap, tv.paddingBottom)
        assertTrue("font padding is back", tv.includeFontPadding)
    }

    @Test
    fun `a heading bound into a holder that showed a middle piece gets font padding and its own gaps`() {
        val state = ParagraphSeamState()
        val pieces = splitPieces(state)
        val seamEntry = ProseBlockEntry(context, ReaderWords.ENGLISH, seams = state)
        val seamHolder = seamEntry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        val markwon = buildPilcrowMarkwon(context)
        val tv = seamHolder.textView
        val density = context.resources.displayMetrics.density

        seamEntry.bindHolder(markwon, seamHolder, pieces[1])
        assertFalse("precondition: the middle piece dropped font padding", tv.includeFontPadding)

        val heading = Heading().apply {
            level = 2
            appendChild(Text("Title"))
        }
        seamEntry.bindHolder(markwon, seamHolder, heading)

        assertTrue("font padding is back for a heading", tv.includeFontPadding)
        assertEquals((ProseBlockEntry.HEADING_TOP_PADDING_DP * density).toInt(), tv.paddingTop)
        assertEquals((ProseBlockEntry.HEADING_BOTTOM_PADDING_DP * density).toInt(), tv.paddingBottom)
    }
}
