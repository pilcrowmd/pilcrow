// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.graphics.Typeface
import android.text.Spanned
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.CalloutKind
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import io.noties.markwon.core.spans.BlockQuoteSpan
import org.commonmark.node.HtmlBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-161 through the real render chain. The painted text is asserted character for character,
 * because search models exactly that text (SearchCalloutsAndDetailsTest) and the two must agree.
 */
@RunWith(RobolectricTestRunner::class)
class CalloutAndDetailsRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val markwon = buildPilcrowMarkwon(context)

    private fun firstBlock(markdown: String) = ReaderDocument.transform(markwon.parse(markdown)).firstChild!!

    @Test
    fun `the render parser's split marker still makes a callout`() {
        // Markwon's inline parser may hand the marker over as several Text nodes; the transform
        // reads the whole first line, so this is the case that proves it.
        val node = firstBlock("> [!WARNING]\n> Mind the gap.")
        assertTrue(node is CalloutBlock)
        assertEquals(CalloutKind.WARNING, (node as CalloutBlock).kind)
    }

    @Test
    fun `a callout paints its title then its body, with no quote bar, and the title takes its colour`() {
        val rendered = markwon.render(firstBlock("> [!TIP]\n> Mind the gap.")) as Spanned
        assertEquals("Tip\nMind the gap.", rendered.toString().trim())
        assertEquals(0, rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java).size)
        CalloutStyle.decorateTitles(context, rendered, LightColorScheme, 1f)
        val title = rendered.getSpans(0, rendered.length, CalloutTitleSpan::class.java).single()
        assertEquals(LightColorScheme.callouts.tip.toArgb(), title.color)
        assertEquals(
            "the icon sits in the title's margin",
            1,
            rendered.getSpans(0, 3, LeadingIconSpan::class.java).size,
        )
    }

    @Test
    fun `a details header paints its summary, and its inline body only while open`() {
        val header = Details.parseHeader(
            (firstBlock("<details><summary>Tight</summary>Its body.</details>") as HtmlBlock).literal,
        )!!
        val style = DetailsHeaderStyle(Typeface.DEFAULT_BOLD, DarkColorScheme.accent.toArgb(), 1f)
        val closed = detailsHeaderText(context, header, expanded = false, style)
        val open = detailsHeaderText(context, header, expanded = true, style)
        assertEquals("Tight", closed.toString())
        assertEquals("Tight\nIts body.", open.toString())
    }

    @Test
    fun `an unknown callout type still renders as a quote`() {
        val rendered = markwon.render(firstBlock("> [!FOO]\n> Body")) as Spanned
        assertEquals(1, rendered.getSpans(0, rendered.length, BlockQuoteSpan::class.java).size)
        assertTrue(rendered.toString().contains("[!FOO]"))
    }
}
