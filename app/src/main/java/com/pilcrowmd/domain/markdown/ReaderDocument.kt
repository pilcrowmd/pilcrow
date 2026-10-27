// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Node

/**
 * The post-parse passes every reader parse site applies (the renderer, the PDF export, search and
 * the headings drawer), so all four walk the same tree and adapter positions stay 1:1. The
 * exceptions are the long-paragraph, long-code-block and long-table splits, which the PDF skips
 * (M-328, issue #17, S8-A31; see [transform]).
 */
object ReaderDocument {
    /**
     * [splitLongParagraphs]: a top-level paragraph with more than
     * [ParagraphSplitter.MAX_INLINE_CHILDREN] inline children becomes several paragraphs, so the
     * reader binds, lays out and records a bounded block per frame (M-328). It runs first, because
     * [Footnotes.transform] numbers the blocks. The PDF export passes false: it lays its pages out
     * off the main thread and prints the paragraph whole.
     *
     * [splitLongCodeBlocks]: a top-level code block longer than [CodeBlockSplitter.LONG_BLOCK_CHARS]
     * or [CodeBlockSplitter.LONG_BLOCK_LINES] becomes several [CodeBlockPiece]s, for the same reason
     * (issue #17). It follows [splitLongParagraphs] unless told otherwise: the PDF skips both.
     *
     * [splitLongTables]: a top-level table with more body rows (or cells) than [TableSplitter.PIECE_ROWS]
     * and [TableSplitter.PIECE_CELLS] allow becomes several [TablePiece]s, each drawn with the header, so
     * the reader binds a bounded number of cells at a time (S8-A31). The PDF skips it, as the others.
     *
     * [inlineSource]: see [Callouts.transform]; the renderer passes its maths source.
     */
    fun transform(
        document: Node,
        splitLongParagraphs: Boolean = true,
        inlineSource: (Node) -> String? = { null },
        splitLongCodeBlocks: Boolean = splitLongParagraphs,
        splitLongTables: Boolean = splitLongParagraphs,
    ): Node {
        if (splitLongParagraphs) ParagraphSplitter.split(document)
        if (splitLongCodeBlocks) CodeBlockSplitter.split(document)
        if (splitLongTables) TableSplitter.split(document)
        return Callouts.transform(Footnotes.transform(document), inlineSource)
    }
}
