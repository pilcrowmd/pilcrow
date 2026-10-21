// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.domain.model.RenderMode
import org.commonmark.node.Node

/**
 * What the reader paints for ONE version of the document's text (M-172, M-179). Prepared once, off
 * the main thread, by `PrepareReaderModelUseCase`, and then shared: the reader renders [blocks], the
 * TOC is [Markdown.headings], and search in the plain state walks [Plain.chunkLiterals].
 *
 * Read-only and derived: nothing on the save path reads it, and it never writes back to the document
 * (Safeguard 2). Compared by IDENTITY — deliberately not a data class, whose `equals` would compare
 * megabytes of text on every `StateFlow` emission.
 */
sealed class ReaderModel(
    /** The exact text this was prepared from; a model whose [content] is not the current text is stale. */
    val content: String,
    /** The render mode that was asked for (a [Plain] model can answer a MARKDOWN request, see [Plain.reason]). */
    val renderMode: RenderMode,
    /**
     * Top-level blocks, one reader item each, numbered by [AdapterBlocks.of] (so no link reference
     * definition, M-214) and already detached from their `Document`.
     */
    val blocks: List<Node>,
) {
    /**
     * Each block's share of the text, counted once off Main when the model is prepared, for the
     * scroll percent ([ScrollPercent.textStarts]).
     */
    abstract val textStarts: IntArray

    /**
     * The characters of wrapped prose and the lines of code or table in each block, counted with
     * [textStarts], for the "more than 6 screens" test ([ScrollPercent.isLong]).
     */
    abstract val rowCounts: ScrollPercent.RowCounts

    /** The Markdown tree, with everything derived from it in the same pass. */
    class Markdown(
        content: String,
        blocks: List<Node>,
        val details: List<DetailsSection>,
        /** Heading anchor -> adapter position, for `[text](#anchor)` links (M-159, [HeadingAnchors]). */
        val anchors: Map<String, Int>,
        val headings: List<HeadingNode>,
        /** Where the pieces of a split paragraph meet (M-328, [ParagraphSplitter]); the reader draws no gap there. */
        val seams: ParagraphSeams = ParagraphSeams.NONE,
    ) : ReaderModel(content, RenderMode.MARKDOWN, blocks) {
        override val textStarts = ScrollPercent.textStarts(blocks)
        override val rowCounts = ScrollPercent.rowCounts(blocks)
    }

    /** The text shown verbatim, in chunks; no Markdown parser ran. */
    class Plain(
        content: String,
        renderMode: RenderMode,
        blocks: List<Node>,
        val chunkLiterals: List<String>,
        val reason: PlainReason,
    ) : ReaderModel(content, renderMode, blocks) {
        override val textStarts = ScrollPercent.textStartsOf(chunkLiterals)
        override val rowCounts = ScrollPercent.rowCountsOf(chunkLiterals)
    }

    /** The TOC this model yields: the Markdown headings, or none. */
    val tocHeadings: List<HeadingNode> get() = (this as? Markdown)?.headings.orEmpty()
}

/** Why a document is shown as plain text. */
enum class PlainReason {
    /** The user's render mode (a `.txt`, or "View as plain text"). */
    PLAIN_TEXT,

    /** Longer than the Markdown ceiling: a parse tree would not fit on a phone (M-179). */
    TOO_LARGE,

    /** The Markdown parse failed or ran out of memory; the text is still readable (Safeguard 3). */
    UNRENDERABLE,

    /**
     * Nested deeper than `NestingLimit.MAX_DEPTH` (NEW-11): shown as plain text WITHOUT a notice, as
     * the reader always has for such a document.
     */
    TOO_DEEP,
}
