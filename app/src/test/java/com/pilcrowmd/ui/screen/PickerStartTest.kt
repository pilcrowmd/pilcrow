// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * S8-A75: the "Allow editing" picker opens in the folder of the open file, also for the plain
 * MediaStore URI Samsung My Files sends (S8-A53). Anything the picker cannot start at gives null.
 */
@RunWith(RobolectricTestRunner::class)
class PickerStartTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mediaFile = Uri.parse("content://media/external/file/123")

    @Before
    fun setUp() {
        FakeMediaProvider.answer = null
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    @Test
    fun `primary storage folder is named by the external storage provider`() {
        assertEquals(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FS8-slot"),
            externalStorageFolder("external_primary", "Download/S8-slot/"),
        )
    }

    @Test
    fun `an SD card volume name becomes the upper case root id`() {
        assertEquals("AB12-CD34:Notes", DocumentsContract.getDocumentId(externalStorageFolder("ab12-cd34", "Notes/")))
    }

    @Test
    fun `a MediaStore file starts the picker in its folder`() {
        FakeMediaProvider.answer = folderCursor("external_primary", "Download/S8-slot/")

        assertEquals(
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FS8-slot"),
            pickerStartFor(context, mediaFile),
        )
    }

    @Test
    fun `a MediaStore file with no row gives null`() {
        FakeMediaProvider.answer = MatrixCursor(COLUMNS)

        assertNull(pickerStartFor(context, mediaFile))
    }

    @Test
    fun `a FileProvider file gives null`() {
        assertNull(pickerStartFor(context, Uri.parse("content://com.example.fileprovider/x/y.md")))
    }

    private fun folderCursor(volume: String, relativePath: String) = MatrixCursor(COLUMNS).apply {
        addRow(arrayOf(volume, relativePath))
    }

    private companion object {
        val COLUMNS = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
    }

    /** Stands in for MediaStore: every query gets [answer]. */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = answer
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

        companion object {
            var answer: Cursor? = null
        }
    }
}
