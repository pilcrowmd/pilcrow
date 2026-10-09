// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.di.DefaultAppContainer
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * **NEW-37a.** `enableEdgeToEdge` with a fixed light/dark [androidx.activity.SystemBarStyle] turns
 * the 3-button nav-bar contrast scrim off, which is what Play 1.0.11 showed. NEW-37 put it back on
 * (`isNavigationBarContrastEnforced = true`), and that drew a light band over the open TOC
 * drawer's dim scrim in Light theme.
 *
 * The platform default is TRUE, so `false` can only be observed after `MainScreen`'s
 * LaunchedEffect has run `enableEdgeToEdge`; waiting for it is the barrier. With the re-enable
 * restored, the effect writes `false` and then `true` in one synchronous block, the polling here
 * never sees `false`, and the wait times out.
 *
 * Runs at the default Robolectric SDK (the app's targetSdk, 36), so the API 29+ window flag exists.
 * The theme is the ViewModel's default (Dark); the removed line was not theme-conditional.
 */
@RunWith(RobolectricTestRunner::class)
class SystemBarContrastTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<PilcrowApplication>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val storage: StorageManager = LocalStorageManager(
            app,
            PreferenceDataStoreFactory.create(scope = storageScope) {
                tempFolder.newFile("new37a_test.preferences_pb")
            },
        )
        val real = DefaultAppContainer(app)
        app.container = object : AppContainer by real {
            override val storageManager: StorageManager = storage
        }
    }

    @After
    fun tearDown() = storageScope.cancel()

    @Test
    fun `the nav-bar contrast scrim is left off once edge-to-edge has been set up`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // Compose's recomposer runs on Choreographer frames, so advance the clock one frame per
        // poll; a bare idle() would never let the LaunchedEffect run.
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (activity.window.isNavigationBarContrastEnforced && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            Thread.sleep(POLL_MS)
        }

        assertFalse(
            "the nav-bar contrast scrim is still on: enableEdgeToEdge never ran, or something put it back",
            activity.window.isNavigationBarContrastEnforced,
        )
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
        const val FRAME_MS = 17L
    }
}
