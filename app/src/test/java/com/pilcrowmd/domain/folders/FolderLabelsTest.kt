// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Test

/** P15: same-name folders show where each one is. */
class FolderLabelsTest {

    @Test
    fun `a unique name gets no suffix`() {
        val labels = FolderLabels.labels(listOf(FolderIdentity("Notes", "Work"), FolderIdentity("Journal", "Home")))
        assertEquals(listOf(null, null), labels)
    }

    @Test
    fun `two folders with the same name show their parent hints`() {
        val labels = FolderLabels.labels(
            listOf(FolderIdentity("Notes", "Work"), FolderIdentity("Journal", "Home"), FolderIdentity("Notes", "Home")),
        )
        assertEquals(listOf("Work", null, "Home"), labels)
    }

    @Test
    fun `same name ignores case and Unicode form`() {
        val labels = FolderLabels.labels(
            listOf(FolderIdentity("Música", "Work"), FolderIdentity("MÚSICA", "Drive")),
        )
        assertEquals(listOf("Work", "Drive"), labels)
    }

    @Test
    fun `a folder inside another is named after its parent`() {
        assertEquals(ParentHint.Named("Work"), FolderLabels.parentHintFromDocumentId(STORAGE, "primary:Work/Notes"))
        assertEquals(ParentHint.Named("B"), FolderLabels.parentHintFromDocumentId(STORAGE, "primary:A/B/Notes"))
        assertEquals(ParentHint.Named("Work"), FolderLabels.parentHintFromDocumentId(STORAGE, "1A2B-3C4D:Work/Notes/"))
    }

    @Test
    fun `a folder at the top of internal storage`() {
        assertEquals(ParentHint.InternalStorage, FolderLabels.parentHintFromDocumentId(STORAGE, "primary:Notes"))
    }

    @Test
    fun `a folder at the top of an SD card`() {
        assertEquals(ParentHint.SdCard, FolderLabels.parentHintFromDocumentId(STORAGE, "1234-ABCD:Notes"))
    }

    @Test
    fun `unknown for the volume itself, an ID without a volume, and other providers`() {
        assertEquals(ParentHint.Unknown, FolderLabels.parentHintFromDocumentId(STORAGE, "primary:"))
        assertEquals(ParentHint.Unknown, FolderLabels.parentHintFromDocumentId(STORAGE, "Notes"))
        assertEquals(
            ParentHint.Unknown,
            FolderLabels.parentHintFromDocumentId("com.google.android.apps.docs.storage", "primary:Work/Notes"),
        )
        assertEquals(ParentHint.Unknown, FolderLabels.parentHintFromDocumentId(null, "primary:Work/Notes"))
    }

    private companion object {
        const val STORAGE = "com.android.externalstorage.documents"
    }
}
