// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-132 / M-133 — the colour each code token is actually drawn in, through the production plugin
 * chain.
 *
 * Each probe names a character that only one token covers, and reads the innermost
 * [ForegroundColorSpan] over it. So the only thing that can decide a probe is the mapping line for
 * that token: remove the line and the character falls back to the default code text colour.
 *
 * The PDF instance is checked the other way round: every colour it draws in these blocks must be the
 * default code text colour. The diff fixture carries a quoted string on a context line, so if the
 * `diff` alias ever reached the PDF instance, that string would be coloured and the check would fail.
 */
@RunWith(RobolectricTestRunner::class)
class CodeTokenColoursTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val markdownBlock =
        "```markdown\n# Title\nSome **bold** and *italic*, a [link](https://example.com) and `code`.\n\n" +
            "- list item\n> quoted\n```"
    private val diffBlock = "```diff\n@@ -1,3 +1,3 @@\n val kept = \"same\"\n-val removed = 1\n+val added = 2\n```"

    private fun render(markwon: Markwon, markdown: String): Spanned = markwon.render(markwon.parse(markdown)) as Spanned

    /** Colour of the innermost foreground span over the first character of [probe]. */
    private fun colourAt(text: Spanned, probe: String): Int? {
        val at = text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        return text.getSpans(at, at + 1, ForegroundColorSpan::class.java)
            .minByOrNull { text.getSpanEnd(it) - text.getSpanStart(it) }
            ?.foregroundColor
    }

    private fun assertMarkdownTokens(scheme: PilcrowColorScheme) {
        val text = render(buildPilcrowMarkwon(context, scheme), markdownBlock)
        val syntax = scheme.codeSyntax
        assertEquals("heading", syntax.heading!!.toArgb(), colourAt(text, "Title"))
        assertEquals("bold", syntax.emphasis!!.toArgb(), colourAt(text, "bold"))
        assertEquals("italic", syntax.emphasis!!.toArgb(), colourAt(text, "italic"))
        assertEquals("url", syntax.link!!.toArgb(), colourAt(text, "https"))
        assertEquals("list marker", syntax.marker!!.toArgb(), colourAt(text, "- list"))
        assertEquals("blockquote", syntax.marker!!.toArgb(), colourAt(text, "> quoted"))
        assertEquals("inline code", syntax.literal!!.toArgb(), colourAt(text, "`code`"))
    }

    private fun assertDiffTokens(scheme: PilcrowColorScheme) {
        val text = render(buildPilcrowMarkwon(context, scheme), diffBlock)
        val syntax = scheme.codeSyntax
        assertEquals("hunk header", syntax.marker!!.toArgb(), colourAt(text, "@@"))
        assertEquals("removed line", syntax.deleted!!.toArgb(), colourAt(text, "-val removed"))
        assertEquals("added line", syntax.inserted!!.toArgb(), colourAt(text, "+val added"))
    }

    @Test
    fun markdownTokensAreColouredInDark() = assertMarkdownTokens(DarkColorScheme)

    @Test
    fun markdownTokensAreColouredInLight() = assertMarkdownTokens(LightColorScheme)

    @Test
    fun diffIsHighlightedAsGitInDark() = assertDiffTokens(DarkColorScheme)

    @Test
    fun diffIsHighlightedAsGitInLight() = assertDiffTokens(LightColorScheme)

    @Test
    fun thePdfInstanceDrawsTheseBlocksInTheDefaultCodeTextOnly() {
        val print = buildPrintMarkwon(context)
        val plain = DarkColorScheme.editorText.toArgb()
        for (block in listOf(markdownBlock, diffBlock)) {
            val text = render(print, block)
            val colours = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                .map { it.foregroundColor }.toSet()
            assertEquals("PDF colours in ${block.substringBefore('\n')}", emptySet<Int>(), colours - plain)
        }
    }
}
