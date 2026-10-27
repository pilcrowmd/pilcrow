// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import android.os.Looper
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.repository.DocumentTree
import com.pilcrowmd.repository.LocalNoteLinkRepository
import com.pilcrowmd.repository.NotesTreeFixture
import com.pilcrowmd.repository.NotesTreeFixture.OTHER
import com.pilcrowmd.repository.NotesTreeFixture.PROJECTS
import com.pilcrowmd.repository.NotesTreeFixture.ROOT
import com.pilcrowmd.repository.NotesTreeFixture.inNotes
import com.pilcrowmd.repository.NotesTreeFixture.notesTree
import com.pilcrowmd.repository.NotesTreeFixture.plain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The folder names the open-file line shows for a note opened from My folders. Every wait is on the
 * names the call publishes: the value in place before they arrive is the document with no folder
 * names, which is none of the answers asserted below.
 */
@RunWith(RobolectricTestRunner::class)
class NoteFolderPathTest {

    private val plan = inNotes("$PROJECTS/plan.md")
    private val index = plain("$ROOT/index.md")
    private val secret = plain("$OTHER/secret.md")

    private fun links(tree: DocumentTree = NotesTreeFixture.Tree()) = NoteLinksViewModel(
        LocalNoteLinkRepository(tree, NotesTreeFixture.Folders(NotesTreeFixture.folder(notesTree))),
    )

    /** A provider that names every document "Name of <last part of its ID>". */
    private fun namingTree(): DocumentTree = object : DocumentTree by NotesTreeFixture.Tree() {
        override fun displayName(treeUri: Uri, documentId: String) = "Name of ${documentId.substringAfterLast('/')}"
    }

    private fun awaitPath(links: NoteLinksViewModel, expected: NoteFolderPath) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (links.folderPath.value == expected) return
            Thread.sleep(10L)
        }
        assertEquals(expected, links.folderPath.value)
    }

    @Test
    fun aNoteInASubfolderGetsTheAddedFolderThenTheSubfolderByName() {
        val links = links(namingTree())
        links.onDocumentShown(plan, "# Plan")
        awaitPath(links, NoteFolderPath(plan, listOf("Name of primary:Notes", "Name of projects")))
    }

    @Test
    fun aNoteDirectlyInTheAddedFolderGetsOnlyThatFolder() {
        val links = links(namingTree())
        links.onDocumentShown(index, "# Index")
        awaitPath(links, NoteFolderPath(index, listOf("Name of primary:Notes")))
    }

    @Test
    fun aFolderTheProviderDoesNotNameIsNamedAfterItsId() {
        val links = links()
        links.onDocumentShown(plan, "# Plan")
        awaitPath(links, NoteFolderPath(plan, listOf("Notes", "projects")))
    }

    @Test
    fun theNextNoteNeverShowsThePreviousNotesFolders() {
        val links = links(namingTree())
        links.onDocumentShown(plan, "# Plan")
        awaitPath(links, NoteFolderPath(plan, listOf("Name of primary:Notes", "Name of projects")))

        links.onDocumentShown(secret, "# Secret") // in no added folder, so its names never arrive
        awaitPath(links, NoteFolderPath(secret, emptyList()))
    }

    @Test
    fun theLineUsesTheFolderNamesOnlyOfItsOwnDocument() {
        val path = NoteFolderPath(plan, listOf("Notes", "projects"))
        assertEquals(OpenFileLine("plan.md", listOf("Notes", "projects")), openFileLine("plan.md", plan, path))
        assertEquals(OpenFileLine("index.md"), openFileLine("index.md", index, path))
        assertEquals(OpenFileLine("plan.md"), openFileLine("plan.md", plan, null))
    }

    @Test
    fun aDocumentWithNoNameHasNoLine() {
        assertNull(openFileLine("", plan, NoteFolderPath(plan, listOf("Notes"))))
        assertNull(openFileLine("  ", plan, null))
    }
}
