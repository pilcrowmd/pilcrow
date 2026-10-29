// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/**
 * S8-A46: the app's own save of a note, waiting for the provider's date. Google Drive reports the
 * new size at once but sets the new date about 8 s later, so the date read right after the save is
 * the old one. [size] is the size the provider gave right after the save, [at] when it finished.
 * Kept in memory only: the stored marks format does not change.
 */
data class OwnSave(val size: Long, val at: Long) {

    /**
     * Whether a note last seen at [oldDate]/[oldSize] and now listed at [newDate]/[newSize] changed
     * only by the provider settling this save: the date, known on both sides, moved; both sizes are
     * the one written; and [now] is within [SETTLE_MS] of the save. Any other difference is a real
     * change. A save settles once: the caller drops it after the first time this is true.
     */
    fun settles(oldDate: Long?, oldSize: Long?, newDate: Long?, newSize: Long?, now: Long): Boolean =
        now - at in 0..SETTLE_MS &&
            oldSize == size &&
            newSize == size &&
            oldDate != null &&
            newDate != null &&
            oldDate != newDate

    companion object {
        /**
         * 2 min: 15 times the 8 s measured on Drive (S8-A45), room for a slow upload. Short, because
         * an outside edit of exactly the same size inside it is not marked. A save that settles later
         * (offline) reads as Updated, as before: a mark too many, never one missed.
         */
        const val SETTLE_MS = 2 * 60_000L

        /**
         * S8-A52: after the app's own save the note is read again every [RECHECK_MS] for
         * [RECHECK_WINDOW_MS], whichever screen shows, so its later date is taken in without a
         * listing. Drive set it 2-4 s after the save (S8-A51) and up to 8 s (S8-A45).
         */
        const val RECHECK_MS = 2_000L
        const val RECHECK_WINDOW_MS = 20_000L
    }
}
