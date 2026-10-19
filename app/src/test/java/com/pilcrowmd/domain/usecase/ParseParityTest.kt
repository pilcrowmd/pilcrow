// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.buildPilcrowMarkwon
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.screenshot.MarkdownSampleProvider
import com.pilcrowmd.testing.bigParagraph
import com.pilcrowmd.testing.fencedCode
import com.pilcrowmd.testing.hardInlineParagraph
import com.pilcrowmd.testing.readerModelFor
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonReducer
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Enforces the block-model parity that search rests on: the `MarkwonAdapter` renders one item per
 * TOP-LEVEL node of `markwon.parse(content)`, while the TOC and search walk
 * [ParseMarkdownHeadingsUseCase.parseDocument]. If those two ever disagree about how many top-level
 * blocks a document has, adapter positions shift and both the TOC and search-scroll target the wrong
 * block — silently.
 *
 * That parity has been maintained by convention (the frontmatter `BlockParser` lives in the domain
 * layer precisely so both sides construct from one definition). Convention is not a mechanism, so
 * this test makes it a checked invariant. It retro-covers frontmatter, and any future custom block
 * parser added to one parser and forgotten in the other fails here first.
 *
 * **The invariant is the COUNT, not the types.** One type divergence exists by design and is pinned
 * below: with the `$$` fences on their own lines, ext-latex's block parser emits a `JLatexMathBlock`
 * in the render parser, where the parity parser — which deliberately omits the math plugins — sees an
 * ordinary `Paragraph`. Both are exactly ONE top-level block, so indices still line up, and both
 * contribute no searchable text (the math source is excluded from search by `mathRanges`). Any
 * divergence beyond that substitution is a real defect and fails.
 *
 * **The raw trees are not the adapter's items (M-214).** Both trees keep a link reference definition
 * as a top-level node, so comparing them cannot see that the adapter drops it. The second half of
 * this test compares [AdapterBlocks.of] on the parity tree with the list the adapter's own reducer
 * (`MarkwonReducer.directChildren()`) builds from the render tree — the numbering every adapter
 * position is taken from.
 *
 * **A split paragraph is parity too (M-328).** Both trees take [ReaderDocument.transform]'s paragraph
 * split, so a huge paragraph must come out in the same pieces from both. The one known gap: inline
 * child counts differ for formula paragraphs (the parity parser has no inline-math processor), so a
 * paragraph over the split limit that is mostly formulas can split differently in the two trees.
 * That is recorded under M-321 and is not pinned here.
 */
@RunWith(RobolectricTestRunner::class)
class ParseParityTest {

    private val parity = ParseMarkdownHeadingsUseCase()
    private val markwon by lazy { buildPilcrowMarkwon(ApplicationProvider.getApplicationContext()) }

    private fun topLevelTypes(node: Node): List<String> = buildList {
        var child = node.firstChild
        while (child != null) {
            add(child.javaClass.simpleName)
            child = child.next
        }
    }

    /** The one accepted substitution: a display-math block is a Paragraph to the parity parser. */
    private fun normalize(types: List<String>): List<String> =
        types.map { if (it == "JLatexMathBlock") "Paragraph" else it }

    private fun assertParity(name: String, markdown: String, markwon: Markwon = this.markwon) {
        val render = topLevelTypes(ReaderDocument.transform(markwon.parse(markdown)))
        val search = topLevelTypes(parity.parseDocument(markdown)!!)
        assertEquals(
            "top-level BLOCK COUNT must match for \"$name\" — adapter positions depend on it " +
                "(render=$render, parity=$search)",
            render.size,
            search.size,
        )
        assertEquals(
            "top-level block TYPES diverge for \"$name\" beyond the accepted display-math " +
                "substitution (render=$render, parity=$search)",
            normalize(render),
            normalize(search),
        )
    }

    @Test
    fun everyGoldenSampleParsesToTheSameBlockSequence() {
        // The samples that drive the 115 committed goldens — i.e. every block type the app renders.
        MarkdownSampleProvider().values.forEach { assertParity(it.name, it.markdown) }
    }

    @Test
    fun blockLevelConstructsWithCustomParsersStayInParity() {
        // Frontmatter is the existing custom BlockParser; display math is the known substitution.
        assertParity("frontmatter", "---\ntitle: t\nauthor: a\n---\n\nbody")
        assertParity("frontmatter unterminated", "---\ntitle: t\n\nbody")
        assertParity("display math on own lines", "before\n\n\$\$\n\\int x\n\$\$\n\nafter")
        assertParity("display math inline", "before\n\n\$\$x\$\$\n\nafter")
        assertParity("inline math", "a \$x^2\$ b")
        assertParity("table then para", "| a | b |\n|---|---|\n| 1 | 2 |\n\nafter")
    }

    @Test
    fun aHugeSpanParagraphSplitsIntoTheSamePiecesInBothParsers() {
        val md = "# Head\n\n" + bigParagraph(450) + "\n\n## Tail\n"

        val render = topLevelTypes(ReaderDocument.transform(markwon.parse(md)))
        val search = topLevelTypes(parity.parseDocument(md)!!)

        assertEquals("same pieces from both parsers (render=$render, parity=$search)", render, search)
        assertTrue("the paragraph was split: ${render.size} top-level blocks", render.size > 3)
        assertEquals(listOf("Heading") + List(render.size - 2) { "Paragraph" } + "Heading", render)
    }

    /**
     * Issue #17: a long code block is cut by every parse the reader's numbering rests on, in the same
     * pieces: the render parse and the parity parse (types and count), and the model the ViewModel
     * prepares for the reader (blocks), which cuts it itself, not through the default transform.
     */
    @Test
    fun aLongCodeBlockSplitsIntoTheSamePiecesInEveryParser() {
        val md =
            "# Head\n\n" + fencedCode(250, width = 60) + "\n## Tail\n\n" + fencedCode(60, info = "kotlin") + "\nEnd.\n"
        assertParity("long code block", md)

        val render = ReaderDocument.transform(markwon.parse(md))
        val search = AdapterBlocks.of(parity.parseDocument(md)!!)
        val reader = (
            MarkwonRenderer(ApplicationProvider.getApplicationContext()).readerModelFor(md) as ReaderModel.Markdown
            ).blocks
        val pieces = { blocks: List<Node> -> blocks.filterIsInstance<CodeBlockPiece>().map { it.literal } }

        assertEquals("same number of items", search.size, reader.size)
        assertEquals("same item types", search.map { it.javaClass.simpleName }, reader.map { it.javaClass.simpleName })
        assertEquals("same piece literals", pieces(search), pieces(reader))
        assertEquals("same piece literals from the render parse", pieces(search), pieces(AdapterBlocks.of(render)))
        assertEquals("250 lines in pieces of 100, 100 and 50", 3, pieces(reader).size)
        assertEquals(
            "the short block after the heading is one block",
            1,
            reader.count {
                it is org.commonmark.node.FencedCodeBlock &&
                    it !is CodeBlockPiece
            },
        )
        // The pieces carry the whole block, so Copy and the colours see the same text in every tree.
        val groups = { blocks: List<Node> ->
            blocks.filterIsInstance<CodeBlockPiece>().map { it.group.literal }.distinct()
        }
        assertEquals(groups(search), groups(reader))
        assertEquals(1, groups(reader).size)
    }

    private fun paragraphChildClasses(document: Node): List<String> = buildList {
        var block = document.firstChild
        while (block != null) {
            if (block is org.commonmark.node.Paragraph) {
                var child = block.firstChild
                while (child != null) {
                    add(child.javaClass.simpleName)
                    child = child.next
                }
            }
            block = block.next
        }
    }

    @Test
    fun aHugeParagraphOfHardInlineContentSplitsIntoTheSamePiecesInBothParsers() {
        val md = "# Head\n\n" + hardInlineParagraph(400) + "\n\n## Tail\n"

        val renderTree = ReaderDocument.transform(markwon.parse(md))
        val parityTree = parity.parseDocument(md)!!
        val render = topLevelTypes(renderTree)
        val search = topLevelTypes(parityTree)
        val renderChildren = paragraphChildClasses(renderTree)
        val parityChildren = paragraphChildClasses(parityTree)
        val firstDifference = (0 until maxOf(renderChildren.size, parityChildren.size))
            .firstOrNull { renderChildren.getOrNull(it) != parityChildren.getOrNull(it) }

        assertEquals(
            "piece counts: render=${render.size}, parity=${search.size}; first differing child index " +
                "$firstDifference (render=${firstDifference?.let { renderChildren.getOrNull(it) }}, " +
                "parity=${firstDifference?.let { parityChildren.getOrNull(it) }}); " +
                "inline children render=${renderChildren.size}, parity=${parityChildren.size}",
            render,
            search,
        )
        assertTrue("the paragraph was split: ${render.size} top-level blocks", render.size > 3)
    }

    /**
     * M-247: a shortcode is inline, so it must never change the block sequence, including where a
     * `:name:` sits next to block syntax (a heading, a table cell, a list item, a footnote).
     */
    @Test
    fun emojiShortcodesStayInBlockParity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val table = AssetEmojiTable({ context.assets.open(AssetEmojiTable.ASSET_PATH) }, canDraw = { true })
        val emoji = buildPilcrowMarkwon(context, emoji = EmojiShortcodes(table))
        assertParity("heading with a shortcode", "# Done :tada:\n\nbody :smile:", emoji)
        assertParity("table cell", "| a :+1: | b |\n|---|---|\n| :-1: | 2 |\n\nafter", emoji)
        assertParity("list and quote", "- :smile:\n- b\n\n> :tada: q\n", emoji)
        assertParity("footnote", "See[^1] :smile:\n\n[^1]: note :tada:\n", emoji)
        MarkdownSampleProvider().values.forEach { assertParity(it.name, it.markdown, emoji) }
    }

    @Test
    fun admonitionsParseToTheSameBlockSequence() {
        // M-251: the admonition block parser is registered on BOTH parsers (CalloutPlugin and the
        // parity parser). Registered on one side only, the other sees a paragraph or code block.
        val cases = mapOf(
            "python-markdown admonition" to
                "Before\n\n!!! info \"Title\"\n    One.\n\n    - a\n\n    Two.\n\nAfter",
            "admonition interrupting a paragraph" to "Before\n!!! note\n    Body\n\nAfter",
            "collapsible admonition" to "???+ tip\n    Body\n\nAfter",
            "fenced admonition" to "::: warning Title\nBody\n\n- a\n:::\nAfter",
            "fenced admonition after a stray opener" to ":::: note\nStray\n\n# Heading\n\n::: tip\nT\n:::\nLast",
            "fenced admonition holding a fence in code" to "::: note\n```\n:::\n```\n:::\nAfter",
            "github alert with title and fold sign" to "> [!TIP]- Title\n> Body\n\nAfter",
            "unknown admonition types beside known ones" to
                "!!! foo\n    Body\n\n!!! note\n    Body\n\n::: foo\nBody\n:::\n\n::: tip\nBody\n:::",
        )
        cases.forEach { (name, markdown) ->
            assertParity(name, markdown)
            assertAdapterParity(name, markdown)
            assertTrue(
                "\"$name\" must actually parse to a callout, or the parity above is vacuous",
                topLevelTypes(parity.parseDocument(markdown)!!).contains("CalloutBlock"),
            )
        }
    }

    private fun assertAdapterParity(name: String, markdown: String) {
        val adapter = MarkwonReducer.directChildren()
            .reduce(ReaderDocument.transform(markwon.parse(markdown)))
            .map { it.javaClass.simpleName }
        val numbered = AdapterBlocks.of(parity.parseDocument(markdown)!!).map { it.javaClass.simpleName }
        assertEquals(
            "AdapterBlocks must number \"$name\" as the adapter does (adapter=$adapter, numbered=$numbered)",
            normalize(adapter),
            normalize(numbered),
        )
    }

    @Test
    fun adapterBlocksNumberEveryGoldenSampleAsTheAdapterDoes() {
        MarkdownSampleProvider().values.forEach { assertAdapterParity(it.name, it.markdown) }
    }

    @Test
    fun adapterBlocksSkipLinkReferenceDefinitionsAsTheAdapterDoes() {
        // Not the empty document: the reducer returns the Document itself as one item when it has no
        // children, a quirk no index is ever taken from (there is nothing to jump to).
        assertAdapterParity("definition before a heading", "[r]: https://x.test\n\n# Head\n\nneedle here")
        assertAdapterParity("definition between blocks", "one\n\n[r]: https://x.test\n\n# Two\n\nthree")
        assertAdapterParity(
            "two consecutive definitions",
            "[a]: https://a.test\n[b]: https://b.test\n\n# Head\n\nbody [a] and [b]",
        )
        assertAdapterParity(
            "definition inside details",
            "<details>\n<summary>S</summary>\n\n[r]: https://x.test\n\nbody\n</details>\n\nafter",
        )
        assertAdapterParity("only definitions", "[a]: https://a.test\n\n[b]: https://b.test\n")
        assertAdapterParity("definition after the last block", "# Head\n\ntext\n\n[r]: https://x.test\n")
        assertAdapterParity(
            "definition before a footnote",
            "[r]: https://x.test\n\nText[^1] ref.\n\n[^1]: the note\n\nTail.\n",
        )
    }
}
