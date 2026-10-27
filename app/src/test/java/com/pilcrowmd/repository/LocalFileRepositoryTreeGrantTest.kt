// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.content.UriPermission
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-162b: a note opened from a notes folder has a tree-based URI and no grant of its own; the
 * folder's tree grant covers it, so Recents shows it as available and Save writes it in place.
 * Only a grant on the same tree of the same provider counts.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryTreeGrantTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val resolver: ContentResolver = mockk(relaxed = false)

    private val authority = "com.android.externalstorage.documents"
    private val notesTree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Notes")
    private val otherTree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Other")
    private val note = DocumentsContract.buildDocumentUriUsingTree(notesTree, "primary:Notes/index.md")

    private fun permission(uri: Uri, read: Boolean, write: Boolean): UriPermission = mockk {
        every { this@mockk.uri } returns uri
        every { isReadPermission } returns read
        every { isWritePermission } returns write
    }

    private fun repo(vararg grants: UriPermission): LocalFileRepository {
        every { resolver.persistedUriPermissions } returns grants.toList()
        return LocalFileRepository(resolver, tempFolder.newFolder())
    }

    @Test
    fun `a read and write grant on the folder covers its note`() {
        val repo = repo(permission(notesTree, read = true, write = true))
        assertTrue(repo.hasPersistedPermission(note))
        assertTrue(repo.hasPersistedWritePermission(note))
    }

    @Test
    fun `a read-only folder grant covers reading, not writing`() {
        val repo = repo(permission(notesTree, read = true, write = false))
        assertTrue(repo.hasPersistedPermission(note))
        assertFalse(repo.hasPersistedWritePermission(note))
    }

    @Test
    fun `a grant on another folder does not cover the note`() {
        val repo = repo(permission(otherTree, read = true, write = true))
        assertFalse(repo.hasPersistedPermission(note))
        assertFalse(repo.hasPersistedWritePermission(note))
    }

    @Test
    fun `the same tree ID under another provider does not cover the note`() {
        val foreign = DocumentsContract.buildTreeDocumentUri("other.provider", "primary:Notes")
        val repo = repo(permission(foreign, read = true, write = true))
        assertFalse(repo.hasPersistedPermission(note))
        assertFalse(repo.hasPersistedWritePermission(note))
    }

    @Test
    fun `a plain document URI is still covered only by its own grant`() {
        val plain = DocumentsContract.buildDocumentUri(authority, "primary:Notes/index.md")
        assertFalse(repo(permission(notesTree, read = true, write = true)).hasPersistedWritePermission(plain))
        assertTrue(repo(permission(plain, read = true, write = true)).hasPersistedWritePermission(plain))
    }
}
