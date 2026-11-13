// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.model

/**
 * The matches of one search, in document order, at most the [Builder]'s limit (large-documents spec, Rule 4).
 * Stored as three int arrays and a small table of the distinct matched texts, so ten thousand matches
 * cost a few hundred kilobytes rather than ten thousand objects; each [SearchMatch] is made when it
 * is read. [capped] says the document holds more matches than were kept, so the count reads "10,000+".
 */
class SearchMatches private constructor(private val kept: Builder) : AbstractList<SearchMatch>() {

    override val size: Int = kept.size

    val capped: Boolean = kept.capped

    override fun get(index: Int): SearchMatch {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index $index, size $size")
        return kept.matchAt(index)
    }

    /** Collects matches in order and stops taking them at [limit]. Not thread-safe; one per search. */
    class Builder(private val limit: Int = MAX_MATCHES) {
        private var starts = IntArray(INITIAL)
        private var positions = IntArray(INITIAL)
        private var occurrences = IntArray(INITIAL)
        private var texts = IntArray(INITIAL)
        private val shown = ArrayList<String>()
        private val shownIndex = HashMap<String, Int>()
        internal var size = 0
            private set

        /** True once a match past [limit] was offered: the search can stop, the count is "limit+". */
        var capped = false
            private set

        /** Keep the next match, or note that the cap was reached. */
        fun add(content: String, startIndex: Int, adapterPosition: Int, occurrenceInBlock: Int) {
            if (size >= limit) {
                capped = true
                return
            }
            if (size == starts.size) grow()
            starts[size] = startIndex
            positions[size] = adapterPosition
            occurrences[size] = occurrenceInBlock
            texts[size] = shownIndex.getOrPut(content) { shown.add(content).let { shown.size - 1 } }
            size++
        }

        /** The matches so far. The builder must not be added to after this: the list reads its arrays. */
        fun build(): SearchMatches = SearchMatches(this)

        internal fun matchAt(index: Int) =
            SearchMatch(shown[texts[index]], starts[index], positions[index], occurrences[index])

        private fun grow() {
            val bigger = minOf(maxOf(starts.size * 2, INITIAL), limit)
            starts = starts.copyOf(bigger)
            positions = positions.copyOf(bigger)
            occurrences = occurrences.copyOf(bigger)
            texts = texts.copyOf(bigger)
        }
    }

    companion object {
        /** The most matches one search keeps (Rule 4); the count then reads "10,000+". */
        const val MAX_MATCHES = 10_000

        private const val INITIAL = 16

        val EMPTY: SearchMatches = Builder().build()
    }
}
