// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.MainActivity
import com.pilcrowmd.PilcrowApplication
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.di.DefaultAppContainer
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * **NEW-37b**, the `MainScreen` column's share (`DisplayCutoutPaddingTest` covers the standalone
 * screens). The column is the only padding the welcome screen has, so it is measured through the
 * welcome screen's "Open MD File" button, whose left edge is a fixed 32 px with no cutout.
 *
 * Same fixture rules as `DisplayCutoutPaddingTest`: a LEFT cutout of 136 px and no system-bar
 * insets, dispatched to the Compose host view, with the BEFORE measurement as the control. The
 * real `MainActivity` is launched with a temp-file storage, as `SystemBarContrastTest` does.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenDisplayCutoutPaddingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var storageScope: CoroutineScope

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<PilcrowApplication>()
        storageScope = CoroutineScope(Dispatchers.IO + Job())
        val storage: StorageManager = LocalStorageManager(
            app,
            PreferenceDataStoreFactory.create(scope = storageScope) {
                tempFolder.newFile("new37b_test.preferences_pb")
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
    fun theMainScreenColumnKeepsTheWelcomeScreenOutOfTheCutout() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        composeRule.waitForIdle()
        val before = leftEdgeOfOpenButton()
        assertTrue("control: with no cutout the button starts inside the band, at ${before}px", before < CUTOUT_PX)

        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
            .build()
        val host = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        composeRule.waitForIdle()

        val after = leftEdgeOfOpenButton()
        assertTrue(
            "the welcome content must start at or beyond the ${CUTOUT_PX}px cutout band, but starts at ${after}px",
            after >= CUTOUT_PX,
        )
    }

    private fun leftEdgeOfOpenButton(): Float =
        composeRule.onNodeWithText("Open MD File").fetchSemanticsNode().positionInRoot.x

    private companion object {
        const val CUTOUT_PX = 136
    }
}
