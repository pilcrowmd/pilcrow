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
        // Each chunk is ONE substring of [content] (M-179), cut where [forEachCut] says.
        val chunks = mutableListOf<Piece>()
        var chunkStart = 0
        var forcedBefore = false
        forEachCut(content, maxChunkLines) { cut, atBlank ->
            chunks += Piece(content.substring(chunkStart, cut - 1), forcedBefore) // minus the seam's '\n'
            chunkStart = cut
            forcedBefore = !atBlank
        }
        chunks += Piece(content.substring(chunkStart), forcedBefore)
        return chunks
    }

    /**
     * Where each chunk of [split] starts in [content], in order; the first is 0. Issue #15: the
     * reader counts chunks and the editor characters, so a switch between them translates here.
     * Copies no text.
     */
    fun chunkStarts(content: String, maxChunkLines: Int = MAX_CHUNK_LINES): IntArray {
        if (content.isEmpty()) return IntArray(0)
        val starts = mutableListOf(0)
        forEachCut(content, maxChunkLines) { cut, _ -> starts += cut }
        return starts.toIntArray()
    }

    /**
     * Calls [onCut] with the start of each chunk after the first, and whether that cut falls after a
     * blank line (an invisible seam) rather than at the line cap.
     */
    private inline fun forEachCut(content: String, maxChunkLines: Int, onCut: (cut: Int, atBlank: Boolean) -> Unit) {
        // Walks the lines in place (M-179): `split('\n')` first would hold every line of the file as
        // its own String at once, before a single chunk exists.
        var lineStart = 0
        var currentLines = 0
        var lastLineBlank = false
        while (true) {
            val newline = content.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) content.length else newline
            // Break after any blank line once past the target, INCLUDING between two blanks:
            // both sides of such a seam are empty space, so it stays invisible, and
            // an all-blank file still chunks instead of becoming one giant TextView. A run with no
            // blank line is cut at the cap; that seam is visible, so the piece is marked forced.
            val atBlank = currentLines >= TARGET_CHUNK_LINES && lastLineBlank
            if (atBlank || currentLines >= maxChunkLines) {
                onCut(lineStart, atBlank)
                currentLines = 0
            }
            currentLines++
            lastLineBlank = isBlank(content, lineStart, lineEnd)
            if (newline < 0) break
            lineStart = newline + 1
        }
    }

    private fun isBlank(content: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (!content[i].isWhitespace()) return false
        return true
    }
}
