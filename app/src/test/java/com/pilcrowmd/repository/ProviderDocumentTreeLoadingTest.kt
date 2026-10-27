// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M-162b: a cloud provider that answers a folder listing while still loading it (EXTRA_LOADING).
 * The provider here hands out prepared cursors in order, so each test fixes exactly what every
 * query sees.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderDocumentTreeLoadingTest {

    class QueuedProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cursor = answers.removeAt(0)(projection!!)
            served += cursor
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0

        companion object {
            val answers = CopyOnWriteArrayList<(Array<out String>) -> Cursor>()
            val served = CopyOnWriteArrayList<Cursor>()
        }
    }

    /** A listing of [names]; still loading when [loading], and then signalling a change at once if [signals]. */
    private fun answer(vararg names: String, loading: Boolean = false, signals: Boolean = false) =
        { projection: Array<out String> ->
            val cursor = object : MatrixCursor(projection) {
                override fun registerContentObserver(observer: ContentObserver) {
                    super.registerContentObserver(observer)
                    // The provider's "more results arrived" notification, after the reader started waiting.
                    if (signals) observer.dispatchChange(false, null)
                }
            }
            names.forEach { name -> cursor.addRow(projection.map { column -> valueFor(column, name) }) }
            if (loading) cursor.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            cursor
        }

    private fun valueFor(column: String, name: String): Any? = when (column) {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root/$name"
        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
        DocumentsContract.Document.COLUMN_MIME_TYPE -> "text/markdown"
        else -> null
    }

    private val treeUri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before
    fun setUp() {
        QueuedProvider.answers.clear()
        QueuedProvider.served.clear()
        Robolectric.setupContentProvider(QueuedProvider::class.java, AUTHORITY)
    }

    private fun tree(waitMs: Long): ProviderDocumentTree {
        val context: Context = ApplicationProvider.getApplicationContext()
        return ProviderDocumentTree(context.contentResolver, context.packageManager, loadingWaitMs = waitMs)
    }

    private fun names(children: List<TreeChild>?) = children?.map { it.name }

    @Test
    fun `a finished listing is used as it is`() {
        QueuedProvider.answers += answer("a.md", "b.md")

        val children = tree(waitMs = 5_000).childrenOrNull(treeUri, "root", waitWhileLoading = true)

        assertEquals(listOf("a.md", "b.md"), names(children))
        assertEquals(1, QueuedProvider.served.size)
    }

    @Test
    fun `a loading listing is asked again once the provider signals a change`() {
        QueuedProvider.answers += answer("a.md", loading = true, signals = true)
        QueuedProvider.answers += answer("a.md", "b.md", "c.md")
        val started = System.currentTimeMillis()

        val children = tree(waitMs = 5_000).childrenOrNull(treeUri, "root", waitWhileLoading = true)

        assertEquals(listOf("a.md", "b.md", "c.md"), names(children))
        assertTrue("woken by the signal, not the timeout", System.currentTimeMillis() - started < 4_000)
        assertEquals(2, QueuedProvider.served.size)
        assertTrue("every cursor closed", QueuedProvider.served.all { it.isClosed })
    }

    @Test
    fun `a provider that stays loading is waited for twice, then what it has is used`() {
        repeat(3) { i -> QueuedProvider.answers += answer(*Array(i + 1) { "n$it.md" }, loading = true) }

        val children = tree(waitMs = 50).childrenOrNull(treeUri, "root", waitWhileLoading = true)

        assertEquals(listOf("n0.md", "n1.md", "n2.md"), names(children))
        assertEquals(3, QueuedProvider.served.size)
        assertTrue("every cursor closed", QueuedProvider.served.all { it.isClosed })
    }

    @Test
    fun `picture lookup takes the first answer, loading or not`() {
        QueuedProvider.answers += answer("a.md", loading = true, signals = true)
        QueuedProvider.answers += answer("a.md", "b.md")
        val started = System.currentTimeMillis()

        val children = tree(waitMs = 5_000).children(treeUri, "root")

        assertEquals(listOf("a.md"), names(children))
        assertEquals(1, QueuedProvider.served.size)
        assertTrue("no wait", System.currentTimeMillis() - started < 4_000)
        assertTrue("the cursor closed", QueuedProvider.served.single().isClosed)
    }

    private companion object {
        const val AUTHORITY = "com.example.loading.documents"
    }
}
