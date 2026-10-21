// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

/**
 * Issue #15: the bookkeeping of the search for the first line that maps to reader block [target]
 * ([ParseMarkdownHeadingsUseCase.offsetOfReaderBlock]). The caller probes the line this proposes and
 * says which block it maps to; the block a line maps to never goes down as the line goes down.
 *
 * Line [lo] maps before the target and line [hi] to it or later, so the answer is [hi] once the two
 * are neighbours. [estimatedStart] is a guess of each block's first line ([BlockLines]): it places the
 * first probe, and then moves the nearest probe by the guessed distance to the target, so a few probes
 * reach the line. Every third probe halves instead, so a bad guess cannot cost more than about three
 * times a plain bisection.
 */
internal class ReaderBlockLineSearch(
    private val lineCount: Int,
    private val estimatedStart: IntArray,
    private val target: Int,
) {
    var lo = 0
        private set
    var hi = lineCount - 1
        private set
    private var loBlock = 0
    private var hiBlock = estimatedStart.size - 1
    private var probes = 0
    private var hiProbed = false
    private var gallop = 1
    private var galloping = false

    val done: Boolean get() = hi - lo <= 1

    /** The line the block is expected on, before any probe. */
    val seed: Int get() = estimatedStart[target].coerceIn(0, lineCount - 1)

    /** The next line to probe, strictly between [lo] and [hi]. */
    fun nextProbe(): Int {
        val guess = when {
            probes == 0 -> seed
            probes % BISECT_EVERY == BISECT_EVERY - 1 -> (lo + hi) / 2
            // In the block, and its first line is further up: step up by 1, 2, 4, ... lines.
            hiProbed && hiBlock == target -> hi - gallop
            else -> nearestEnd()
        }
        galloping = hiProbed && hiBlock == target && probes != 0 && probes % BISECT_EVERY != BISECT_EVERY - 1
        return guess.coerceIn(lo + 1, hi - 1)
    }

    /** Line [line] maps to [block]. */
    fun record(line: Int, block: Int) {
        probes++
        if (block < target) {
            lo = line
            loBlock = block
            gallop = 1
        } else {
            hi = line
            hiBlock = block
            hiProbed = true
            gallop = if (galloping) gallop * 2 else 1
        }
        galloping = false
    }

    /**
     * The best line to settle for when the search must stop now: the line known to be in the block
     * when there is one, else the current estimate, kept between what the probes have bracketed.
     */
    fun bestSoFar(): Int =
        if (hiProbed && hiBlock == target) hi else (if (probes == 0) seed else nearestEnd()).coerceIn(lo, hi)

    /** The probe that maps nearest the target, moved by the guessed number of lines between. */
    private fun nearestEnd(): Int {
        val goal = estimatedStart[target]
        val fromLo = goal - estimatedStart[loBlock]
        val fromHi = estimatedStart[hiBlock] - goal
        return if (fromLo <= fromHi) lo + fromLo else hi - fromHi
    }

    private companion object {
        const val BISECT_EVERY = 3
    }
}
