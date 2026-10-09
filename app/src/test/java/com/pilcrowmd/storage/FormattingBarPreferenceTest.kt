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

/** M-217: the editor's formatting bar is ON until the writer turns it off, and the choice persists. */
@RunWith(RobolectricTestRunner::class)
class FormattingBarPreferenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("formatting_bar_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the bar is ON by default`() = runBlocking {
        assertTrue(storage.formattingBarEnabled.first())
    }

    @Test
    fun `the setting persists both ways`() = runBlocking {
        storage.setFormattingBarEnabled(false)
        assertFalse(storage.formattingBarEnabled.first())
        storage.setFormattingBarEnabled(true)
        assertTrue(storage.formattingBarEnabled.first())
    }
}
