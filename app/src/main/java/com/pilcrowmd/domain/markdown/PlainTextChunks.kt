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
 * keep the line pitch across it. A line longer than [LongLineCut.MAX_BLOCK_CHARS] is cut inside
 * itself too (S8-A61: one TextView line-breaking a 200 KB line froze the main thread for seconds); such
 * a cut drops no character and is a forced seam as well.
 *
 * Reconstruction invariant: the chunks, in order, with a "\n" between two chunks wherever a cut fell
 * at a line end, reproduce the input exactly. [chunkStarts] says where each chunk starts, so which
 * kind of cut lies before a chunk is whether the character before its start is that "\n".
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
     * The chunk literals — what search scans. [maxChunkLines] and [maxLineChars] let a caller that
     * does not draw on the main thread (the PDF export) opt out of the caps; the reader and search
     * use the defaults.
     */
    fun split(
        content: String,
        maxChunkLines: Int = MAX_CHUNK_LINES,
        maxLineChars: Int = LongLineCut.MAX_BLOCK_CHARS,
    ): List<String> = pieces(content, maxChunkLines, maxLineChars).map { it.literal }

    /** The same split with the forced-seam flag per piece, for the painter. */
    fun pieces(
        content: String,
        maxChunkLines: Int = MAX_CHUNK_LINES,
        maxLineChars: Int = LongLineCut.MAX_BLOCK_CHARS,
    ): List<Piece> {
        if (content.isEmpty()) return emptyList()
        // Each chunk is ONE substring of [content] (M-179), cut where [forEachCut] says.
        val chunks = mutableListOf<Piece>()
        var chunkStart = 0
        var forcedBefore = false
        forEachCut(content, maxChunkLines, maxLineChars) { cut, kind ->
            // A cut at a line end drops that line's '\n': the seam stands in for it.
            val end = if (kind == Cut.IN_LINE) cut else cut - 1
            chunks += Piece(content.substring(chunkStart, end), forcedBefore)
            chunkStart = cut
            forcedBefore = kind != Cut.BLANK
        }
        chunks += Piece(content.substring(chunkStart), forcedBefore)
        return chunks
    }

    /**
     * Where each chunk of [split] starts in [content], in order; the first is 0. Issue #15: the
     * reader counts chunks and the editor characters, so a switch between them translates here.
     * Copies no text.
     */
    fun chunkStarts(
        content: String,
        maxChunkLines: Int = MAX_CHUNK_LINES,
        maxLineChars: Int = LongLineCut.MAX_BLOCK_CHARS,
    ): IntArray {
        if (content.isEmpty()) return IntArray(0)
        val starts = mutableListOf(0)
        forEachCut(content, maxChunkLines, maxLineChars) { cut, _ -> starts += cut }
        return starts.toIntArray()
    }

    /** What lies at a cut: a blank line (an invisible seam), the line cap, or the middle of a long line. */
    private enum class Cut { BLANK, LINE_CAP, IN_LINE }

    /** Calls [onCut] with the start of each chunk after the first, and what kind of cut lies before it. */
    private inline fun forEachCut(
        content: String,
        maxChunkLines: Int,
        maxLineChars: Int,
        onCut: (cut: Int, kind: Cut) -> Unit,
    ) {
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
                onCut(lineStart, if (atBlank) Cut.BLANK else Cut.LINE_CAP)
                currentLines = 0
            }
            currentLines++
            // S8-A61: a line no TextView can lay out in time is cut inside, [maxLineChars] at most a piece.
            var pieceStart = lineStart
            while (lineEnd - pieceStart > maxLineChars) {
                pieceStart = LongLineCut.pieceEnd(content, pieceStart, lineEnd, maxLineChars)
                // Only a malformed cluster as long as the window can end the line here: no cut then.
                if (pieceStart >= lineEnd) break
                onCut(pieceStart, Cut.IN_LINE)
                currentLines = 1
            }
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
