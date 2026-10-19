// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * M-92: the AMOLED choice survives a restart through the existing storage abstraction — the theme is
 * stored by name, so a new [ThemeMode] needs no new mechanism. The "restart" is a second
 * [LocalStorageManager] over the same file, opened after the first store's scope has ended, so the
 * value can only come from disk.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeModePersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun open(file: File): LocalStorageManager {
        val context: Context = ApplicationProvider.getApplicationContext()
        val scope = CoroutineScope(Dispatchers.IO + Job()).also { scopes += it }
        return LocalStorageManager(context, PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `AMOLED survives a restart`() = runBlocking {
        val file = tempFolder.newFile("theme_mode_test.preferences_pb")
        val first = open(file)
        first.setThemeMode(ThemeMode.AMOLED)
        assertEquals(ThemeMode.AMOLED, first.themeMode.first())

        scopes.single().coroutineContext[Job]!!.cancelAndJoin()

        assertEquals(ThemeMode.AMOLED, open(file).themeMode.first())
    }
}
