// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import com.pilcrowmd.domain.folders.Ago
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.domain.folders.NoteMark
import com.pilcrowmd.domain.folders.NoteMarks

/**
 * One shown row of the Files list; [depth] 0 is directly inside the ticked folder. [key] is unique in
 * the list: the row's path of document IDs, since a provider may show one document in two folders
 * (Drive lets a file have two parents), so the document ID alone is not.
 */
sealed interface FileRowUi {
    val documentId: String
    val name: String
    val depth: Int
    val key: String

    /** A subfolder; [markCount] is the number of marked notes anywhere below it (M7). */
    data class Folder(
        override val documentId: String,
        override val name: String,
        override val depth: Int,
        val expanded: Boolean,
        val markCount: Int,
        override val key: String = documentId,
    ) : FileRowUi

    /** A note; [mark] and [ago] are null when it is unmarked (M1, M2, M8). */
    data class Note(
        override val documentId: String,
        override val name: String,
        override val depth: Int,
        val mark: Mark?,
        val ago: Ago?,
        override val key: String = documentId,
    ) : FileRowUi
}

/**
 * The rows the Files list shows, in order: each folder's subfolders, then its notes (L5), and the
 * contents of an open subfolder (L2) right under it. Built once per state change, outside
 * composition, so a 5,000-note folder scrolls without work per frame (L7). A document a provider
 * lists twice in the same folder is shown once.
 */
fun fileTreeRows(
    root: FolderEntry,
    expanded: Set<String>,
    marks: Map<String, NoteMark>,
    counts: Map<String, Int>,
    now: Long,
): List<FileRowUi> {
    val rows = ArrayList<FileRowUi>()
    val keys = HashSet<String>()
    fun add(folder: FolderEntry, depth: Int, parentKey: String) {
        for (sub in folder.folders) {
            val key = parentKey + KEY_SEP + sub.documentId
            if (!keys.add(key)) continue
            val open = sub.documentId in expanded
            rows += FileRowUi.Folder(sub.documentId, sub.name, depth, open, counts[sub.documentId] ?: 0, key)
            if (open) add(sub, depth + 1, key)
        }
        for (note in folder.notes) {
            val key = parentKey + KEY_SEP + note.documentId
            if (!keys.add(key)) continue
            val mark = marks[note.documentId]
            val ago = mark?.let { NoteMarks.ago(it.since, now) }
            rows += FileRowUi.Note(note.documentId, note.name, depth, mark?.mark, ago, key)
        }
    }
    add(root, 0, "")
    return rows
}

// Cannot occur in a document ID, so two different paths never make the same key.
private const val KEY_SEP = "\u0000"
