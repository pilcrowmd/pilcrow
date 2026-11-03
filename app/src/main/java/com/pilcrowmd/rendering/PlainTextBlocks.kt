// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.LongLineCut
import com.pilcrowmd.domain.markdown.PlainTextChunks
import org.commonmark.node.CustomBlock
import org.commonmark.node.Document

/**
 * One block of plain text — carries its lines verbatim as [literal]. A custom commonmark node
 * type so the existing markwon-recycler adapter dispatches it to [PlainTextBlockEntry]; the
 * Markdown parser never emits it, so `.md` rendering is structurally unaffected.
 */
class PlainTextChunk(val literal: String, val continuesPrevious: Boolean = false, val continuesNext: Boolean = false) :
    CustomBlock()

/**
 * Builds the plain-text render model (Option A refined per the fidelity spike): a
 * commonmark [Document] whose children are [PlainTextChunk] nodes holding the content VERBATIM —
 * no parser runs, so Markdown syntax has no meaning and every character renders literally.
 * The chunks are [PlainTextChunks.pieces], the same split search uses ([PlainTextChunks.split]), so
 * adapter positions align. A run with no blank line is cut at [PlainTextChunks.MAX_CHUNK_LINES]; the
 * two chunks around that forced seam carry [PlainTextChunk.continuesPrevious] /
 * [PlainTextChunk.continuesNext] so [PlainTextBlockEntry] can keep the line pitch across it.
 */
object PlainTextBlocks {

    /**
     * [maxChunkLines] and [maxLineChars] let a caller that does not draw on the main thread (the PDF
     * export) opt out of the caps.
     */
    fun build(
        content: String,
        maxChunkLines: Int = PlainTextChunks.MAX_CHUNK_LINES,
        maxLineChars: Int = LongLineCut.MAX_BLOCK_CHARS,
    ): Document = build(PlainTextChunks.pieces(content, maxChunkLines, maxLineChars))

    /** The document for pieces [PlainTextChunks.pieces] already produced — the chunks share those strings. */
    fun build(pieces: List<PlainTextChunks.Piece>): Document {
        val document = Document()
        pieces.forEachIndexed { i, piece ->
            val continuesNext = pieces.getOrNull(i + 1)?.forcedBefore == true
            document.appendChild(PlainTextChunk(piece.literal, piece.forcedBefore, continuesNext))
        }
        return document
    }

    /**
     * The chunk literals [build] wraps into nodes — exposed so plain-mode search can compute
     * adapter positions from the same split. Joining with "\n" reproduces [content] exactly.
     */
    fun chunkLiterals(content: String): List<String> = PlainTextChunks.split(content)
}
