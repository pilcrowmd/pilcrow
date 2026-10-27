// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M-162b: the New and Updated marks (spec section 3b), against the spec's cases 27-35 on the
 * section-5 layout. Case 28's "within about 1 s" is the live listing, tested by the watch test in
 * NotesFoldersViewModelTest (L6); here, the listing that follows marks the new note. Case 32 needs
 * the real store and is in LocalNoteMarksStoreTest.
 */
class NoteMarksTest {

    private fun note(name: String, modified: Long? = 100L, size: Long? = 10L) = NoteEntry(name, name, modified, size)

    private val index = note("index.md")
    private val note2 = note("Note 2.md")
    private val note10 = note("Note 10.md")
    private val plan = note("projects/plan.md")
    private val layout = listOf(index, note2, note10, plan)

    private val root = FolderEntry(
        "Notes",
        "Notes",
        listOf(FolderEntry("projects", "projects", emptyList(), listOf(plan))),
        listOf(index, note2, note10),
    )

    private fun marksOf(records: Map<String, SeenRecord>): Map<String, NoteMark> =
        records.mapNotNull { (id, r) -> NoteMarks.markOf(r)?.let { id to it } }.toMap()

    /** The folder as first scanned at time 1,000. */
    private val baseline = NoteMarks.reconcile(null, layout, now = 1_000L)

    @Test
    fun case27_a_folder_just_added_has_no_marks() {
        assertEquals(layout.map { it.documentId }.toSet(), baseline.keys)
        assertEquals(emptyMap<String, NoteMark>(), marksOf(baseline))
    }

    @Test
    fun case28_a_note_written_into_the_folder_shows_New() {
        val fresh = note("fresh.md")
        val after = NoteMarks.reconcile(baseline, layout + fresh, now = 2_000L)
        assertEquals(mapOf("fresh.md" to NoteMark(Mark.NEW, 2_000L)), marksOf(after))
    }

    @Test
    fun case29_opening_the_new_note_clears_its_mark() {
        val fresh = note("fresh.md")
        val marked = NoteMarks.reconcile(baseline, layout + fresh, now = 2_000L)
        val opened = marked + ("fresh.md" to NoteMarks.seen(fresh.lastModified, fresh.size))
        assertEquals(emptyMap<String, NoteMark>(), marksOf(NoteMarks.reconcile(opened, layout + fresh, now = 3_000L)))
    }

    @Test
    fun case30_a_note_changed_outside_the_app_shows_Updated() {
        val changed = index.copy(lastModified = 200L, size = 12L)
        val after = NoteMarks.reconcile(baseline, listOf(changed, note2, note10, plan), now = 5_000L)
        assertEquals(mapOf("index.md" to NoteMark(Mark.UPDATED, 5_000L)), marksOf(after))
    }

    @Test
    fun case30_Updated_keeps_the_time_it_was_first_seen() {
        val changed = index.copy(lastModified = 200L)
        val first = NoteMarks.reconcile(baseline, listOf(changed, note2, note10, plan), now = 5_000L)
        val later = NoteMarks.reconcile(first, listOf(changed.copy(lastModified = 300L), note2, note10, plan), 9_000L)
        assertEquals(NoteMark(Mark.UPDATED, 5_000L), NoteMarks.markOf(later.getValue("index.md")))
    }

    @Test
    fun case31_saving_in_the_app_never_shows_Updated() {
        val saved = index.copy(lastModified = 200L, size = 14L)
        // The reader reports the save: the values after it become the baseline (M4).
        val afterSave = baseline + ("index.md" to NoteMarks.seen(saved.lastModified, saved.size))
        val listed = NoteMarks.reconcile(afterSave, listOf(saved, note2, note10, plan), now = 5_000L)
        assertEquals(emptyMap<String, NoteMark>(), marksOf(listed))
    }

    @Test
    fun case33_Mark_all_as_seen_clears_the_folder_and_its_subfolders() {
        val fresh = note("fresh.md")
        val changedPlan = plan.copy(size = 99L)
        val notes = listOf(index, note2, note10, changedPlan, fresh)
        val marked = NoteMarks.reconcile(baseline, notes, now = 2_000L)
        assertEquals(setOf("fresh.md", "projects/plan.md"), marksOf(marked).keys)

        val seen = NoteMarks.markAllSeen(notes)
        assertEquals(emptyMap<String, NoteMark>(), marksOf(seen))
        assertEquals(emptyMap<String, NoteMark>(), marksOf(NoteMarks.reconcile(seen, notes, now = 3_000L)))
    }

    @Test
    fun case34_time_going_backwards_with_a_size_change_shows_Updated() {
        val back = index.copy(lastModified = 50L, size = 11L)
        val after = NoteMarks.reconcile(baseline, listOf(back, note2, note10, plan), now = 5_000L)
        assertEquals(NoteMark(Mark.UPDATED, 5_000L), NoteMarks.markOf(after.getValue("index.md")))
    }

    @Test
    fun case34_time_going_backwards_alone_shows_Updated_too() {
        // Compared with the stored value, never with the clock: an older date is still a change.
        val back = index.copy(lastModified = 50L)
        val after = NoteMarks.reconcile(baseline, listOf(back, note2, note10, plan), now = 5_000L)
        assertEquals(Mark.UPDATED, NoteMarks.markOf(after.getValue("index.md"))?.mark)
    }

    @Test
    fun case35_a_renamed_note_shows_New() {
        val renamed = note("Note 3.md")
        val after = NoteMarks.reconcile(baseline, listOf(index, renamed, note10, plan), now = 4_000L)
        assertEquals(mapOf("Note 3.md" to NoteMark(Mark.NEW, 4_000L)), marksOf(after))
        assertNull("the old name's record is dropped", after["Note 2.md"])
    }

    @Test
    fun with_neither_value_known_a_note_is_never_Updated() {
        val blind = NoteEntry("index.md", "index.md", lastModified = null, size = null)
        val first = NoteMarks.reconcile(null, listOf(blind), now = 1_000L)
        val changed = NoteEntry("index.md", "index.md", lastModified = 999L, size = 999L)
        assertNull(NoteMarks.markOf(NoteMarks.reconcile(first, listOf(changed), now = 2_000L).getValue("index.md")))
    }

    @Test
    fun a_value_known_on_one_side_only_is_not_compared() {
        val sizeOnly = NoteEntry("index.md", "index.md", lastModified = null, size = 10L)
        val first = NoteMarks.reconcile(null, listOf(sizeOnly), now = 1_000L)
        val sameSizeNewDate = NoteEntry("index.md", "index.md", lastModified = 500L, size = 10L)
        assertNull(NoteMarks.markOf(NoteMarks.reconcile(first, listOf(sameSizeNewDate), 2_000L).getValue("index.md")))
    }

    @Test
    fun a_New_note_stays_New_when_it_changes_later() {
        val fresh = note("fresh.md")
        val marked = NoteMarks.reconcile(baseline, layout + fresh, now = 2_000L)
        val changed = NoteMarks.reconcile(marked, layout + fresh.copy(size = 500L), now = 3_000L)
        assertEquals(NoteMark(Mark.NEW, 2_000L), NoteMarks.markOf(changed.getValue("fresh.md")))
    }

    @Test
    fun counts_give_each_folder_its_marked_notes_and_the_root_the_total() {
        val marks = mapOf(
            "projects/plan.md" to NoteMark(Mark.UPDATED, 1L),
            "index.md" to NoteMark(Mark.NEW, 1L),
        )
        assertEquals(mapOf("Notes" to 2, "projects" to 1), NoteMarks.markedCounts(root, marks))
    }

    @Test
    fun ago_is_never_negative_and_rounds_down() {
        assertEquals(Ago.JustNow, NoteMarks.ago(since = 10_000L, now = 5_000L))
        assertEquals(Ago.JustNow, NoteMarks.ago(0L, 59_999L))
        assertEquals(Ago.Minutes(12), NoteMarks.ago(0L, 12 * 60_000L + 59_999L))
        assertEquals(Ago.Hours(1), NoteMarks.ago(0L, 60 * 60_000L))
        assertEquals(Ago.Days(2), NoteMarks.ago(0L, 2 * 24 * 60 * 60_000L))
    }
}
