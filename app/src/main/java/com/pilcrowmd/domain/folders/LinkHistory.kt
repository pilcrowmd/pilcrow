// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/**
 * K10: where Back goes after following links between notes. Each entry is one followed link, from
 * the note it was in to the note it opened. Back is offered only while the note on screen is the one
 * the last followed link opened; any other change of document clears the history (the caller does
 * that, see [clear]).
 */
class LinkHistory<T> {
    private val entries = ArrayDeque<Pair<T, T>>()

    /** A followed link from [from] opened [to]. */
    fun push(from: T, to: T) {
        entries.addLast(from to to)
    }

    /** True only when [current] is the note the last followed link opened. */
    fun canGoBack(current: T?): Boolean = current != null && entries.lastOrNull()?.second == current

    /** The note Back returns to, without taking it off; null when Back is not offered for [current]. */
    fun peek(current: T?): T? = if (canGoBack(current)) entries.last().first else null

    /** Takes the last entry off and returns the note it came from; null when there is none. */
    fun back(): T? = entries.removeLastOrNull()?.first

    /** A document opened or closed by anything but a followed link or Back: the history ends. */
    fun clear() {
        entries.clear()
    }
}

/**
 * K2/P14: of the added folders holding a note, the OUTERMOST applies: the one whose chain of folders
 * from its own root down to the note is the longest. Never the order the folders were added in.
 * [candidates] pairs each folder with that chain; null when there is none.
 */
fun <F> outermostFolder(candidates: List<Pair<F, List<String>>>): Pair<F, List<String>>? =
    candidates.maxByOrNull { it.second.size }
