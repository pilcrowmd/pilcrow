// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * The draft store on its own, over a real folder (atomic write and byte fidelity).
 *
 * Safeguard 1: a write that fails or is cut short leaves the previous draft whole. Safeguard 2: the
 * text comes back byte for byte.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalDraftRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private val drafts get() = File(baseDir, "drafts")

    @Before
    fun setup() {
        baseDir = tempFolder.newFolder("nobackup")
    }

    private fun uri(name: String): Uri = Uri.parse("content://test/$name.md")

    private fun draft(uri: Uri, text: String = "text", savedAt: Long = 1L, baseline: String = "0:base") =
        Draft(uri, text, lineEnding = "LF", baseline = baseline, savedAt = savedAt)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun draftFiles(): List<String> = drafts.listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `the text comes back byte for byte`() = runBlocking {
        val fixtures = linkedMapOf(
            "CRLF" to "one\r\ntwo\r\n",
            "LF" to "one\ntwo\n",
            "leading U+FEFF" to "﻿one\n",
            "no trailing newline" to "one\ntwo",
            "astral emoji" to "one 😀 two 👩‍💻\n",
            "lone CR" to "one\rtwo\n",
            "empty" to "",
        )
        val repo = LocalDraftRepository(baseDir)
        fixtures.entries.forEachIndexed { i, (name, text) ->
            val target = uri("fidelity$i")
            val written = Draft(target, text, lineEnding = "CRLF", baseline = "12:abc", savedAt = 77L)

            assertTrue(name, repo.write(written).isSuccess)
            val read = repo.read(target)

            assertNotNull("$name: a draft is stored", read)
            assertEquals("$name: bytes", sha256(text), sha256(read!!.text))
            assertEquals("$name: length", text.length, read.text.length)
            assertEquals("$name: the rest of the draft", written, read)
        }
    }

    @Test
    fun `a write that cannot finish leaves the older draft whole`() = runBlocking {
        // T6. The temp file cannot be created because a directory sits at its path, so the new write
        // fails before the rename. The reader must still get the OLDER draft: a write straight into
        // the target would have truncated it first and left the newer text (or half of it) there.
        val target = uri("atomic")
        val repo = LocalDraftRepository(baseDir)
        val older = draft(target, text = "older text, all of it\n", savedAt = 10L, baseline = "1:older")
        assertTrue(repo.write(older).isSuccess)
        assertEquals(older, repo.read(target))
        assertTrue(File(drafts, "${DraftFile.keyOf(target)}.draft.tmp").mkdir())

        val result = repo.write(draft(target, text = "newer text that must not land\n", savedAt = 20L))

        assertTrue("the write reports the failure", result.isFailure)
        assertEquals("the older draft is intact", older, repo.read(target))
    }

    @Test
    fun `a delete issued after a write is not undone by that write landing late`() = runTest {
        // The write is queued on the repository's scope (a test dispatcher that runs nothing until it is
        // told to) while the delete runs at once. When the write finally runs it finds a newer call for
        // the file and must stand down. Without that check it would write the draft back AFTER the delete.
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val repo = LocalDraftRepository(baseDir, scope = scope, io = Dispatchers.Unconfined)
        val deleted = uri("deleted")
        val control = uri("control")

        repo.writeDetached(draft(deleted, text = "must not survive"))
        repo.writeDetached(draft(control, text = "control"))
        assertTrue("nothing has run yet: the writes are queued", draftFiles().isEmpty())
        repo.delete(deleted)
        advanceUntilIdle() // now both queued writes run

        assertNull("the stale write did not resurrect the draft", repo.read(deleted))
        assertEquals(
            "the control write DID run, so the line above is not passing because nothing ran",
            "control",
            repo.read(control)?.text,
        )
        assertEquals(listOf(control), repo.list().map { it.uri })
    }

    @Test
    fun `trim keeps the newest drafts up to the limit, and the file in use`() = runBlocking {
        val repo = LocalDraftRepository(baseDir)
        val uris = (1..5).map { uri("trim$it") }
        uris.forEachIndexed { i, u -> assertTrue(repo.write(draft(u, savedAt = (i + 1) * 100L)).isSuccess) }

        repo.trim(limit = 3, keep = uris[4]) { false }

        assertEquals(listOf(uris[4], uris[3], uris[2]), repo.list().map { it.uri })
    }

    @Test
    fun `trim never removes the file in use even when it is the oldest`() = runBlocking {
        val repo = LocalDraftRepository(baseDir)
        val uris = (1..5).map { uri("keep$it") }
        uris.forEachIndexed { i, u -> assertTrue(repo.write(draft(u, savedAt = (i + 1) * 100L)).isSuccess) }

        repo.trim(limit = 3, keep = uris[0]) { false }

        // Three kept in all: the file in use, and the two newest of the rest.
        assertEquals(listOf(uris[4], uris[3], uris[0]), repo.list().map { it.uri })
    }

    @Test
    fun `trim never removes a protected draft, so the store may exceed the limit`() = runBlocking {
        val repo = LocalDraftRepository(baseDir)
        val uris = (1..5).map { uri("protected$it") }
        uris.forEachIndexed { i, u -> assertTrue(repo.write(draft(u, savedAt = (i + 1) * 100L)).isSuccess) }
        val protectedOnes = uris.take(3).toSet() // the three OLDEST, whose files changed

        repo.trim(limit = 3, keep = uris[4]) { it.uri in protectedOnes }

        // The only unprotected, non-current draft (4th) goes; the three oldest stay although that is 4 > 3.
        assertEquals(listOf(uris[4], uris[2], uris[1], uris[0]), repo.list().map { it.uri })
    }

    @Test
    fun `a temp file left by a kill is swept and is never a draft`() = runBlocking {
        val repo = LocalDraftRepository(baseDir)
        val real = uri("real")
        val onlyTemp = uri("only-temp")
        assertTrue(repo.write(draft(real)).isSuccess)
        val stray = File(drafts, "${DraftFile.keyOf(onlyTemp)}.draft.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val strayBesideReal = File(drafts, "${DraftFile.keyOf(real)}.draft.tmp").apply { writeText("half") }

        val listed = repo.list()

        assertEquals("only the real draft is listed", listOf(real), listed.map { it.uri })
        assertFalse("the stray temp file is gone", stray.exists())
        assertFalse("so is the one beside a real draft", strayBesideReal.exists())
        assertNull("a temp file alone is not a draft", repo.read(onlyTemp))
        assertEquals("the real draft is untouched", draft(real), repo.read(real))
        assertEquals(listOf("${DraftFile.keyOf(real)}.draft"), draftFiles())
    }
}
