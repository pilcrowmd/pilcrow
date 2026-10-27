// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.net.Uri
import com.pilcrowmd.domain.folders.SeenRecord

/**
 * M-162b: the New and Updated marks of each added folder (spec section 3b), remembered across
 * restarts (M5). Kept apart from [NotesFolderStore] in its own preferences file.
 */
interface NoteMarksStore {
    /** The records of the folder [treeUri] by note document ID; null when it was never scanned (M3). */
    suspend fun get(treeUri: Uri): Map<String, SeenRecord>?

    suspend fun put(treeUri: Uri, records: Map<String, SeenRecord>)

    /** Forgets the folder, so a folder added again starts from a fresh baseline (M3). */
    suspend fun drop(treeUri: Uri)
}
