// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.folders.Mark
import com.pilcrowmd.domain.folders.NoteEntry
import com.pilcrowmd.domain.folders.NoteMark
import com.pilcrowmd.domain.folders.NoteMarks
import com.pilcrowmd.domain.folders.SeenRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** M-162b: the marks survive a restart (M5), each folder apart, and a hostile note ID cannot corrupt them. */
@RunWith(RobolectricTestRunner::class)
class LocalNoteMarksStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val jobs = mutableListOf<Job>()
    private val file: File by lazy { tempFolder.newFile("notes_marks_test.preferences_pb") }

    private val notes = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes")
    private val other = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AOther")

    @After
    fun tearDown() = restart()

    /** A store over [file]; each call is a fresh instance, as after a restart. */
    private fun store(): LocalNoteMarksStore {
        val job = Job().also { jobs += it }
        val scope = CoroutineScope(Dispatchers.IO + job)
        val context: Context = ApplicationProvider.getApplicationContext()
        return LocalNoteMarksStore(context, PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    /** Ends the running instance, so a new one may open the same file (one DataStore per file). */
    private fun restart() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    @Test
    fun `a folder never scanned has no records, a scanned empty one has empty records`() = runBlocking {
        val store = store()
        assertNull(store.get(notes))
        store.put(notes, emptyMap())
        assertEquals(emptyMap<String, SeenRecord>(), store.get(notes))
        assertNull(store.get(other))
    }

    @Test
    fun case32_restart_keeps_the_Updated_mark() = runBlocking {
        val index = NoteEntry("primary:Notes/index.md", "index.md", 100L, 10L)
        val baseline = NoteMarks.reconcile(null, listOf(index), now = 1_000L)
        val updated = NoteMarks.reconcile(baseline, listOf(index.copy(lastModified = 200L)), now = 5_000L)
        store().put(notes, updated)

        restart()
        val read = store().get(notes)

        assertEquals(updated, read)
        assertEquals(NoteMark(Mark.UPDATED, 5_000L), NoteMarks.markOf(read!!.getValue(index.documentId)))
    }

    @Test
    fun `hostile IDs and unknown values round-trip exactly`() = runBlocking {
        val records = mapOf(
            "a\tb" to SeenRecord(null, null, isNew = true, newSince = 7L, updatedSince = null),
            "line\nbreak%25" to SeenRecord(-5L, 0L, isNew = false, newSince = null, updatedSince = 9L),
            "msf:47" to SeenRecord(Long.MAX_VALUE, null, isNew = false, newSince = null, updatedSince = null),
        )
        store().put(notes, records)
        restart()
        assertEquals(records, store().get(notes))
    }

    @Test
    fun `putting one folder keeps the other's marks, also after a restart`() = runBlocking {
        val record = SeenRecord(1L, 2L, isNew = false, newSince = null, updatedSince = null)
        val first = store()
        first.put(notes, mapOf("x" to record))
        first.put(other, mapOf("y" to record))
        first.put(notes, mapOf("x2" to record))

        restart()
        val second = store()

        assertEquals(mapOf("x2" to record), second.get(notes))
        assertEquals(mapOf("y" to record), second.get(other))
    }

    @Test
    fun `dropping one folder keeps the other`() = runBlocking {
        val store = store()
        val record = SeenRecord(1L, 2L, isNew = false, newSince = null, updatedSince = null)
        store.put(notes, mapOf("x" to record))
        store.put(other, mapOf("y" to record))

        store.drop(notes)

        assertNull(store.get(notes))
        assertEquals(mapOf("y" to record), store.get(other))
    }
}
