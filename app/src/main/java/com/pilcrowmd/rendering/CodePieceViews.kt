// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.os.Handler
import android.os.Looper
import com.pilcrowmd.domain.markdown.CodeBlockGroup
import kotlinx.coroutines.Job

/**
 * What the pieces of one long code block share on screen (issue #17), held by the reader's code entry
 * for as long as its adapter lives: the sideways offset, so the pieces pan together and their lines
 * stay lined up; one width, the widest piece's, so every piece can pan as far; and the block's colour
 * runs, cut per piece, so each piece paints only its own. While a json block is still being coloured, the
 * runs of its front come in a window at a time ([partialSlices], [coveredPieces]), and the pieces they
 * cover paint without waiting for the rest.
 *
 * Main thread only. A holder is attached while it shows one of the block's pieces. Once none is and
 * the colours have not arrived, the colouring job is cancelled: the block has left the screen, or the
 * document was replaced. A later bind starts it again; a finished result stays in the highlighter's cache.
 */
internal class CodePieceViews {
    private val holders = LinkedHashSet<FencedCodeBlockEntry.Holder>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The block's sideways offset, in px. */
    var scrollX: Int = 0
        private set

    private var width = 0
    private var widthTextSize = Float.NaN
    private var syncing = false

    /** The block's runs cut per piece, offsets within each piece's literal; null until coloured. */
    var slices: List<List<CodeRun>>? = null

    /**
     * The runs so far, cut per piece like [slices], while the job is still colouring the block; only the
     * first [coveredPieces] pieces' share is final. Null until the first window is in, and again once
     * [slices] is set. Kept apart from [slices], whose non-null means the block is done ([cancelIfUnseen]).
     */
    var partialSlices: List<List<CodeRun>>? = null

    /** How many pieces, from the first, [partialSlices] holds the final runs of. */
    var coveredPieces: Int = 0

    /** The job colouring the whole block, if one was started. */
    var job: Job? = null

    val attached: List<FencedCodeBlockEntry.Holder> get() = holders.toList()

    fun attach(holder: FencedCodeBlockEntry.Holder) {
        holders += holder
    }

    fun detach(holder: FencedCodeBlockEntry.Holder) {
        holders -= holder
        // A rebind detaches and attaches again in the same turn, so the job is only given up if no piece
        // is shown a turn later.
        if (holders.isEmpty() && job != null) mainHandler.post(::cancelIfUnseen)
    }

    private fun cancelIfUnseen() {
        if (holders.isNotEmpty() || slices != null) return
        job?.cancel()
        job = null
    }

    /** One piece was panned to [x]: every other piece shown pans with it. */
    fun onScrolled(source: CodeScrollView, x: Int) {
        if (syncing) return
        scrollX = x
        syncing = true
        try {
            holders.forEach { holder ->
                val view = holder.codeScroll
                if (view !== source && view.scrollX != x) view.scrollTo(x, 0)
            }
        } finally {
            syncing = false
        }
    }

    /** The width every piece takes at [textSize], as far as it is known yet. A new size starts over. */
    fun widthAt(textSize: Float): Int {
        if (textSize != widthTextSize) {
            widthTextSize = textSize
            width = 0
        }
        return width
    }

    /**
     * A piece was laid out [laidOutWidth] wide. A piece is never narrower than [width], so a wider one
     * holds a longer line than any piece seen yet: every piece shown takes its width.
     */
    fun onLaidOut(laidOutWidth: Int) {
        if (laidOutWidth <= width) return
        width = laidOutWidth
        holders.forEach { holder ->
            holder.codeView.post { if (holder.pieceViews === this) holder.codeView.minWidth = width }
        }
    }
}

/**
 * [runs] (offsets in [group]'s whole literal) cut at the piece boundaries, each piece's runs in their
 * original order with offsets in that piece's literal. A run over a seam is split in two.
 */
internal fun sliceRuns(runs: List<CodeRun>, group: CodeBlockGroup): List<List<CodeRun>> {
    val slices = List(group.pieceCount) { ArrayList<CodeRun>() }
    for (run in runs) {
        var index = group.pieceAt(run.start)
        while (index < group.pieceCount && group.start(index) < run.end) {
            val base = group.start(index)
            val start = maxOf(run.start, base)
            val end = minOf(run.end, group.end(index))
            if (end > start) slices[index].add(CodeRun(start - base, end - base, run.role))
            index++
        }
    }
    return slices
}

/** How many pieces of [group], from the first, lie wholly within the first [coveredChars] chars of its literal. */
internal fun piecesCoveredBy(group: CodeBlockGroup, coveredChars: Int): Int {
    var count = 0
    while (count < group.pieceCount && group.end(count) <= coveredChars) count++
    return count
}
