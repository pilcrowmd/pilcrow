// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.folders.OwnSave
import com.pilcrowmd.repository.DocumentStats
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8-A46: for a short window after a file from an added folder is opened, whether it changes
 * outside the app. Google Drive first hands over its old local copy and fetches the new one in the
 * background (about 27 s measured, S8-A45), so the date and size are asked again every [intervalMs]
 * for [windowMs]. The app's own saves are not changes: a query that overlapped one is not compared,
 * the values after it become the baseline, and the provider's later date for it is taken in
 * ([OwnSave]). Nothing runs outside [changed], which the caller ends when the file closes or the
 * app goes to the background.
 */
class OpenFileChangeCheck(
    private val stats: suspend (Uri) -> DocumentStats?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = INTERVAL_MS,
    private val windowMs: Long = WINDOW_MS,
) {

    private val writing = AtomicInteger()
    private val saves = AtomicInteger()

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
     * True as soon as [uri]'s date or size moves from the values at the start, or at once when the
     * copy read at the open, [readSize] bytes, is not the size the provider states: Drive can state
     * the new version's size while it still hands over its old copy (S8-A46, measured). False when
     * the window ends with no change, or when the provider cannot say at the start.
     */
    suspend fun changed(uri: Uri, readSize: Long? = null): Boolean {
        var seenSaves = saves.get()
        var baseline = stats(uri) ?: return false
        if (readSize != null && baseline.size != null && baseline.size != readSize) return true
        var ownSave: OwnSave? = null
        repeat((windowMs / intervalMs).toInt()) {
            delay(intervalMs)
            val savesBefore = saves.get()
            if (writing.get() > 0) return@repeat
            val now = stats(uri) ?: return@repeat
            // A save that began or ended while the query ran: its values may be half old, half new.
            if (writing.get() > 0 || saves.get() != savesBefore) return@repeat
            if (savesBefore != seenSaves) {
                seenSaves = savesBefore
                baseline = now
                ownSave = now.size?.let { OwnSave(it, clock()) }
                return@repeat
            }
            if (now == baseline) return@repeat
            if (ownSave?.settles(baseline.lastModified, baseline.size, now.lastModified, now.size, clock()) == true) {
                baseline = now
                ownSave = null // settles once: a second move is a change
                return@repeat
            }
            return true
        }
        return false
    }

    companion object {
        const val INTERVAL_MS = 3_000L
        const val WINDOW_MS = 30_000L

        /**
         * A document opened through an added folder's tree grant (`tree/<folder>/document/<note>`),
         * the only files checked.
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
