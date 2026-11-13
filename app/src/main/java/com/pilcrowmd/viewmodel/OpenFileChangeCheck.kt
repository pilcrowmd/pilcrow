// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.folders.OwnSave
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.repository.StatsAnswer
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8-A90 live reload (S8-A46 before it): whether the open file changes outside the app while it is
 * on screen. The date and size are asked every [intervalMs], with no time limit, only while [watch]
 * runs; the caller ends it when the file closes or the app goes to the background, and calls it
 * again with `resume = true` on the way back, which asks at once against the values kept from
 * before. A file whose provider cannot state them at the open is not checked at all.
 *
 * The app's own saves are not changes: a query that overlapped one is not compared, the values after
 * it become the baseline, and the provider's later date for it is taken in ([OwnSave]).
 */
class OpenFileChangeCheck(
    private val answer: suspend (Uri) -> StatsAnswer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = INTERVAL_MS,
) {

    /** What [watch] saw. */
    enum class Event { CHANGED, GONE }

    private val writing = AtomicInteger()
    private val saves = AtomicInteger()

    /** The file being watched and the values it is compared with; kept while the app is away. */
    private class Watched(val uri: Uri, var baseline: DocumentStats, var seenSaves: Int, var ownSave: OwnSave?)

    // Only [watch] reads or writes it, and one runs at a time (the caller cancels the last first).
    @Volatile private var watched: Watched? = null

    /** Around every save in place of the checked file, so the check never takes it for a change. */
    suspend fun <T> saving(write: suspend () -> Result<T>): Result<T> {
        writing.incrementAndGet()
        try {
            return write().also { if (it.isSuccess) saves.incrementAndGet() }
        } finally {
            writing.decrementAndGet()
        }
    }

    /**
     * Runs until [uri]'s date or size moves from the values at the open ([Event.CHANGED]), or until
     * the provider says it is gone ([Event.GONE]), and otherwise until cancelled. A provider that does
     * not answer (offline, slow) is asked again on the next step: that is never a delete. Changed at once
     * when the copy read at the open, [readSize] bytes, is not the size the provider states: Drive
     * can state the new version's size while it still hands over its old copy (S8-A46, measured).
     * Null at once when the provider cannot say at the open. On [resume] the values kept for [uri]
     * are used, or, when none are (an event ended the last watch, or the app went away before the
     * first answer), it starts afresh from the values now.
     */
    suspend fun watch(uri: Uri, readSize: Long? = null, resume: Boolean = false): Event? {
        val kept = watched?.takeIf { resume && it.uri == uri }
        watched = kept ?: start(uri)
        val state = watched ?: return null
        if (kept == null && statesOtherSize(state, readSize)) {
            watched = null
            return Event.CHANGED
        }
        var waits = kept == null // back from the background: ask at once
        var goneAnswers = 0
        while (true) {
            if (waits) delay(intervalMs)
            waits = true
            val step = step(state)
            goneAnswers = if (step == Step.GONE) goneAnswers + 1 else 0
            val event = when {
                step == Step.CHANGED -> Event.CHANGED
                goneAnswers >= GONE_AFTER_ANSWERS -> Event.GONE
                else -> null
            }
            if (event != null) {
                watched = null
                return event
            }
        }
    }

    private fun statesOtherSize(state: Watched, readSize: Long?): Boolean {
        val stated = state.baseline.size
        return readSize != null && stated != null && stated != readSize
    }

    /** The values at the open, or null when the provider states neither. */
    private suspend fun start(uri: Uri): Watched? {
        val seenSaves = saves.get()
        val now = (answer(uri) as? StatsAnswer.Known)?.stats?.takeIf { it.lastModified != null || it.size != null }
        return now?.let { Watched(uri, it, seenSaves, ownSave = null) }
    }

    private enum class Step { SAME, GONE, CHANGED }

    /** One query of the provider, compared with the values kept in [state]. */
    private suspend fun step(state: Watched): Step {
        val savesBefore = saves.get()
        if (writing.get() > 0) return Step.SAME
        val reply = answer(state.uri)
        // A row with neither value says nothing, like no answer at all: asked again next step.
        val now = (reply as? StatsAnswer.Known)?.stats?.takeIf { it.lastModified != null || it.size != null }
        // A save that began or ended while the query ran: its values may be half old, half new.
        val overlapped = writing.get() > 0 || saves.get() != savesBefore
        return when {
            overlapped -> Step.SAME
            reply == StatsAnswer.Gone || saysNoFile(state, now) -> Step.GONE
            now == null -> Step.SAME
            savesBefore != state.seenSaves -> {
                state.seenSaves = savesBefore
                state.baseline = now
                state.ownSave = now.size?.let { OwnSave(it, clock()) }
                Step.SAME
            }
            now == state.baseline -> Step.SAME
            settlesOwnSave(state, now) -> {
                state.baseline = now
                state.ownSave = null // settles once: a second move is a change
                Step.SAME
            }
            else -> Step.CHANGED
        }
    }

    /**
     * A file-backed provider (Downloads' `raw:` documents) answers for a deleted file with a row of
     * no date and size 0, as java.io.File does for a path that is not there (measured on the
     * emulator, S8-A90). A file that had a date before cannot lose it, so that row says it is gone.
     */
    private fun saysNoFile(state: Watched, now: DocumentStats?): Boolean {
        if (now == null || state.baseline.lastModified == null) return false
        return now.lastModified == null && (now.size ?: 0L) == 0L
    }

    private fun settlesOwnSave(state: Watched, now: DocumentStats): Boolean {
        val old = state.baseline
        return state.ownSave?.settles(old.lastModified, old.size, now.lastModified, now.size, clock()) == true
    }

    companion object {
        const val INTERVAL_MS = 5_000L

        /** "Gone" answers in a row before the file counts as deleted, so one odd reply is not enough. */
        const val GONE_AFTER_ANSWERS = 2

        /**
         * A document opened through an added folder's tree grant (`tree/<folder>/document/<note>`).
         * Only for these is the size read compared with the size stated at the open: that is
         * Drive's stale-copy case (S8-A46), and another provider may state a size the bytes do not
         * match.
         */
        fun isFolderNote(uri: Uri): Boolean {
            val path = uri.pathSegments
            return uri.scheme == "content" &&
                path.size == TREE_DOCUMENT_SEGMENTS &&
                path[0] == "tree" &&
                path[2] == "document"
        }

        private const val TREE_DOCUMENT_SEGMENTS = 4
    }
}
