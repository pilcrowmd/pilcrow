// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.NoteEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** M-162b: the rows of the Files list have keys unique in the list, whatever the provider returns. */
class FileTreeRowsTest {

    private val shared = NoteEntry("doc-1", "shared.md", 1L, 1L)

    @Test
    fun `one document in two open folders is two rows with different keys`() {
        // Drive lets one file have two parents: the same document ID under both folders.
        val root = FolderEntry(
            "root",
            "Notes",
            listOf(
                FolderEntry("f1", "one", emptyList(), listOf(shared)),
                FolderEntry("f2", "two", emptyList(), listOf(shared)),
            ),
            emptyList(),
        )
        val rows = fileTreeRows(root, expanded = setOf("f1", "f2"), emptyMap(), emptyMap(), now = 0L)

        assertEquals(listOf("one", "shared.md", "two", "shared.md"), rows.map { it.name })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `a document listed twice in one folder is shown once`() {
        val root = FolderEntry("root", "Notes", emptyList(), listOf(shared, shared))
        val rows = fileTreeRows(root, expanded = emptySet(), emptyMap(), emptyMap(), now = 0L)
        assertEquals(listOf("shared.md"), rows.map { it.name })
    }
}
