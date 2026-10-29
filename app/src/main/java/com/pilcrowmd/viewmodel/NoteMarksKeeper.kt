// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.NoteMarks
import com.pilcrowmd.domain.folders.OwnSave
import com.pilcrowmd.domain.folders.SeenRecord
import com.pilcrowmd.domain.folders.allNotes
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.storage.NoteMarksStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the New and Updated marks (spec section 3b) in step with listings, notes seen and Mark all
 * as seen, for [NotesFoldersViewModel]. One lock orders every read-change-write of [store], so a
 * listing and a note being opened can never undo each other. The work (decoding, reconciling and
 * encoding up to thousands of records) runs on [dispatcher], never on the main thread (L7).
 * [clock] dates new marks (M8).
 */
class NoteMarksKeeper(
    private val store: NoteMarksStore,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val lock = Mutex()
    private val latest = MutableStateFlow<Map<Uri, Map<String, SeenRecord>>>(emptyMap())

    // S8-A46: the app's own saves still waiting for the provider's date, by folder and note ID.
    private val ownSaves = HashMap<Uri, Map<String, OwnSave>>()

    // Bumped by drop(): a listing that began before a folder was dropped writes nothing for it.
    private val generations = HashMap<Uri, Int>()

    /** The records of each folder as last written, by tree URI: one folder's change leaves the others. */
    val shown: StateFlow<Map<Uri, Map<String, SeenRecord>>> = latest.asStateFlow()

    /** Taken before a listing of [treeUri] starts, and handed back to [listed]. */
    suspend fun generationOf(treeUri: Uri): Int = lock.withLock { generations[treeUri] ?: 0 }

    /**
     * After a listing of [root]: new notes marked New, changed ones Updated (M1-M3, M6). Nothing is
     * written when the folder was dropped since the listing began ([generation]).
     */
    suspend fun listed(treeUri: Uri, root: FolderEntry, generation: Int) = work {
        if ((generations[treeUri] ?: 0) != generation) return@work
        val now = clock()
        val waiting = ownSaves[treeUri].orEmpty().filterValues { now - it.at in 0..OwnSave.SETTLE_MS }
        val previous = store.get(treeUri)
        val records = NoteMarks.reconcile(previous, root.allNotes(), now, waiting)
        // A save settles once: the one whose new date was just taken in, or that a real change has
        // overtaken (now Updated), waits no more.
        ownSaves[treeUri] = waiting.filterKeys { id ->
            val record = records[id]
            record != null && record.updatedSince == null && record.lastModified == previous?.get(id)?.lastModified
        }
        write(treeUri, records)
    }

    /** M5: every note of [root], subfolders included, seen. */
    suspend fun allSeen(treeUri: Uri, root: FolderEntry) = work {
        write(treeUri, NoteMarks.markAllSeen(root.allNotes()))
    }

    /** Whether the folder [treeUri] has a record of the note [documentId]. */
    suspend fun knows(treeUri: Uri, documentId: String): Boolean = work {
        store.get(treeUri)?.containsKey(documentId) == true
    }

    /**
     * M4, M5: the note [documentId] was opened or saved, and [current] is what it is now. When the
     * provider could not say, the baseline stays as it was and only the mark goes. After the app's
     * own save ([ownSave]), the next listings may also take the provider's later date (S8-A46).
     */
    suspend fun seen(treeUri: Uri, documentId: String, current: DocumentStats?, ownSave: Boolean = false) = work {
        val records = store.get(treeUri) ?: return@work
        val old = records[documentId] ?: return@work
        val seen = NoteMarks.seen(current?.lastModified ?: old.lastModified, current?.size ?: old.size)
        // An open meanwhile keeps the wait: the provider's date may still be on its way.
        val savedSize = seen.size
        if (ownSave && savedSize != null) {
            ownSaves[treeUri] = ownSaves[treeUri].orEmpty() + (documentId to OwnSave(savedSize, clock()))
        }
        write(treeUri, records + (documentId to seen))
    }

    /**
     * S8-A52: [current] is the note [documentId] read again after the app's own save, with no
     * listing. When it only settles that save ([OwnSave.settles]), its date becomes the baseline and
     * the save waits no more. Nothing is marked here: a real change shows at the next listing.
     * Returns whether the save still waits.
     */
    suspend fun recheck(treeUri: Uri, documentId: String, current: DocumentStats?): Boolean = work {
        val save = ownSaves[treeUri]?.get(documentId) ?: return@work false
        val records = store.get(treeUri) ?: return@work false
        val old = records[documentId] ?: return@work false
        if (!save.settles(old.lastModified, old.size, current?.lastModified, current?.size, clock())) return@work true
        ownSaves[treeUri] = ownSaves[treeUri].orEmpty() - documentId
        write(treeUri, records + (documentId to NoteMarks.seen(current?.lastModified, current?.size)))
        false
    }

    /** M3: the folder was removed or replaced; if it is added again, its first scan marks nothing. */
    suspend fun drop(treeUri: Uri) = work {
        generations[treeUri] = (generations[treeUri] ?: 0) + 1
        ownSaves.remove(treeUri)
        store.drop(treeUri)
        latest.update { it - treeUri }
    }

    private suspend fun <T> work(block: suspend () -> T): T = lock.withLock { withContext(dispatcher) { block() } }

    private suspend fun write(treeUri: Uri, records: Map<String, SeenRecord>) {
        store.put(treeUri, records)
        latest.update { it + (treeUri to records) }
    }
}
