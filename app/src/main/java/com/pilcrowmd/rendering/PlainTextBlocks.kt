// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import org.commonmark.node.CustomBlock
import org.commonmark.node.Document

/**
 * One block of plain text — carries its lines verbatim as [literal]. A custom commonmark node
 * type so the existing markwon-recycler adapter dispatches it to [PlainTextBlockEntry]; the
 * Markdown parser never emits it, so `.md` rendering is structurally unaffected.
 */
class PlainTextChunk(val literal: String) : CustomBlock()

/**
 * Builds the plain-text render model (Option A refined per the fidelity spike): a
 * commonmark [Document] whose children are [PlainTextChunk] nodes holding the content VERBATIM —
 * no parser runs, so Markdown syntax has no meaning and every character renders literally.
 *
 * Chunking exists only so the preview RecyclerView can recycle on huge files, and chunk
 * boundaries are placed ONLY at blank-line runs (the spike measured a ~4px seam deviation from
 * TextView font-padding/line-spacing arithmetic — inside a blank-line gap it is invisible; a
 * pathological blank-line-free run therefore stays unsplit, the same acceptance a giant
 * single-paragraph `.md` has today). Reconstruction invariant: joining chunk literals with "\n"
 * reproduces the input exactly.
 */
object PlainTextBlocks {

    /** Split no earlier than this many lines, at the next blank-line boundary. */
    const val TARGET_CHUNK_LINES = 200

    fun build(content: String): Document {
        val document = Document()
        chunkLiterals(content).forEach { document.appendChild(PlainTextChunk(it)) }
        return document
    }

    /**
     * The chunk literals [build] wraps into nodes — exposed so plain-mode search can compute
     * adapter positions from the same split. Joining with "\n" reproduces [content] exactly.
     */
    fun chunkLiterals(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentLines = 0
        var lastLineBlank = false
        for (line in content.split('\n')) {
            // Break after any blank line once past the target — INCLUDING between two blanks
            // both sides of such a seam are empty space, so it stays invisible, and
            // an all-blank file still chunks instead of becoming one giant TextView.
            val breakHere = currentLines >= TARGET_CHUNK_LINES && lastLineBlank
            if (breakHere) {
                chunks += current.toString()
                current.setLength(0)
                currentLines = 0
            }
            if (currentLines > 0) current.append('\n')
            current.append(line)
            currentLines++
            lastLineBlank = line.isBlank()
        }
        chunks += current.toString()
        return chunks
    }
}
