// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Preview scroll memory now persists a (first-visible-item index, intra-item pixel offset)
 * anchor instead of a fragile absolute pixel offset. These tests lock the DataStore round-trip
 * and per-URI isolation.
 */
@RunWith(RobolectricTestRunner::class)
class ScrollAnchorPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("scroll_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unknownUri_defaultsToTop() = runBlocking {
        val anchor = storage.getScrollPosition(Uri.parse("content://doc/never-seen"))
        assertEquals(ScrollAnchor(0, 0), anchor)
    }

    @Test
    fun anchor_persistsIndexAndOffset() = runBlocking {
        val uri = Uri.parse("content://doc/a.md")
        storage.saveScrollPosition(uri, ScrollAnchor(index = 7, offset = -42))

        assertEquals(ScrollAnchor(7, -42), storage.getScrollPosition(uri))
    }

    @Test
    fun anchors_areIsolatedPerUri() = runBlocking {
        val a = Uri.parse("content://doc/a.md")
        val b = Uri.parse("content://doc/b.md")
        storage.saveScrollPosition(a, ScrollAnchor(3, 10))
        storage.saveScrollPosition(b, ScrollAnchor(99, 0))

        assertEquals(ScrollAnchor(3, 10), storage.getScrollPosition(a))
        assertEquals(ScrollAnchor(99, 0), storage.getScrollPosition(b))
    }

    @Test
    fun latestSaveWins() = runBlocking {
        val uri = Uri.parse("content://doc/a.md")
        storage.saveScrollPosition(uri, ScrollAnchor(1, 5))
        storage.saveScrollPosition(uri, ScrollAnchor(2, 6))

        assertEquals(ScrollAnchor(2, 6), storage.getScrollPosition(uri))
    }
}
