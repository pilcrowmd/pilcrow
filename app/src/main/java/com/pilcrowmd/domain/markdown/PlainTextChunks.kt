// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

/**
 * The split of a document shown as plain text (a `.txt`, plain mode, or a Markdown document nested too
 * deep to render) into the chunks the reader paints, one adapter item each. The painter
 * (`PlainTextBlocks`) and search both take their chunks from [split] / [pieces], so a match's adapter
 * position is its chunk index.
 *
 * Chunking exists only so the preview RecyclerView can recycle on huge files. Boundaries go at
 * blank-line runs once a chunk is past [TARGET_CHUNK_LINES] (a seam there is invisible: the spike
 * measured a ~4px seam deviation from TextView font-padding/line-spacing arithmetic, and inside a
 * blank-line gap it cannot be seen). A run with no blank line is cut every [MAX_CHUNK_LINES] lines
 * instead, and the piece after such a forced seam is flagged [Piece.forcedBefore] so the painter can
 * keep the line pitch across it. Reconstruction invariant: joining the chunks with "\n" reproduces
 * the input exactly.
 */
object PlainTextChunks {

    /** Split no earlier than this many lines, at the next blank-line boundary. */
    const val TARGET_CHUNK_LINES = 200

    /**
     * A run with no blank line is split here (2 x [TARGET_CHUNK_LINES]) so that no single TextView
     * is recorded in full on every draw (M-365). A file that has a blank line by line 400 chunks
     * exactly as before.
     */
    const val MAX_CHUNK_LINES = 400

    /** A chunk literal, and whether the boundary before it cuts between two non-blank-ended lines. */
    class Piece(val literal: String, val forcedBefore: Boolean)

    /**
     * The chunk literals — what search scans. [maxChunkLines] lets a caller that does not draw on
     * the main thread (the PDF export) opt out of the cap; the reader and search use the default.
     */
    fun split(content: String, maxChunkLines: Int = MAX_CHUNK_LINES): List<String> =
        pieces(content, maxChunkLines).map { it.literal }

    /** The same split with the forced-seam flag per piece, for the painter. */
    fun pieces(content: String, maxChunkLines: Int = MAX_CHUNK_LINES): List<Piece> {
        if (content.isEmpty()) return emptyList()
        val chunks = mutableListOf<Piece>()
        val current = StringBuilder()
        var currentLines = 0
        var lastLineBlank = false
        var forcedBefore = false
        for (line in content.split('\n')) {
            // Break after any blank line once past the target, INCLUDING between two blanks:
            // both sides of such a seam are empty space, so it stays invisible, and
            // an all-blank file still chunks instead of becoming one giant TextView. A run with no
            // blank line is cut at the cap; that seam is visible, so the piece is marked forced.
            val atBlank = currentLines >= TARGET_CHUNK_LINES && lastLineBlank
            if (atBlank || currentLines >= maxChunkLines) {
                chunks += Piece(current.toString(), forcedBefore)
                current.setLength(0)
                currentLines = 0
                forcedBefore = !atBlank
            }
            if (currentLines > 0) current.append('\n')
            current.append(line)
            currentLines++
            lastLineBlank = line.isBlank()
        }
        chunks += Piece(current.toString(), forcedBefore)
        return chunks
    }
}
