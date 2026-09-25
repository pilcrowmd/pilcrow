// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PreviewLineHeightMultiplier
import io.mockk.mockk
import io.noties.markwon.Markwon
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards **M-108** — a metadata card whose font failed to load must still look like a metadata
 * card, whatever the shared `adapter_code_block` holder last showed.
 *
 * The failure is triggered the same honest way as [FrontmatterBlockEntrySizeTest]: a [FontSet]
 * whose reading font does not resolve, so the real `getFont` — the first call that can throw —
 * throws. Each test pins ONE property, with a fixture in which only that property can decide it:
 *
 * - **typeface** and **background** need a holder a code block bound first, because that is the
 *   only way the holder carries the mono face and the code surface. The background test uses
 *   [LightColorScheme], because in Dark `codeBlockBg` and `secondarySurface` are the same colour
 *   and the assertion could not tell the two surfaces apart.
 * - **line spacing** needs a FRESH holder, because every successful lane sets the same
 *   [PreviewLineHeightMultiplier]; only the XML default differs.
 */
@RunWith(RobolectricTestRunner::class)
class FrontmatterBlockEntryDegradedTest {

    private lateinit var context: Context

    /** A font set whose reading font does not resolve, so the real `getFont` throws. */
    private val brokenFonts = FontSets.DEFAULT.copy(readingRegular = 0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun frontmatterNode(): FencedCodeBlock =
        FencedCodeBlock().apply { literal = "title: A Note\nauthor: someone\n" }

    private fun codeNode(): FencedCodeBlock = FencedCodeBlock().apply {
        info = "kotlin"
        literal = "val x = 1\n"
    }

    private fun freshHolder(): FencedCodeBlockEntry.Holder =
        FrontmatterBlockEntry(context).createHolder(LayoutInflater.from(context), FrameLayout(context))

    /** A holder a healthy code block has just bound: mono face, code surface. */
    private fun holderLeftByCodeBlock(): FencedCodeBlockEntry.Holder {
        val holder = freshHolder()
        FencedCodeBlockEntry(context, colorScheme = LightColorScheme)
            .bindHolder(mockk<Markwon>(relaxed = true), holder, codeNode())
        return holder
    }

    private fun bindDegraded(holder: FencedCodeBlockEntry.Holder) {
        FrontmatterBlockEntry(context, fontSet = brokenFonts, colorScheme = LightColorScheme)
            .bindHolder(mockk<Markwon>(relaxed = true), holder, frontmatterNode())
        // Without this the tests below could pass on a quiet success rather than the catch.
        assertEquals(
            "precondition: the catch renders the raw literal, so the try must have thrown",
            frontmatterNode().literal,
            holder.codeView.text.toString(),
        )
    }

    private fun surfaceColor(holder: FencedCodeBlockEntry.Holder): Int? =
        (holder.codeScroll.background as? GradientDrawable)?.color?.defaultColor

    @Test
    fun `a degraded card does not keep a code block's mono typeface`() {
        val holder = holderLeftByCodeBlock()
        assertNotEquals(
            "precondition: the code block must have left a non-default typeface",
            Typeface.DEFAULT,
            holder.codeView.typeface,
        )

        bindDegraded(holder)

        assertEquals(Typeface.DEFAULT, holder.codeView.typeface)
    }

    @Test
    fun `a degraded card does not keep a code block's surface`() {
        val holder = holderLeftByCodeBlock()
        assertEquals(
            "precondition: the code block must have left its own surface",
            LightColorScheme.codeBlockBg.toArgb(),
            surfaceColor(holder),
        )

        bindDegraded(holder)

        assertEquals(LightColorScheme.secondarySurface.toArgb(), surfaceColor(holder))
    }

    @Test
    fun `a degraded card on a fresh holder gets the reading line spacing`() {
        val holder = freshHolder()
        assertNotEquals(
            "precondition: a fresh holder must start at a different spacing",
            PreviewLineHeightMultiplier,
            holder.codeView.lineSpacingMultiplier,
            0.001f,
        )

        bindDegraded(holder)

        assertEquals(PreviewLineHeightMultiplier, holder.codeView.lineSpacingMultiplier, 0.001f)
    }
}
