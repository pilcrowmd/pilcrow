// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.AssetEmojiTable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-247: search must model a `:shortcode:` exactly as the reader paints it, or a match's ordinal
 * points at the wrong highlight. Each case renders the paragraph with the real renderer, counts the
 * query in the painted text the way the highlighter does, and compares that with the search model,
 * once with the emoji drawn and once with the device unable to draw them.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiShortcodeSearchParityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shortcodes(canDraw: Boolean) =
        EmojiShortcodes(AssetEmojiTable({ context.assets.open(AssetEmojiTable.ASSET_PATH) }, { canDraw }))

    /** Painted offsets of [query] in the first block, as the highlighter finds them. */
    private fun paintedOffsets(shortcodes: EmojiShortcodes, markdown: String, query: String): List<Int> {
        val markwon = buildPilcrowMarkwon(context, emoji = shortcodes)
        val block = ReaderDocument.transform(markwon.parse(markdown)).firstChild!!
        return searchableMatchOffsets(markwon.render(block), query)
    }

    private fun modelled(shortcodes: EmojiShortcodes, markdown: String, query: String): List<SearchMatch> =
        SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase(), shortcodes)
            .findSearchMatches(markdown, query)
            .filter { it.adapterPosition == 0 }

    private fun assertParity(canDraw: Boolean, markdown: String, queries: List<String>) {
        val shortcodes = shortcodes(canDraw)
        for (query in queries) {
            val painted = paintedOffsets(shortcodes, markdown, query)
            val model = modelled(shortcodes, markdown, query)
            assertEquals("count of \"$query\" (drawn=$canDraw) in <$markdown>", painted.size, model.size)
            val ordinals = model.map { it.occurrenceInBlock }
            assertEquals("ordinals of \"$query\" (drawn=$canDraw)", painted.indices.toList(), ordinals)
        }
    }

    @Test
    fun `a mixed paragraph counts the same painted and modelled, emoji drawn or not`() {
        for (canDraw in listOf(true, false)) assertParity(canDraw, MIXED, QUERIES)
    }

    @Test
    fun `escapes and entities in the same run do not hide a shortcode from search`() {
        // commonmark decodes `\#` and `&amp;`, so this run is not verbatim in the source.
        val markdown = "C\\# &amp; R\\_x :smile: and &copy; :tada: \\:smile: end"
        for (canDraw in listOf(true, false)) assertParity(canDraw, markdown, QUERIES)
        assertEquals(1, modelled(shortcodes(canDraw = true), markdown, "smile").size)
        val tada = modelled(shortcodes(canDraw = true), markdown, TADA).single()
        assertEquals(markdown.indexOf(":tada:"), tada.startIndex)
    }

    @Test
    fun `the letters of a drawn shortcode are not a match, a literal one's are`() {
        // MIXED's literal "smile"s: the code span, the escaped one, the bare word, the autolink.
        assertEquals(4, modelled(shortcodes(canDraw = true), MIXED, "smile").size)
        assertEquals(4, paintedOffsets(shortcodes(canDraw = true), MIXED, "smile").size)
        // Undrawable: the `:smile:` of `:smile::+1:` is literal too.
        assertEquals(5, modelled(shortcodes(canDraw = false), MIXED, "smile").size)
        assertEquals(5, paintedOffsets(shortcodes(canDraw = false), MIXED, "smile").size)
    }

    @Test
    fun `a drawn shortcode is anchored on its opening colon and later text keeps its offsets`() {
        val markdown = "a :tada: b tada"
        val emoji = modelled(shortcodes(canDraw = true), markdown, TADA).single()
        assertEquals(markdown.indexOf(":tada:"), emoji.startIndex)
        val word = modelled(shortcodes(canDraw = true), markdown, "tada").single()
        assertEquals(markdown.lastIndexOf("tada"), word.startIndex)
    }

    @Test
    fun `maths keeps a shortcode out of search`() {
        assertEquals(0, modelled(shortcodes(canDraw = true), "a \$x :smile: y\$ b", "smile").size)
        assertEquals(0, modelled(shortcodes(canDraw = true), "a \$x :smile: y\$ b", SMILE).size)
    }

    private companion object {
        const val SMILE = "😄"
        const val TADA = "🎉"

        const val MIXED = "Ship :tada: at 10:30:45, a:b:c, `:smile:` and \\:smile: stay; :smile::+1: then " +
            ":nosuch: smile [go :tada:](https://e.test/:smile:) <https://e.test/:smile:> **:-1:** end"

        val QUERIES = listOf("smile", "tada", ":", "1", "e", " ", TADA, SMILE)
    }
}
