// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The remembered-file pointer on the real DataStore: a clear really removes it. The ViewModel test
 * for save-and-close keeps the pointer in memory (M-211), so this is where the real storage is
 * checked. Each read is a fresh `first()` after an awaited write, so no write is in flight when
 * it subscribes.
 */
@RunWith(RobolectricTestRunner::class)
class LastFileUriPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    private val uri: Uri = Uri.parse("content://test/a.md")

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("last_file_uri_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun clearRemovesTheRememberedFile() = runBlocking {
        storage.saveLastFileUri(uri)
        assertEquals("fixture: the file is remembered", uri, storage.lastFileUri.first())

        storage.clearLastFileUri()
        assertNull("a cleared pointer is gone from storage", storage.lastFileUri.first())
    }
}
