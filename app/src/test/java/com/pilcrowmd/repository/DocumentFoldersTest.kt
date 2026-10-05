// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import com.pilcrowmd.repository.DocumentFolders.DOWNLOADS
import com.pilcrowmd.repository.DocumentFolders.EXTERNAL_STORAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-93: which folder a note is in, and whether Android's folder picker would let the user pick it. */
class DocumentFoldersTest {

    private fun folder(id: String, authority: String = EXTERNAL_STORAGE, sdk: Int = 34) =
        DocumentFolders.folderOf(authority, id, sdk)

    @Test
    fun `a note in a subfolder is in a folder the picker allows`() {
        val f = folder("primary:Documents/Notes/trip.md")!!
        assertEquals("$EXTERNAL_STORAGE|primary:Documents/Notes", f.key)
        assertFalse(f.ungrantable)
    }

    @Test
    fun `a note straight in Download is in a folder the picker refuses`() {
        assertTrue(folder("primary:Download/trip.md")!!.ungrantable)
        assertTrue(folder("primary:download/trip.md")!!.ungrantable)
    }

    @Test
    fun `a note in a subfolder of Download is fine`() {
        assertFalse(folder("primary:Download/Notes/trip.md")!!.ungrantable)
    }

    @Test
    fun `the top of internal storage and of an SD card are refused`() {
        assertTrue(folder("primary:trip.md")!!.ungrantable)
        assertTrue(folder("1A2B-3C4D:trip.md")!!.ungrantable)
    }

    @Test
    fun `Android and everything under it is refused`() {
        assertTrue(folder("primary:Android/data/x/trip.md")!!.ungrantable)
    }

    @Test
    fun `before Android 11 the picker allowed every folder`() {
        assertFalse(folder("primary:Download/trip.md", sdk = 29)!!.ungrantable)
    }

    @Test
    fun `a raw Downloads path straight in Download is refused, and its subfolders are unknown`() {
        val f = folder("raw:/storage/emulated/0/Download/trip.md", authority = DOWNLOADS)!!
        assertTrue(f.ungrantable)
        assertNull(folder("raw:/storage/emulated/0/Download/Notes/trip.md", authority = DOWNLOADS))
    }

    @Test
    fun `IDs that name no folder are unknown`() {
        assertNull(folder("msf:42", authority = DOWNLOADS))
        assertNull(folder("primary:Notes/trip.md", authority = "com.example.fileprovider"))
        assertNull(folder("no-colon"))
    }
}
