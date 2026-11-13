// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/**
 * S8-A90: only a provider that says the document is not there makes it gone. A provider that fails
 * any other way (offline, dead, refused) says nothing about the file, so the open file stays.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderDocumentTreeAnswerTest {

    class ScriptedProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries.incrementAndGet()
            return reply(projection!!)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0

        companion object {
            @Volatile var reply: (Array<out String>) -> Cursor? = { null }
            val queries = AtomicInteger()
        }
    }

    private val document = DocumentsContract.buildDocumentUri(AUTHORITY, "doc")

    @Before
    fun setUp() {
        ScriptedProvider.queries.set(0)
        Robolectric.setupContentProvider(ScriptedProvider::class.java, AUTHORITY)
    }

    private fun answer(): StatsAnswer {
        val context: Context = ApplicationProvider.getApplicationContext()
        return ProviderDocumentTree(context.contentResolver, context.packageManager).documentAnswer(document)
    }

    @Test
    fun `a row is the document's date and size`() {
        ScriptedProvider.reply = { p -> MatrixCursor(p).apply { addRow(arrayOf<Any?>(2_000L, 12L)) } }
        assertEquals(StatsAnswer.Known(DocumentStats(2_000L, 12L)), answer())
    }

    @Test
    fun `no row is gone`() {
        ScriptedProvider.reply = { p -> MatrixCursor(p) }
        assertEquals(StatsAnswer.Gone, answer())
        assertEquals("one query decides it", 1, ScriptedProvider.queries.get())
    }

    @Test
    fun `file not found is gone`() {
        ScriptedProvider.reply = { throw FileNotFoundException("no such document") }
        assertEquals(StatsAnswer.Gone, answer())
    }

    @Test
    fun `any other failure is no answer, not a delete`() {
        ScriptedProvider.reply = { throw IllegalStateException("provider offline") }
        assertEquals(StatsAnswer.Unknown, answer())
    }

    @Test
    fun `no cursor at all is no answer`() {
        ScriptedProvider.reply = { null }
        assertEquals(StatsAnswer.Unknown, answer())
    }

    private companion object {
        const val AUTHORITY = "com.pilcrowmd.test.answers"
    }
}
