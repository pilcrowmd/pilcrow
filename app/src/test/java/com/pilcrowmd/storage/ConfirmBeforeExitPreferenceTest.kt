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

/** Default is ON: double-Back to leave from home unless the reader turns confirmation off. */
@RunWith(RobolectricTestRunner::class)
class ConfirmBeforeExitPreferenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("confirm_before_exit_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `confirm before exit is ON by default`() = runBlocking {
        assertTrue(storage.confirmBeforeExit.first())
    }

    @Test
    fun `the setting persists both ways`() = runBlocking {
        storage.setConfirmBeforeExit(false)
        assertFalse(storage.confirmBeforeExit.first())
        storage.setConfirmBeforeExit(true)
        assertTrue(storage.confirmBeforeExit.first())
    }
}
