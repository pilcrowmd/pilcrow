// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Block
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockParserFactory
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/**
 * Footnote parsing primitives, the offset-safe way — the direct analogue of
 * [FrontmatterBlockParserFactory], and in the same layer for the same reason: the renderer, the PDF
 * export and the search/TOC parse must all share ONE definition of block structure, or adapter
 * positions diverge. Pure commonmark-java, no Android and no Markwon.
 *
 * **Why custom parsing at all.** The pinned parser line — Markwon 4.6.2 → `com.atlassian.commonmark`
 * 0.13.0 — never shipped a footnotes extension, and `app/build.gradle.kts` forbids adding
 * `org.commonmark:*` (two commonmark libraries on the classpath broke the recycler build). So the
 * only route is a custom block parser plus a post-parse pass, and that turns out to be enough.
 *
 * **Why the definition block is a CONTAINER, not a leaf.** Making it a container lets commonmark
 * parse the body into ordinary child nodes — emphasis, several paragraphs, nested lists all work,
 * and `SearchMarkdownUseCase.appendVisible` needs no new case for it (its existing "recurse into
 * children" branch already produces exactly the visible text the entry paints). A leaf holding a raw
 * string would have needed bespoke handling in search, in the adapter and in the PDF builder.
 *
 * **What this fixes.** Measured on `main` before the change, footnote syntax degraded three ways,
 * one of them a real defect:
 *  - `[^1]: body` — a body with no spaces is a valid CommonMark *link reference definition*, so the
 *    reference rendered as a tappable link to the destination `body` and the definition vanished;
 *  - an indented multi-paragraph body rendered its continuation as an `IndentedCodeBlock`;
 *  - a multi-word body rendered as a stray visible paragraph.
 * Starting the block parser on the `[^label]:` line pre-empts all three: commonmark never gets to
 * read the line as a link reference definition, and the body is parsed as the prose it is.
 *
 * **Never mutates the source** (Safeguard 2): the parser consumes lines and the transform rewrites
 * already-parsed inline nodes, so `rawContent` — and therefore every editor/search char offset —
 * is untouched.
 */

/**
 * One footnote definition, `[^label]: body`. A container: its children are ordinary parsed blocks.
 *
 * [ordinal] is assigned by [Footnotes.transform] from the order references first appear, matching
 * GitHub. It stays `null` for a definition nothing references — such a block renders its literal
 * [label] instead of a fabricated number, and is never dropped (deleting user-written content is not
 * an option, so this deliberately diverges from GitHub, which omits unreferenced definitions).
 *
 * [blockIndex] and [firstReferenceBlockIndex] are positions in the document's top-level child
 * sequence. That sequence is exactly what `MarkwonAdapter` turns into RecyclerView items and what
 * `ParseMarkdownHeadingsUseCase` counts for TOC/search targets, so a top-level index IS an adapter
 * position — which is what makes tap-to-jump a plain `scrollToPositionWithOffset` with no new
 * lookup table. [firstReferenceBlockIndex] stays [NO_BLOCK] when nothing references the definition:
 * there is nowhere to go back to, so no back-link is drawn.
 */
class FootnoteDefinitionBlock(val label: String) : CustomBlock() {
    var ordinal: Int? = null
    var blockIndex: Int = NO_BLOCK
    var firstReferenceBlockIndex: Int = NO_BLOCK
}

/**
 * An inline `[^label]` reference that resolved to a definition, carrying the [ordinal] the marker
 * paints and the top-level block the marker jumps to. A reference with no matching definition is
 * never turned into one of these — it stays literal text, exactly as GitHub leaves it.
 */
class FootnoteReference(val label: String, val ordinal: Int, val definitionBlockIndex: Int) : CustomNode()

/** No such top-level block — used where a jump target does not exist (nothing to scroll to). */
const val NO_BLOCK: Int = -1

/** `[^label]:` at the start of a line, plus the optional single space before the body. */
private val DEFINITION_START = Regex("""^\[\^([^\]\s]+)\]:[ \t]?""")

/** Matches a reference inside prose text; the label rules mirror [DEFINITION_START]. */
private val REFERENCE = Regex("""\[\^([^\]\s]+)\]""")

/**
 * Consumes `[^label]: …` and everything indented under it. Bounded by construction — it finishes at
 * the first non-blank, unindented line — so unlike frontmatter it needs no whole-document lookahead
 * to avoid running away to EOF (commonmark-java 0.13.0 has no block-parser rollback).
 */
private class FootnoteDefinitionParser(label: String) : AbstractBlockParser() {

    private val block = FootnoteDefinitionBlock(label)

    override fun getBlock(): Block = block

    override fun isContainer(): Boolean = true

    override fun canContain(childBlock: Block): Boolean = true

    override fun tryContinue(state: ParserState): BlockContinue? = when {
        // A blank line does not end the definition — a multi-paragraph body is separated by one.
        state.isBlank -> BlockContinue.atIndex(state.nextNonSpaceIndex)
        // Continuation lines are indented; consume the 4-space marker and let children parse the rest.
        state.indent >= CONTINUATION_INDENT -> BlockContinue.atColumn(state.column + CONTINUATION_INDENT)
        else -> BlockContinue.none()
    }

    private companion object {
        const val CONTINUATION_INDENT = 4
    }
}

/**
 * Starts a [FootnoteDefinitionParser] for an unindented `[^label]:` line. Registered on BOTH the
 * render parser and the search/TOC parity parser — that shared registration is what keeps their
 * top-level block sequences 1:1, and `ParseParityTest` fails if one side ever forgets.
 */
class FootnoteBlockParserFactory : BlockParserFactory {

    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        // Four spaces of indent is an indented code block; a definition must not hijack it.
        if (state.indent >= INDENTED_CODE_INDENT) return BlockStart.none()
        val line = state.line.subSequence(state.nextNonSpaceIndex, state.line.length).toString()
        val match = DEFINITION_START.find(line) ?: return BlockStart.none()
        val contentIndex = state.nextNonSpaceIndex + match.value.length
        return BlockStart.of(FootnoteDefinitionParser(match.groupValues[1])).atIndex(contentIndex)
    }

    private companion object {
        const val INDENTED_CODE_INDENT = 4
    }
}

/**
 * The post-parse pass that resolves references against definitions.
 *
 * It is a plain function over a parsed [Document] rather than a Markwon `beforeRender` hook, and
 * that is deliberate: in the recycler path `beforeRender` fires per TOP-LEVEL BLOCK at bind time, so
 * it can never see the document-wide label set that numbering requires. Applying it at the parse
 * sites instead gives whole-document scope and keeps the render, PDF and search trees identical by
 * construction.
 *
 * A custom inline processor was ruled out and cannot work here: markwon's `OpenBracketInlineProcessor`
 * claims `[`, never returns null, and is registered before anything added via `addInlineProcessor`,
 * so a custom `[` processor is unreachable. (Single-`$` math worked only because no default
 * processor claims `$`.)
 */
object Footnotes {

    /**
     * Resolve every `[^label]` reference in [document] against its `[^label]:` definitions, in place,
     * and return the same document for convenience.
     *
     * Rules, matching GitHub except where noted (all verified against its Markdown API):
     *  - markers are numbered 1..n by the order references FIRST appear, so a label referenced twice
     *    keeps one number;
     *  - a reference with no definition stays literal text;
     *  - a duplicate definition is ignored for resolution (first wins) but is still RENDERED, and an
     *    unreferenced definition is still rendered — GitHub drops both, we never drop user content;
     *  - references are only rewritten inside prose text, so one inside a code span, fenced/indented
     *    code or raw HTML stays literal for free;
     *  - math is untouched: the render parser turns `$…$` into a node whose latex is a string
     *    property, which this walk never visits.
     *
     * **Scope fence:** references *inside* a footnote body are not resolved (nested footnotes are out
     * of scope for v1.0.4); they stay literal.
     */
    fun transform(document: Node): Node {
        val definitions = collectDefinitions(document)
        if (definitions.isEmpty()) return document
        val ordinals = LinkedHashMap<String, Int>()
        // Walk the top-level blocks by index, not the tree as a whole: every reference has to learn
        // WHICH block it sits in so the definition can link back to it, and that index is only
        // knowable here (a node cannot see its own position, and commonmark carries no source spans).
        var blockIndex = 0
        var child = document.firstChild
        while (child != null) {
            // Capture `next` first: replacing a Text node relinks siblings under our feet.
            val next = child.next
            resolveReferences(child, blockIndex, definitions, ordinals)
            blockIndex++
            child = next
        }
        definitions.forEach { (label, block) -> block.ordinal = ordinals[label] }
        return document
    }

    /**
     * First definition per label wins; later duplicates keep their place in the tree, unresolved.
     * Every definition learns its own top-level position on the way past — including a duplicate,
     * which is still rendered and so still needs one.
     */
    private fun collectDefinitions(document: Node): Map<String, FootnoteDefinitionBlock> {
        val found = LinkedHashMap<String, FootnoteDefinitionBlock>()
        var blockIndex = 0
        var node = document.firstChild
        while (node != null) {
            if (node is FootnoteDefinitionBlock) {
                node.blockIndex = blockIndex
                found.putIfAbsent(node.label, node)
            }
            blockIndex++
            node = node.next
        }
        return found
    }

    /** Walk one top-level block's prose, replacing resolvable references and numbering as we go. */
    private fun resolveReferences(
        node: Node,
        blockIndex: Int,
        definitions: Map<String, FootnoteDefinitionBlock>,
        ordinals: MutableMap<String, Int>,
    ) {
        when {
            // Definition bodies are prose too, but nested references are out of scope (v1.0.4).
            node is FootnoteDefinitionBlock -> Unit
            // Literal-text nodes render their source verbatim, so a reference inside one is not
            // a reference at all.
            node is Code || node is FencedCodeBlock || node is IndentedCodeBlock -> Unit
            node is HtmlBlock || node is HtmlInline -> Unit
            node is Text -> splitReferences(node, blockIndex, definitions, ordinals)
            else -> {
                var child = node.firstChild
                while (child != null) {
                    // Capture `next` first: replacing a Text node relinks siblings under our feet.
                    val next = child.next
                    resolveReferences(child, blockIndex, definitions, ordinals)
                    child = next
                }
            }
        }
    }

    /**
     * Replace each resolvable `[^label]` in [text] with a [FootnoteReference], keeping the literal
     * text either side. The pieces are inserted before the original node, which is then unlinked —
     * so the source order (and therefore every later raw-source offset) is preserved exactly.
     */
    private fun splitReferences(
        text: Text,
        blockIndex: Int,
        definitions: Map<String, FootnoteDefinitionBlock>,
        ordinals: MutableMap<String, Int>,
    ) {
        val literal = text.literal
        val matches = REFERENCE.findAll(literal)
            .filter { definitions.containsKey(it.groupValues[1]) }
            .toList()
        if (matches.isEmpty()) return

        var cursor = 0
        for (match in matches) {
            if (match.range.first > cursor) {
                text.insertBefore(Text(literal.substring(cursor, match.range.first)))
            }
            val label = match.groupValues[1]
            val definition = definitions.getValue(label)
            // The FIRST reference both fixes the ordinal and becomes the definition's back-link
            // target; later references to the same label reuse the number and change nothing.
            val ordinal = ordinals[label] ?: (ordinals.size + 1).also {
                ordinals[label] = it
                definition.firstReferenceBlockIndex = blockIndex
            }
            text.insertBefore(FootnoteReference(label, ordinal, definition.blockIndex))
            cursor = match.range.last + 1
        }
        if (cursor < literal.length) {
            text.insertBefore(Text(literal.substring(cursor)))
        }
        text.unlink()
    }
}
