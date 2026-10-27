// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import com.pilcrowmd.repository.NotesTreeFixture.OTHER
import com.pilcrowmd.repository.NotesTreeFixture.PROJECTS
import com.pilcrowmd.repository.NotesTreeFixture.ROOT
import com.pilcrowmd.repository.NotesTreeFixture.folder
import com.pilcrowmd.repository.NotesTreeFixture.inNotes
import com.pilcrowmd.repository.NotesTreeFixture.notesTree
import com.pilcrowmd.repository.NotesTreeFixture.otherTree
import com.pilcrowmd.repository.NotesTreeFixture.plain
import com.pilcrowmd.repository.NotesTreeFixture.projectsTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-160: which added folder applies to a note, and what its links resolve to, on the section 5 layout
 * (notes-folder spec, section 4 Android rule: only when the folder permission confirms it).
 */
@RunWith(RobolectricTestRunner::class)
class LocalNoteLinkRepositoryTest {

    private fun repo(vararg added: NotesFolder) =
        LocalNoteLinkRepository(NotesTreeFixture.Tree(), NotesTreeFixture.Folders(*added))

    @Test
    fun case25_note_opened_from_recents_resolves_through_its_folder() = runBlocking {
        val repo = repo(folder(notesTree))
        val index = plain("$ROOT/index.md")
        assertEquals(NoteFolderContext(notesTree, ROOT, listOf(ROOT)), repo.folderFor(index))
        assertEquals(
            ResolvedLink.OpenNote(inNotes("$PROJECTS/plan.md"), null),
            repo.resolve(index, "projects/plan.md"),
        )
    }

    @Test
    fun note_outside_every_added_folder_keeps_todays_behaviour() = runBlocking {
        val repo = repo(folder(notesTree))
        val secret = plain("$OTHER/secret.md")
        assertNull(repo.folderFor(secret))
        assertEquals(ResolvedLink.Unhandled, repo.resolve(secret, "index.md"))
        assertEquals(emptySet<String>(), repo.resolvableBareLinks(secret, "[x](Note 2.md)"))
    }

    @Test
    fun an_unavailable_folder_does_not_apply() = runBlocking {
        val repo = repo(folder(notesTree, available = false))
        assertNull(repo.folderFor(inNotes("$ROOT/index.md")))
    }

    @Test
    fun nested_folders_use_the_outermost_root_whatever_the_order_added() = runBlocking {
        // projects/ added first: the order added must not decide (K2/P14).
        val repo = repo(folder(projectsTree, "projects"), folder(notesTree), folder(otherTree, "Other"))
        val plan = plain("$PROJECTS/plan.md")
        assertEquals(NoteFolderContext(notesTree, ROOT, listOf(ROOT, PROJECTS)), repo.folderFor(plan))
        assertEquals(ResolvedLink.OpenNote(inNotes("$ROOT/index.md"), "top"), repo.resolve(plan, "/index.md#top"))
    }

    @Test
    fun k8_a_file_that_is_not_a_note_is_handed_to_the_system() = runBlocking {
        val link = repo(folder(notesTree)).resolve(inNotes("$ROOT/index.md"), "projects/img.png")
        assertTrue(link is ResolvedLink.OpenFile)
        assertEquals(inNotes("$PROJECTS/img.png"), (link as ResolvedLink.OpenFile).uri)
    }

    @Test
    fun k7_a_missing_note_is_not_found() = runBlocking {
        assertEquals(
            ResolvedLink.NotFound("No such note.md"),
            repo(folder(notesTree)).resolve(inNotes("$ROOT/index.md"), "No%20such%20note.md"),
        )
    }

    @Test
    fun bare_links_only_those_that_name_a_note() = runBlocking {
        val content = "see [x](Note 2.md) and [y](No such note.md) and <Note 2.md>"
        val found = repo(folder(notesTree)).resolvableBareLinks(inNotes("$ROOT/index.md"), content)
        assertEquals(setOf("Note 2.md"), found)
    }

    @Test
    fun same_document_whether_tree_based_or_not() {
        val repo = repo(folder(notesTree))
        assertTrue(repo.sameDocument(plain("$ROOT/index.md"), inNotes("$ROOT/index.md")))
        assertTrue(!repo.sameDocument(plain("$ROOT/index.md"), inNotes("$PROJECTS/plan.md")))
    }
}
