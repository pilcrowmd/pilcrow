// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CalloutBlock
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.export.parseTopLevelBlocks
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.testing.readerModelFor
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-227 / M-251 on every parse site production has: the reader (its own parse, M-172, whose tree the
 * contents list reads, M-179), search's parser, and the PDF. A site that lacks the admonition parser
 * shows the block as a paragraph or code; one that reads a title differently paints a title search
 * does not find.
 */
@RunWith(RobolectricTestRunner::class)
class CalloutParseSitesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val table = AssetEmojiTable({ context.assets.open(AssetEmojiTable.ASSET_PATH) }, canDraw = { true })
    private val renderer = MarkwonRenderer(context, emoji = EmojiShortcodes(table))
    private val headings = ParseMarkdownHeadingsUseCase()

    private fun firstBlockAtEachSite(markdown: String): Map<String, Node> = mapOf(
        "reader" to renderer.readerModelFor(markdown).blocks.first(),
        "search" to AdapterBlocks.of(headings.parseDocument(markdown)!!).first(),
        "pdf" to parseTopLevelBlocks(renderer.printMarkwonFor(1f), markdown).first(),
    )

    @Test
    fun everyAdmonitionFormIsACalloutAtEverySite() {
        listOf(
            "!!! info \"Fieldwork\"\n    Body\n",
            "???+ tip\n    Body\n",
            "::: warning\nBody\n:::\n",
            "> [!TIP]- Fieldwork\n> Body\n",
        ).forEach { markdown ->
            firstBlockAtEachSite(markdown).forEach { (site, block) ->
                assertTrue("$site: \"$markdown\" is a ${block.javaClass.simpleName}", block is CalloutBlock)
            }
        }
    }

    @Test
    fun aTitleWithAFormulaAndAnEmojiReadsTheSameAtEverySite() {
        val titles = firstBlockAtEachSite("> [!NOTE] Fieldwork \$x\$ :smile:\n> Body\n")
            .mapValues { (_, block) -> (block as CalloutBlock).paintedTitle }
        assertEquals(mapOf("reader" to TITLE, "search" to TITLE, "pdf" to TITLE), titles)
    }

    private companion object {
        const val TITLE = "Fieldwork \$x\$ :smile:"
    }
}
