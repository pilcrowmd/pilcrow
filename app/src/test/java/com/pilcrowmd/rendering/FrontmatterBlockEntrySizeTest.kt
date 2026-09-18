// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.FontSets
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
 * Guards **M-107** — the same rule as [FencedCodeBlockEntrySizeTest], on the metadata-card lane.
 *
 * The live pinch writes a PX size straight onto the attached `TextView`, and the end-of-gesture
 * rebuild uses `swapAdapter(_, false)`, which **re-binds** existing holders rather than recreating
 * them. So every entry owes its size on **every** bind — including the degraded one. This entry
 * set its size *inside* the `try`, after `ResourcesCompat.getFont`, so a card whose font failed to
 * load kept whatever size the gesture had left on it, permanently.
 *
 * **The failure is triggered honestly, not mocked:** the entry is given a [FontSet] whose reading
 * font cannot be resolved, which is precisely what makes the real `getFont` call throw. That call
 * is the FIRST statement of the try, so it is the only vector that reaches the catch *before* the
 * size would have been set — which is what makes this test able to tell the two orderings apart.
 * A throw from anywhere later (`buildCardText`, `setText`) happens after the old ordering had
 * already applied the size, and would pass against the bug.
 */
@RunWith(RobolectricTestRunner::class)
class FrontmatterBlockEntrySizeTest {

    private lateinit var context: Context

    /** A font set whose reading font does not resolve, so the real `getFont` throws. */
    private val brokenFonts = FontSets.DEFAULT.copy(readingRegular = 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun frontmatterNode(): FencedCodeBlock =
        FencedCodeBlock().apply { literal = "title: A Note\nauthor: someone\n" }

    private fun holderFor(entry: FrontmatterBlockEntry): FencedCodeBlockEntry.Holder =
        entry.createHolder(LayoutInflater.from(context), FrameLayout(context))

    @Test
    fun `a FAILED bind restores the text size the gesture overwrote`() {
        // The committed size this entry owes every bind, taken from a healthy entry.
        val healthy = FrontmatterBlockEntry(context)
        val holder = holderFor(healthy)
        healthy.bindHolder(mockk(relaxed = true), holder, frontmatterNode())
        val committed = holder.codeView.textSize
        assertTrue("precondition: a real size must have been applied", committed > 0f)

        // A pinch leaves an arbitrary PX size on the view.
        holder.codeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, committed * 2.5f)
        assertNotEquals(
            "precondition: the view must actually be mis-sized before the bind",
            committed,
            holder.codeView.textSize,
            0.001f,
        )

        // Re-bound through an entry whose font cannot load: the render degrades (Safeguard 3) and
        // must still leave the card at the committed size.
        val degrading = FrontmatterBlockEntry(context, fontSet = brokenFonts)
        degrading.bindHolder(mockk<Markwon>(relaxed = true), holder, frontmatterNode())

        assertEquals(
            "a card that failed to render must not keep the gesture's size",
            committed,
            holder.codeView.textSize,
            0.001f,
        )
    }

    @Test
    fun `the degraded bind really is the degraded path, not a quiet success`() {
        // Without this the test above could pass for the wrong reason: if getFont stopped throwing,
        // the happy path would set the size and the assertion would still hold, while proving
        // nothing about the catch. Pin the catch's own observable effect — the raw literal.
        val degrading = FrontmatterBlockEntry(context, fontSet = brokenFonts)
        val holder = holderFor(degrading)

        degrading.bindHolder(mockk<Markwon>(relaxed = true), holder, frontmatterNode())

        assertEquals(
            "the catch renders the raw literal; if this is the formatted card, the try did not throw",
            frontmatterNode().literal,
            holder.codeView.text.toString(),
        )
    }
}
