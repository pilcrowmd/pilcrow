// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
import java.io.FileOutputStream

/**
 * NEW-12: [LocalFileRepository.readFile] reports whether a file's bytes were valid UTF-8, so a file
 * that saving would rewrite in another encoding is never mistaken for one it can save.
 *
 * The four non-UTF-8 cases are the round-trip corpus findings (PR #239), each of which a save used
 * to rewrite: the invalid sequences became U+FFFD (`EF BF BD`) on disk. The ViewModel refuses those
 * saves ([com.pilcrowmd.viewmodel.MarkdownViewModelNotUtf8SaveTest]); what this suite pins is the
 * signal that refusal depends on, and that valid UTF-8 — a BOM and CRLF included — still saves back
 * byte for byte.
 *
 * Only the ContentResolver is mocked, and only to hand out REAL descriptors over files on disk, as
 * [LocalFileRepositoryAtomicSaveTest] does.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryEncodingTest {

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
            every { openFileDescriptor(uri, "wt") } answers {
                ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }
        }
    }

    private fun repo() = LocalFileRepository(resolver, walBaseDir)

    @Test
    fun eachNonUtf8CorpusFileIsReportedAsNotUtf8AndReadingLeavesItsBytesAlone() = runBlocking {
        for ((name, bytes) in NotUtf8Corpus.cases) {
            target.writeBytes(bytes)

            val read = repo().readFile(uri).getOrThrow()

            assertFalse("$name must be reported as not UTF-8", read.isUtf8)
            assertTrue("$name is still shown, decoded leniently", read.content.contains('�'))
            assertArrayEquals("$name: reading must not change a byte", bytes, target.readBytes())
        }
    }

    /** Controls: valid UTF-8 is reported as such and saves back unchanged, byte for byte. */
    @Test
    fun validUtf8IncludingABomAndCrlfSavesBackByteIdentical() = runBlocking {
        val controls = mapOf(
            "plain" to "# Title\n\nCafé, naïve, 5 €, 日本語, 🙂\n".toByteArray(Charsets.UTF_8),
            "bom" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# Title\n\nwith a BOM\n".toByteArray(Charsets.UTF_8),
            "bom+crlf" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "# Title\r\n\r\nCafé\r\n".toByteArray(Charsets.UTF_8),
            "empty" to ByteArray(0),
        )
        for ((name, bytes) in controls) {
            target.writeBytes(bytes)
            val read = repo().readFile(uri).getOrThrow()
            assertTrue("$name is valid UTF-8", read.isUtf8)

            // The save must actually write: a save that never did would leave the bytes in place.
            target.writeBytes("SENTINEL".toByteArray())
            assertTrue(repo().saveFile(uri, read.content).isSuccess)

            assertArrayEquals("$name must save back byte-identical", bytes, target.readBytes())
        }
    }

    /** A BOM is U+FEFF in the text, so it reaches the editor and is written back, not dropped. */
    @Test
    fun aUtf8BomIsKeptInTheText() = runBlocking {
        target.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray())

        assertEquals("﻿x", repo().readFile(uri).getOrThrow().content)
    }

    /**
     * The other read path: a save interrupted mid-commit is served from its WAL slot. Its bytes were
     * written by a save, so they are UTF-8, and the slot is reported as such.
     */
    @Test
    fun aReadServedFromAnInterruptedSaveIsDecodedTheSameWay() = runBlocking {
        target.writeBytes(NotUtf8Corpus.cases.getValue("windows-1252.md"))
        every { resolver.openFileDescriptor(uri, "wt") } answers {
            FileOutputStream(target).close() // the "wt" open truncates, then the write dies
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        assertTrue(repo().saveFile(uri, "Café\n").isFailure)

        val read = repo().readFile(uri).getOrThrow()

        assertEquals("Café\n", read.content)
        assertTrue(read.isUtf8)
    }
}

/**
 * The round-trip corpus's non-UTF-8 findings (PR #239, `NonUtf8Samples`), copied rather than shared
 * because that branch is not merged. Each was saved back as U+FFFD before NEW-12.
 */
internal object NotUtf8Corpus {
    private fun latin(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    val cases: Map<String, ByteArray> = mapOf(
        // Windows-1252: smart quotes, euro, dashes, ellipsis and every byte in 0x80..0x9F.
        "windows-1252.md" to latin("Café menu: ") + bytes(0x93) + latin("quoted") + bytes(0x94) +
            latin(" costs ") + bytes(0x80) + latin("5 ") + bytes(0x96) + latin(" long") + bytes(0x97) +
            latin("dash") + bytes(0x85) + latin("\n\nAll: ") + ByteArray(0x20) { (0x80 + it).toByte() } +
            latin("\n"),
        // ISO-8859-1: accented Latin letters and every byte in 0xA0..0xFF.
        "latin-1.md" to latin("# Straße und Käse\n\nGrüße aus München © 2026, 20°C.\n\nAll: ") +
            ByteArray(0x60) { (0xA0 + it).toByte() } + latin("\n"),
        // UTF-16 little-endian with a byte-order mark.
        "utf-16le-bom.md" to bytes(0xFF, 0xFE) +
            "# Title\r\n\r\nPlain text saved as UTF-16 LE, café.\r\n".toByteArray(Charsets.UTF_16LE),
        // UTF-8 cut off in the middle of a multi-byte character at end of file.
        "truncated-utf-8.md" to "Price: 5 ".toByteArray(Charsets.UTF_8) + bytes(0xE2, 0x82),
    )
}
