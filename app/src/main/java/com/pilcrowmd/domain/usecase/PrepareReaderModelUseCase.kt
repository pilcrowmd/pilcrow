// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.HeadingAnchors
import com.pilcrowmd.domain.markdown.ParagraphSplitter
import com.pilcrowmd.domain.markdown.PlainReason
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.markdown.TooDeepDocument
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.rendering.PlainTextBlocks
import com.pilcrowmd.rendering.calloutTitleSource
import com.pilcrowmd.rendering.linkBareNotes
import org.commonmark.node.Node

/**
 * Builds the [ReaderModel] for a version of the document's text: ONE whole-document parse, from
 * which the reader's blocks, the `<details>` sections, the heading anchors and the TOC are all taken
 * (M-172, M-179). Blocking and CPU-bound — the caller runs it off the main thread.
 *
 * @param parse the reader's Markdown parse (production: `MarkwonRenderer.parseForReader`, the
 *   reader's full plugin chain). Must be safe to call from a background thread.
 * @param markdownCeilingChars above this length no parse tree is built; the text is shown plain.
 * @param inlineText how the TOC reads an inline custom node of [parse]'s tree as text (production:
 *   an inline formula's source, see `readerInlineText`); null leaves it to its children.
 */
class PrepareReaderModelUseCase(
    private val parse: (String) -> Node,
    private val headings: ParseMarkdownHeadingsUseCase,
    private val markdownCeilingChars: Int = MARKDOWN_CEILING_CHARS,
    private val inlineText: (Node) -> String? = { null },
) {

    /**
     * The model for [content] in [renderMode]. Never throws for a document it cannot render as
     * Markdown: it answers with the plain model and says why ([PlainReason]).
     *
     * [bareLinks] (M-160, K13/K14): the bare note names of this note that the resolver found a note
     * for. They become links in the parsed tree, here, so a change of the set is a new model. A
     * plain model has no tree, so it takes none.
     */
    // Safeguard 3: any parser failure degrades to plain text, and the reason is carried in the model
    // (PlainReason.UNRENDERABLE) for the screen to state — so nothing is swallowed silently.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun prepare(content: String, renderMode: RenderMode, bareLinks: Set<String> = emptySet()): ReaderModel = when {
        renderMode == RenderMode.PLAIN -> plain(content, renderMode, PlainReason.PLAIN_TEXT)
        content.length > markdownCeilingChars -> plain(content, renderMode, PlainReason.TOO_LARGE)
        else -> try {
            markdown(content, renderMode, bareLinks)
        } catch (e: OutOfMemoryError) {
            // Sound only because the parse is one discardable computation: everything it allocated
            // is unreachable once this frame unwinds. A second line only — an allocation on another
            // thread can still lose the race while the parse fills the heap; the ceiling is the guard.
            plain(content, renderMode, PlainReason.UNRENDERABLE)
        } catch (e: Exception) {
            plain(content, renderMode, PlainReason.UNRENDERABLE)
        }
    }

    /**
     * Whether [content] in [renderMode] is shown as plain text by length and mode alone — for a
     * caller that has no model to ask (search while the editor is open).
     */
    fun showsAsPlain(content: String, renderMode: RenderMode): Boolean =
        renderMode == RenderMode.PLAIN || content.length > markdownCeilingChars

    private fun markdown(content: String, renderMode: RenderMode, bareLinks: Set<String>): ReaderModel {
        val parsed = parse(content)
        // A tree nested past NestingLimit.MAX_DEPTH comes back as an empty TooDeepDocument (NEW-11):
        // rendering it as Markdown would overflow the stack, so the source is shown as plain text.
        if (parsed is TooDeepDocument) return plain(content, renderMode, PlainReason.TOO_DEEP)
        // M-328: the reader runs the split itself to keep the seams; search and the headings drawer
        // take the same split through ReaderDocument.transform's default, so positions agree.
        val seams = ParagraphSplitter.split(parsed)
        // M-227: a callout's title reads a formula or emoji as its source, as the PDF does (ReaderTree),
        // so the title painted here is the text search finds in it.
        // Issue #17: long code blocks are cut here too, by the same default search and the drawer take.
        // S8-A31: so are long tables, for the same reason.
        val document = ReaderDocument.transform(
            parsed,
            splitLongParagraphs = false,
            inlineSource = ::calloutTitleSource,
            splitLongCodeBlocks = true,
            splitLongTables = true,
        )
        // Everything that walks the tree runs before [detach] unlinks its blocks.
        val details = Details.sections(document)
        val anchors = HeadingAnchors.targets(document)
        // M-160: after the anchors, so a heading's slug is made from its text as written (GitHub's rule)
        // and a `#link` to it keeps working once the bare links arrive; before the blocks are cut loose,
        // so the blocks, search and the TOC read the links.
        linkBareNotes(document, bareLinks)
        val blocks = detach(document)
        return ReaderModel.Markdown(content, blocks, details, anchors, headings.headingsOf(blocks, inlineText), seams)
    }

    private fun plain(content: String, renderMode: RenderMode, reason: PlainReason): ReaderModel.Plain {
        val pieces = PlainTextChunks.pieces(content)
        val literals = pieces.map { it.literal }
        return ReaderModel.Plain(content, renderMode, detach(PlainTextBlocks.build(pieces)), literals, reason)
    }

    /**
     * The document's top-level blocks, unlinked from it — what `MarkwonAdapter`'s default reducer
     * does on every `setParsedMarkdown(Node)`, including dropping link reference definitions
     * ([AdapterBlocks.of], M-214). Done once here so the SAME list can be handed to every adapter the
     * reader builds (a font-scale or theme rebuild) without parsing again.
     */
    private fun detach(document: Node): List<Node> = AdapterBlocks.of(document).onEach { it.unlink() }

    companion object {
        /**
         * PROPOSED, to be calibrated on a release build of the phone. 10 M chars keeps one
         * reader tree + one transient search tree + the text under half of a 256 MB heap at the
         * pessimistic per-char cost.
         */
        const val MARKDOWN_CEILING_CHARS = 10_000_000
    }
}
