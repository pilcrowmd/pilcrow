// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-162a: each picker contract adds `EXTRA_INITIAL_URI` from its start lambda when there is a start,
 * leaves it out when there is none, and keeps everything the base contract already puts on the intent.
 */
@RunWith(RobolectricTestRunner::class)
class PickerContractsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val start = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ANotes%2Fa.md")
    private val mimes = arrayOf("text/markdown", "text/plain", "text/*")

    private fun Intent.initialUri(): Uri? =
        IntentCompat.getParcelableExtra(this, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)

    @Test
    fun openDocumentStartsAtTheGivenUriAndKeepsItsMimeTypes() {
        val intent = OpenDocumentFrom { start }.createIntent(context, mimes)
        assertEquals(start, intent.initialUri())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertArrayEquals(mimes, intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun openDocumentWithNoStartHasNoInitialUri() {
        val intent = OpenDocumentFrom { null }.createIntent(context, mimes)
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
    }

    @Test
    fun createDocumentStartsAtTheGivenUriAndKeepsTypeAndTitle() {
        val intent = CreateDocumentFrom("text/markdown") { start }.createIntent(context, "Copy of a.md")
        assertEquals(start, intent.initialUri())
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals("Copy of a.md", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun createDocumentWithNoStartHasNoInitialUri() {
        val intent = CreateDocumentFrom("application/pdf") { null }.createIntent(context, "a.pdf")
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
        assertEquals("application/pdf", intent.type)
        assertEquals("a.pdf", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun openAnyDocumentStartsAtTheGivenUriAndStillSetsNoMimeTypes() {
        val intent = OpenAnyDocument { start }.createIntent(context, Unit)
        assertEquals(start, intent.initialUri())
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        // The Samsung workaround: no EXTRA_MIME_TYPES at all, so every file stays selectable.
        assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test
    fun openAnyDocumentWithNoStartHasNoInitialUri() {
        val intent = OpenAnyDocument { null }.createIntent(context, Unit)
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
        assertEquals("*/*", intent.type)
    }

    @Test
    fun theStartIsReadWhenThePickerLaunchesNotWhenTheContractIsMade() {
        var current: Uri? = null
        val contract = CreateDocumentFrom("text/plain") { current }
        current = start
        assertEquals(start, contract.createIntent(context, "a.txt").initialUri())
    }
}
