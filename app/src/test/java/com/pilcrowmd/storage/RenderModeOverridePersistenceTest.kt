// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.RenderMode
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
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
 * Per-URI render-mode override persistence: the DataStore round-trip, per-URI isolation,
 * and null-as-clear — absence means "use the extension default".
 */
@RunWith(RobolectricTestRunner::class)
class RenderModeOverridePersistenceTest {

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
            tempFolder.newFile("render_mode_test.preferences_pb")
        }
        storage = LocalStorageManager(context, dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun unknownUri_hasNoOverride() = runBlocking {
        assertNull(storage.getRenderModeOverride(Uri.parse("content://doc/never-seen.txt")))
    }

    @Test
    fun override_roundTripsBothModes() = runBlocking {
        val uri = Uri.parse("content://doc/notes.txt")
        storage.setRenderModeOverride(uri, RenderMode.MARKDOWN)
        assertEquals(RenderMode.MARKDOWN, storage.getRenderModeOverride(uri))
        storage.setRenderModeOverride(uri, RenderMode.PLAIN)
        assertEquals(RenderMode.PLAIN, storage.getRenderModeOverride(uri))
    }

    @Test
    fun overrides_areIsolatedPerUri() = runBlocking {
        val a = Uri.parse("content://doc/a.txt")
        val b = Uri.parse("content://doc/b.txt")
        storage.setRenderModeOverride(a, RenderMode.MARKDOWN)
        storage.setRenderModeOverride(b, RenderMode.PLAIN)

        assertEquals(RenderMode.MARKDOWN, storage.getRenderModeOverride(a))
        assertEquals(RenderMode.PLAIN, storage.getRenderModeOverride(b))
    }

    @Test
    fun nullClearsTheOverride() = runBlocking {
        val uri = Uri.parse("content://doc/a.txt")
        storage.setRenderModeOverride(uri, RenderMode.MARKDOWN)
        storage.setRenderModeOverride(uri, null)
        assertNull(storage.getRenderModeOverride(uri))
    }

    @Test
    fun coexistsWithScrollAnchors() = runBlocking {
        // Same DataStore, different keys — writes must not clobber each other.
        val uri = Uri.parse("content://doc/a.txt")
        storage.saveScrollPosition(uri, ScrollAnchor(4, -7))
        storage.setRenderModeOverride(uri, RenderMode.MARKDOWN)

        assertEquals(ScrollAnchor(4, -7), storage.getScrollPosition(uri))
        assertEquals(RenderMode.MARKDOWN, storage.getRenderModeOverride(uri))
    }
}
