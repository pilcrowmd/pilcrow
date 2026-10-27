// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import java.util.Locale

/** One entry of a folder, as the folder source reports it. */
data class ListedChild(
    val documentId: String,
    val name: String,
    val isFolder: Boolean,
    val lastModified: Long?,
    val size: Long?,
)

/** Reads one folder's entries; null when the folder could not be read. */
fun interface ChildSource {
    fun children(folderId: String): List<ListedChild>?
}

data class NoteEntry(val documentId: String, val name: String, val lastModified: Long?, val size: Long?)

/** A folder in the notes list: its subfolders first, then its notes, each in [NaturalOrder]. */
data class FolderEntry(
    val documentId: String,
    val name: String,
    val folders: List<FolderEntry>,
    val notes: List<NoteEntry>,
) {
    /** Every note in this folder and all folders below it. */
    val noteCount: Int = notes.size + folders.sumOf { it.noteCount }
}

/**
 * The notes list rules (spec section 3): which entries are notes (L1), which are hidden (L3), which
 * folders are dropped (L4), and the order (L5).
 */
object NoteListing {

    /** Folders deeper than this below the root are not walked: a guard against a folder loop. */
    const val MAX_DEPTH = 64

    private val noteExtensions = listOf(".md", ".markdown")

    /** L1: `.md` or `.markdown` in any capitalisation, with a non-empty name in front. */
    fun isNote(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return noteExtensions.any { lower.endsWith(it) && lower.length > it.length }
    }

    /** L3: `.obsidian`, `.git`, `.trash` and every other name starting with a dot. */
    fun isHidden(name: String): Boolean = name.startsWith(".")

    /**
     * Walks the folder [rootId] through [source]. Null when the root itself cannot be read, so the
     * caller can say so instead of showing an empty list (F4). A subfolder that cannot be read
     * counts as empty. [ensureActive] runs once per folder, so a cancelled listing stops early.
     */
    fun build(rootId: String, rootName: String, source: ChildSource, ensureActive: () -> Unit = {}): FolderEntry? {
        ensureActive()
        val children = source.children(rootId) ?: return null
        return Walk(source, ensureActive).folder(rootId, rootName, children, depth = 0)
    }

    private class Walk(private val source: ChildSource, private val ensureActive: () -> Unit) {

        fun folder(id: String, name: String, children: List<ListedChild>, depth: Int): FolderEntry {
            // Hidden entries go first, so a hidden folder (.git) is never read at all.
            val shown = children.filterNot { isHidden(it.name) }
            val folders = if (depth >= MAX_DEPTH) {
                emptyList()
            } else {
                shown.filter { it.isFolder }.mapNotNull { child ->
                    ensureActive()
                    val below = source.children(child.documentId).orEmpty()
                    folder(child.documentId, child.name, below, depth + 1).takeIf { it.noteCount > 0 }
                }.sortedWith(compareBy(NaturalOrder) { it.name })
            }
            val notes = shown.filter { !it.isFolder && isNote(it.name) }
                .map { NoteEntry(it.documentId, it.name, it.lastModified, it.size) }
                .sortedWith(compareBy(NaturalOrder) { it.name })
            return FolderEntry(id, name, folders, notes)
        }
    }
}

/**
 * L6 (section 6, Android): the folders of [root] that are shown open, which are the ones Android
 * can watch: the root itself, and every subfolder in [expanded] whose parents are all open too. A
 * subfolder left open inside a closed one is not shown, and an ID no longer in the tree is ignored.
 */
fun shownOpenFolderIds(root: FolderEntry, expanded: Set<String>): Set<String> {
    val shown = mutableSetOf(root.documentId)
    val open = ArrayDeque(listOf(root))
    while (open.isNotEmpty()) {
        for (sub in open.removeFirst().folders) {
            if (sub.documentId in expanded) {
                shown += sub.documentId
                open += sub
            }
        }
    }
    return shown
}
