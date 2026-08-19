// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
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
        entry = ProseBlockEntry(context)
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
}
