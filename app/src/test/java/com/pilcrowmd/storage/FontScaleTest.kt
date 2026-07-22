// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for fontScale persistence and range validation.
 * Tests DataStore I/O, clamping behavior, and default values.
 */
@RunWith(RobolectricTestRunner::class)
class FontScaleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: LocalStorageManager
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Test isolation: build a fresh DataStore backed by a unique temp file per test, so no
        // state (or the process-singleton's in-memory cache) can leak across methods. This is why
        // LocalStorageManager takes an injectable DataStore — production still uses the singleton.
        scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tempFolder.newFile("fontscale_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun fontScale_default_is_1_0() = runBlocking {
        // When no fontScale key is in DataStore, reading should return 1.0f (no scaling)
        val scale = storage.fontScale.first()
        assertEquals(1.0f, scale)
    }

    @Test
    fun fontScale_persists_and_reads_back() = runBlocking {
        // Save a specific value and verify it reads back correctly
        val savedValue = 1.3f
        storage.saveFontScale(savedValue)

        val retrieved = storage.fontScale.first()
        assertEquals(savedValue, retrieved)
    }

    @Test
    fun fontScale_clamped_to_lower_bound() = runBlocking {
        // Values below 0.85 should be clamped to 0.85
        storage.saveFontScale(0.5f)

        val retrieved = storage.fontScale.first()
        assertEquals(0.85f, retrieved)
    }

    @Test
    fun fontScale_clamped_to_upper_bound() = runBlocking {
        // Values above 1.6 should be clamped to 1.6
        storage.saveFontScale(2.0f)

        val retrieved = storage.fontScale.first()
        assertEquals(1.6f, retrieved)
    }

    @Test
    fun fontScale_valid_range_preserved() = runBlocking {
        // Values within [0.85, 1.6] should be preserved exactly
        val testValues = listOf(0.85f, 0.9f, 1.0f, 1.2f, 1.5f, 1.6f)

        for (value in testValues) {
            storage.saveFontScale(value)
            val retrieved = storage.fontScale.first()
            assertEquals("Value $value should be preserved", value, retrieved)
        }
    }

    @Test
    fun fontScale_edge_case_zero_clamped() = runBlocking {
        // Zero should clamp to 0.85
        storage.saveFontScale(0f)

        val retrieved = storage.fontScale.first()
        assertEquals(0.85f, retrieved)
    }

    @Test
    fun fontScale_edge_case_negative_clamped() = runBlocking {
        // Negative values should clamp to 0.85
        storage.saveFontScale(-1f)

        val retrieved = storage.fontScale.first()
        assertEquals(0.85f, retrieved)
    }

    @Test
    fun fontScale_multiple_saves_latest_wins() = runBlocking {
        // Multiple saves should result in the latest value being persisted
        storage.saveFontScale(0.9f)
        storage.saveFontScale(1.2f)
        storage.saveFontScale(1.5f)

        val retrieved = storage.fontScale.first()
        assertEquals(1.5f, retrieved)
    }
}
