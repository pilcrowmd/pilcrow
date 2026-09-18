// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards **M-04** on the fenced-code lane.
 *
 * The live pinch writes a PX size straight onto the attached `TextView`, and the end-of-gesture
 * rebuild uses `swapAdapter(_, false)`, which **re-binds** existing holders rather than recreating
 * them — so `createHolder` never runs again for a reused holder. Every entry must therefore
 * re-apply its size on bind, and it must do so on the **degraded** path too: the size reset used to
 * sit inside the `try` *after* `markwon.setParsedMarkdown`, so a block that failed to render kept
 * the gesture's size for good. The one path that had already failed was the one left mis-sized.
 */
@RunWith(RobolectricTestRunner::class)
class FencedCodeBlockEntrySizeTest {

    private lateinit var context: Context
    private lateinit var entry: FencedCodeBlockEntry
    private lateinit var holder: FencedCodeBlockEntry.Holder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        entry = FencedCodeBlockEntry(context)
        holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
    }

    private fun codeNode(): FencedCodeBlock = FencedCodeBlock().apply { literal = "val x = 1\n" }

    /** The size a healthy bind settles on — the committed size this entry owes every bind. */
    private fun committedSize(): Float {
        val healthy = mockk<Markwon>(relaxed = true)
        every { healthy.render(any()) } returns android.text.SpannableString("code")
        entry.bindHolder(healthy, holder, codeNode())
        return holder.codeView.textSize
    }

    @Test
    fun `a recycled holder recovers its text size on a healthy bind`() {
        val committed = committedSize()
        assertTrue("precondition: a real size must have been applied", committed > 0f)

        holder.codeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, committed * 2.5f)
        assertNotEquals(committed, holder.codeView.textSize, 0.001f)

        val healthy = mockk<Markwon>(relaxed = true)
        every { healthy.render(any()) } returns android.text.SpannableString("code")
        entry.bindHolder(healthy, holder, codeNode())

        assertEquals(committed, holder.codeView.textSize, 0.001f)
    }

    @Test
    fun `a FAILED bind also restores the text size`() {
        // This is the case the old ordering missed entirely: the catch restores text and colour but
        // never touched the size, so a failed block kept whatever the pinch had left on it.
        val committed = committedSize()
        holder.codeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, committed * 2.5f)

        val throwing = mockk<Markwon>()
        every { throwing.render(any()) } throws RuntimeException("boom")
        every { throwing.setParsedMarkdown(any(), any()) } throws RuntimeException("boom")

        entry.bindHolder(throwing, holder, codeNode())

        assertEquals(
            "a block that failed to render must not keep the gesture's size",
            committed,
            holder.codeView.textSize,
            0.001f,
        )
    }

    @Test
    fun `the chrome is excluded from pinch scaling by role`() {
        // Neither takes its size from the reader font scale, so nothing re-applies it on bind; the
        // scaler must skip them or they stay wrong at rest for good.
        assertNotEquals(null, holder.copyButton.getTag(com.pilcrowmd.R.id.pinch_excluded))
        assertNotEquals(null, holder.mermaidCaption.getTag(com.pilcrowmd.R.id.pinch_excluded))
    }
}
