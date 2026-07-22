// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.UriPermission
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests for the read-only [LocalFileRepository.hasPersistedWritePermission] query.
 * It is the honest signal for "can this document be saved in place?" — a doc opened
 * read-only via "Open with" holds at most a read grant, so in-place save would fail and the UI must
 * route to Save-As instead. The query touches no save path; it only reads persistedUriPermissions.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryWritePermissionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private val target: Uri = Uri.parse("content://test/document/doc.md")
    private val other: Uri = Uri.parse("content://test/document/other.md")

    @Before
    fun setup() {
        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
    }

    private fun permission(uri: Uri, read: Boolean, write: Boolean): UriPermission = mockk {
        every { this@mockk.uri } returns uri
        every { isReadPermission } returns read
        every { isWritePermission } returns write
    }

    @Test
    fun returnsTrueWhenAPersistedWriteGrantIsHeld() {
        every { resolver.persistedUriPermissions } returns listOf(permission(target, read = true, write = true))

        assertTrue(LocalFileRepository(resolver, walBaseDir).hasPersistedWritePermission(target))
    }

    @Test
    fun returnsFalseWhenOnlyAReadGrantIsHeld() {
        every { resolver.persistedUriPermissions } returns listOf(permission(target, read = true, write = false))

        assertFalse(LocalFileRepository(resolver, walBaseDir).hasPersistedWritePermission(target))
    }

    @Test
    fun returnsFalseWhenNoGrantForThisUri() {
        every { resolver.persistedUriPermissions } returns listOf(permission(other, read = true, write = true))

        assertFalse(LocalFileRepository(resolver, walBaseDir).hasPersistedWritePermission(target))
    }

    @Test
    fun returnsFalseWhenNoPersistedPermissionsAtAll() {
        every { resolver.persistedUriPermissions } returns emptyList()

        assertFalse(LocalFileRepository(resolver, walBaseDir).hasPersistedWritePermission(target))
    }
}
