// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Spec section 3: which entries are listed, which are walked, and in what order. */
class NoteListingTest {

    /** A folder tree by document ID, recording every folder it is asked to list. */
    private class FakeSource : ChildSource {
        val folders = mutableMapOf<String, MutableList<ListedChild>>()
        val unreadable = mutableSetOf<String>()
        val queried = mutableListOf<String>()

        fun folder(id: String) {
            folders.getOrPut(id) { mutableListOf() }
        }

        fun add(parent: String, name: String, isFolder: Boolean = false): String {
            val id = "$parent/$name"
            val child = ListedChild(id, name, isFolder, lastModified = null, size = null)
            folders.getOrPut(parent) { mutableListOf() } += child
            if (isFolder) folder(id)
            return id
        }

        override fun children(folderId: String): List<ListedChild>? {
            queried += folderId
            return if (folderId in unreadable) null else folders[folderId]?.toList()
        }
    }

    private fun FolderEntry.names(): List<String> = folders.map { it.name + "/" } + notes.map { it.name }

    /** The folder layout of spec section 5 (without the case 11 and case 24 extras). */
    private fun specLayout() = FakeSource().apply {
        folder("Notes")
        add("Notes", "index.md")
        add("Notes", "Note 2.md")
        add("Notes", "Note 10.md")
        val projects = add("Notes", "projects", isFolder = true)
        add(projects, "plan.md")
        add(projects, "img.png")
        add("Notes", "Música.md") // stored in the decomposed form, NFD
        val obsidian = add("Notes", ".obsidian", isFolder = true)
        add(obsidian, "config.md")
        add("Notes", "empty", isFolder = true)
    }

    @Test
    fun `spec case 15 - folders first, natural order, no hidden or empty folders`() {
        val root = NoteListing.build("Notes", "Notes", specLayout())!!

        // Case 15 names projects/, index.md, Note 2.md, Note 10.md in that order. Música.md is in the
        // same layout: compared without case and in one Unicode form, "m" falls between "i" and "n".
        assertEquals(
            listOf("projects/", "index.md", "Música.md", "Note 2.md", "Note 10.md"),
            root.names(),
        )
        assertEquals(listOf("plan.md"), root.folders.single().names())
        assertEquals(5, root.noteCount)
    }

    @Test
    fun `md and markdown in any case are notes, nothing else is`() {
        val source = FakeSource().apply {
            folder("r")
            listOf("a.MD", "b.Markdown", "c.markdown", "d.md", "e.txt", "f.mdx", "g.md.bak", ".md", "h.pdf")
                .forEach { add("r", it) }
        }
        assertEquals(listOf("a.MD", "b.Markdown", "c.markdown", "d.md"), NoteListing.build("r", "r", source)!!.names())
        assertFalse(NoteListing.isNote(".markdown"))
        assertTrue(NoteListing.isNote("x.MarkDown"))
    }

    @Test
    fun `hidden files are skipped and hidden folders are never read`() {
        val source = FakeSource().apply {
            folder("r")
            add("r", ".secret.md")
            add("r", "shown.md")
            val git = add("r", ".git", isFolder = true)
            add(git, "HEAD.md")
            val trash = add("r", ".trash", isFolder = true)
            add(trash, "old.md")
        }
        val root = NoteListing.build("r", "r", source)!!

        assertEquals(listOf("shown.md"), root.names())
        assertEquals(listOf("r"), source.queried)
    }

    @Test
    fun `folders with no note anywhere below are dropped, at every level`() {
        val source = FakeSource().apply {
            folder("r")
            val a = add("r", "a", isFolder = true)
            val b = add(a, "b", isFolder = true)
            add(b, "c", isFolder = true)
            add(b, "photo.png")
            val keep = add("r", "keep", isFolder = true)
            val deep = add(keep, "deep", isFolder = true)
            add(deep, "x.md")
            add(keep, "nothing", isFolder = true)
        }
        val root = NoteListing.build("r", "r", source)!!

        assertEquals(listOf("keep/"), root.names())
        assertEquals(listOf("deep/"), root.folders.single().names())
        assertEquals(1, root.noteCount)
    }

    @Test
    fun `an unreadable subfolder counts as empty`() {
        val source = FakeSource().apply {
            folder("r")
            val locked = add("r", "locked", isFolder = true)
            add(locked, "x.md")
            val open = add("r", "open", isFolder = true)
            add(open, "y.md")
            unreadable += locked
        }
        val root = NoteListing.build("r", "r", source)!!

        assertEquals(listOf("open/"), root.names())
        assertTrue("the unreadable folder was asked", "r/locked" in source.queried)
    }

    @Test
    fun `an unreadable root gives null, not an empty list`() {
        val source = FakeSource().apply {
            folder("r")
            add("r", "x.md")
            unreadable += "r"
        }
        assertNull(NoteListing.build("r", "r", source))
    }

    @Test
    fun `folders deeper than MAX_DEPTH are not walked`() {
        val source = FakeSource().apply { folder("d0") }
        var parent = "d0"
        val ids = mutableListOf(parent)
        repeat(NoteListing.MAX_DEPTH + 5) { level ->
            source.add(parent, "n$level.md")
            parent = source.add(parent, "d${level + 1}", isFolder = true)
            ids += parent
        }
        val root = NoteListing.build("d0", "d0", source)!!

        var depth = 0
        var folder = root
        while (folder.folders.isNotEmpty()) {
            folder = folder.folders.single()
            depth++
        }
        assertEquals(NoteListing.MAX_DEPTH, depth)
        assertTrue("the last walked folder", ids[NoteListing.MAX_DEPTH] in source.queried)
        assertFalse("one below it", ids[NoteListing.MAX_DEPTH + 1] in source.queried)
    }

    @Test
    fun `ensureActive runs once per folder read, and stops the walk when it throws`() {
        val source = specLayout()
        var calls = 0
        NoteListing.build("Notes", "Notes", source) { calls++ }
        assertEquals(source.queried.size, calls)

        val stopped = specLayout()
        val thrown = runCatching {
            NoteListing.build("Notes", "Notes", stopped) { if (stopped.queried.isNotEmpty()) error("cancelled") }
        }
        assertTrue(thrown.isFailure)
        assertEquals(listOf("Notes"), stopped.queried)
    }

    @Test
    fun `L7 - 5,000 notes in 100 folders are all listed, in order`() {
        val random = Random(7)
        val source = FakeSource().apply { folder("r") }
        val allFolders = mutableListOf<String>()
        // 4 folders, 4 in each, 5 in each of those: 4 + 16 + 80 = 100 folders, three levels deep.
        for (a in 1..4) {
            val l1 = source.add("r", "level $a", isFolder = true).also { allFolders += it }
            for (b in 1..4) {
                val l2 = source.add(l1, "part $b", isFolder = true).also { allFolders += it }
                for (c in 1..5) allFolders += source.add(l2, "leaf $c", isFolder = true)
            }
        }
        for (folder in allFolders) {
            (1..50).shuffled(random).forEach { source.add(folder, "note $it.md") }
            source.folders.getValue(folder).shuffle(random)
        }
        assertEquals(100, allFolders.size)

        val root = NoteListing.build("r", "r", source)!!

        assertEquals(5_000, root.noteCount)
        val expectedNotes = (1..50).map { "note $it.md" }
        fun check(folder: FolderEntry) {
            if (folder !== root) assertEquals(expectedNotes, folder.notes.map { it.name })
            assertEquals(folder.folders.map { it.name }.sortedWith(NaturalOrder), folder.folders.map { it.name })
            folder.folders.forEach { check(it) }
        }
        check(root)
        assertEquals((1..4).map { "level $it" }, root.folders.map { it.name })
    }

    private fun folder(id: String, vararg subfolders: FolderEntry) =
        FolderEntry(id, id, subfolders.toList(), listOf(NoteEntry("$id/n.md", "n.md", null, null)))

    /** r / a / a1, and r / b / b1: the tree the next two tests open and close. */
    private val tree = folder("r", folder("a", folder("a1")), folder("b", folder("b1")))

    @Test
    fun `shown open - the root and each open folder whose parents are all open`() {
        assertEquals(setOf("r"), shownOpenFolderIds(tree, emptySet()))
        assertEquals(setOf("r", "a", "a1"), shownOpenFolderIds(tree, setOf("a", "a1")))
    }

    @Test
    fun `shown open - not a folder left open inside a closed one, nor one no longer in the tree`() {
        // b1 is open but b is closed, so b1 is not on screen and is not watched.
        assertEquals(setOf("r", "a"), shownOpenFolderIds(tree, setOf("a", "b1", "gone")))
    }
}
