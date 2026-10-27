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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * [LocalFileRepository.readFile] reads at most [MAX_FILE_BYTES]. Before the cap it read until EOF,
 * so a remembered document that grew huge, or a provider that served an endless stream, exhausted
 * the heap on every cold start.
 *
 * Only the ContentResolver is mocked, and only to hand out REAL descriptors over files on disk, as
 * [LocalFileRepositoryEncodingTest] does. The endless stream is the case a size check alone cannot
 * answer: it reports no size, so the read itself has to stop.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryReadCapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private lateinit var target: File
    private val uri: Uri = Uri.parse("content://test/document/doc.md")

    @Before
    fun setup() {
        walBaseDir = tempFolder.newFolder("nobackup")
        target = tempFolder.newFile("doc.md")
        resolver = mockk {
            every { openFileDescriptor(uri, "r") } answers {
                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    private fun repo() = LocalFileRepository(resolver, walBaseDir)

    /** A file of exactly [bytes] zero bytes (valid UTF-8), without writing them out. */
    private fun sizeTarget(bytes: Long) = RandomAccessFile(target, "rw").use { it.setLength(bytes) }

    @Test
    fun `a file over the cap is refused with FileTooLargeException`() = runBlocking {
        sizeTarget(MAX_FILE_BYTES + 1L)

        val result = repo().readFile(uri)

        assertTrue("an over-cap file must not be read", result.isFailure)
        assertTrue(
            "the failure must say too large, was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is FileTooLargeException,
        )
    }

    /** Control, and the off-by-one: a file of exactly the cap is still read. */
    @Test
    fun `a file of exactly the cap is read`() = runBlocking {
        sizeTarget(MAX_FILE_BYTES.toLong())

        val result = repo().readFile(uri)

        assertEquals(MAX_FILE_BYTES, result.getOrThrow().content.length)
    }

    /**
     * An endless stream reports no size (statSize -1 on a device), so only the bounded read can end
     * it. /dev/zero is that stream, real and deterministic: Robolectric's own pipe is file-backed, so
     * a reader that outruns its writer sees an early end of file and the test would pass or fail on
     * thread timing. The failure must be the cap, not an OutOfMemoryError mapped to the same type.
     */
    @Test(timeout = 60_000)
    fun `an endless stream with no reported size ends in FileTooLargeException`() {
        val endless = mockk<ParcelFileDescriptor> {
            every { statSize } returns -1L
            every { fileDescriptor } returns FileInputStream("/dev/zero").fd
            every { close() } returns Unit
        }
        every { resolver.openFileDescriptor(uri, "r") } returns endless

        val result = runBlocking { repo().readFile(uri) }

        val failure = result.exceptionOrNull()
        assertTrue("the endless stream must fail, not be read, was $result", failure is FileTooLargeException)
        assertTrue(
            "the cap must end the read, not the heap: ${failure?.message}",
            failure?.message?.contains("memory") == false,
        )
    }

    /** The WAL branch reads the staged copy of an interrupted save; it is bounded the same way. */
    @Test
    fun `a staged copy over the cap is refused with FileTooLargeException`() = runBlocking {
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            FileOutputStream(target).close() // the "wt" open truncates, then the write dies
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        assertTrue(repo().saveFile(uri, "a".repeat(MAX_FILE_BYTES + 1)).isFailure)

        val result = repo().readFile(uri)

        assertTrue(
            "the staged copy must be refused, was $result",
            result.exceptionOrNull() is FileTooLargeException,
        )
    }

    /** An allocation failure on the read is a failed Result, not a process-fatal Error. */
    @Test
    fun `running out of memory on the read is a failed Result`() = runBlocking {
        every { resolver.openFileDescriptor(uri, "r") } throws OutOfMemoryError("test")

        val result = repo().readFile(uri)

        assertTrue("OutOfMemoryError must become a failure, was $result", result.isFailure)
    }
}
