// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Tests for the WAL uncommittable-slot escape hatch: listing stranded
 * slots, rescuing one to a new target (raw bytes verbatim, then discard), and explicit discard.
 * Safeguard 1 (never auto-discard / no data loss) + Safeguard 2 (byte-identical rescue).
 *
 * A stranded slot is created with [LocalFileRepository.debugStageWithoutCommit] — it stages content
 * to the WAL + truncates the target without committing, exactly the state a permanently-lost target
 * leaves after recovery can't land it.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryStrandedSlotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File

    @Before
    fun setup() {
        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
    }

    private fun repo() = LocalFileRepository(resolver, walBaseDir)

    /** A real writable, truncating pfd over [file] (models a normal SAF "wt" open). */
    private fun writablePfd(file: File): ParcelFileDescriptor = ParcelFileDescriptor.open(
        file,
        ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
    )

    /** Stage [content] for [uri] and leave it stranded (staged + target truncated, never committed). */
    private fun strand(repo: LocalFileRepository, uri: Uri, content: String) = runBlocking {
        val sink = tempFolder.newFile("sink-${uri.lastPathSegment}")
        every { resolver.openFileDescriptor(uri, "wt") } answers { writablePfd(sink) }
        repo.debugStageWithoutCommit(uri, content)
    }

    @Test
    fun strandedSlotsListsAnUncommittableSlot() = runBlocking {
        val repo = repo()
        val uri = Uri.parse("content://test/doc/lost.md")
        strand(repo, uri, "STRANDED")

        val slots = repo.strandedSlots().getOrThrow()

        assertEquals("one stranded slot", 1, slots.size)
        assertEquals(uri, slots.first().uri)
    }

    @Test
    fun strandedSlotsEmptyWhenNoneStranded() = runBlocking {
        assertTrue(repo().strandedSlots().getOrThrow().isEmpty())
    }

    @Test
    fun saveStrandedSlotToTargetWritesRawBytesVerbatimAndDiscards() = runBlocking {
        // Safeguard 2: the slot bytes (already line-ending-processed at stage time) are written to the
        // new target byte-for-byte — CRLF stays CRLF, never re-processed.
        val repo = repo()
        val uri = Uri.parse("content://test/doc/crlf.md")
        strand(repo, uri, "L1\r\nL2\r\n")
        val key = repo.strandedSlots().getOrThrow().first().key

        val rescued = tempFolder.newFile("rescued.md")
        val newUri = Uri.parse("content://test/doc/rescued.md")
        every { resolver.openFileDescriptor(newUri, "wt") } answers { writablePfd(rescued) }

        val result = repo.saveStrandedSlotToTarget(key, newUri)

        assertTrue(result.isSuccess)
        assertEquals("rescued copy is byte-identical CRLF", "L1\r\nL2\r\n", rescued.readText())
        assertTrue("slot discarded after a successful rescue", repo.strandedSlots().getOrThrow().isEmpty())
    }

    @Test
    fun saveStrandedSlotToTargetKeepsSlotWhenTheWriteFails() = runBlocking {
        // Safeguard 1: a failed rescue never discards the slot — the content stays recoverable.
        val repo = repo()
        val uri = Uri.parse("content://test/doc/keep.md")
        strand(repo, uri, "KEEPME")
        val key = repo.strandedSlots().getOrThrow().first().key

        val newUri = Uri.parse("content://test/doc/failtarget.md")
        every { resolver.openFileDescriptor(newUri, "wt") } throws IOException("target gone")

        val result = repo.saveStrandedSlotToTarget(key, newUri)

        assertTrue(result.isFailure)
        assertEquals("slot kept after a failed rescue", 1, repo.strandedSlots().getOrThrow().size)
    }

    @Test
    fun discardSlotRemovesExactlyThatSlot() = runBlocking {
        val repo = repo()
        val a = Uri.parse("content://test/doc/a.md")
        val b = Uri.parse("content://test/doc/b.md")
        strand(repo, a, "AAA")
        strand(repo, b, "BBB")
        val keyA = repo.strandedSlots().getOrThrow().first { it.uri == a }.key

        repo.discardSlot(keyA)

        val remaining = repo.strandedSlots().getOrThrow()
        assertEquals(1, remaining.size)
        assertEquals("the OTHER slot is untouched", b, remaining.first().uri)
    }

    @Test
    fun rescuingOneSlotLeavesTheOthersIntact() = runBlocking {
        val repo = repo()
        val a = Uri.parse("content://test/doc/a.md")
        val b = Uri.parse("content://test/doc/b.md")
        strand(repo, a, "AAA")
        strand(repo, b, "BBB")
        val keyA = repo.strandedSlots().getOrThrow().first { it.uri == a }.key

        val rescued = tempFolder.newFile("rescuedA.md")
        val newUri = Uri.parse("content://test/doc/rescuedA.md")
        every { resolver.openFileDescriptor(newUri, "wt") } answers { writablePfd(rescued) }
        repo.saveStrandedSlotToTarget(keyA, newUri)

        val remaining = repo.strandedSlots().getOrThrow()
        assertEquals("only B remains", 1, remaining.size)
        assertEquals(b, remaining.first().uri)
        assertEquals("A's bytes were rescued verbatim", "AAA", rescued.readText())
        assertFalse(remaining.any { it.uri == a })
    }
}
