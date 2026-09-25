// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-134: the default is part of a public promise (issue #8 — side-scroll stays unless the reader
 * turns wrapping on), so it is pinned here rather than left to whatever the key happens to fall back to.
 */
@RunWith(RobolectricTestRunner::class)
class WrapCodeLinesPreferenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("wrap_code_lines_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `wrapping is OFF by default`() = runBlocking {
        assertFalse(storage.wrapCodeLines.first())
    }

    @Test
    fun `the setting persists both ways`() = runBlocking {
        storage.setWrapCodeLines(true)
        assertTrue(storage.wrapCodeLines.first())
        storage.setWrapCodeLines(false)
        assertFalse(storage.wrapCodeLines.first())
    }
}
