// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.AmoledColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome rating card (M-177) and update note (M-174) in AMOLED (M-92). [WelcomeScreenshotTest]
 * runs Dark and Light only; adding AMOLED there would add an AMOLED golden for every welcome state,
 * so the two cards get theirs here, with the same portrait viewport, recents and tolerance. No
 * landscape pair: there the cards sit below the fold (accepted, M-177), so a capture would not show them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class WelcomePromptsAmoledScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The same cross-OS tolerance as WelcomeScreenshotTest, for the same brand block.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun goldenRatingCardWithRecents() = capture("rating_recents", showRatingCard = true)

    @Test
    fun goldenUpdateNoteWithRecents() = capture("update_note_recents", showUpdateNote = true)

    private fun capture(suffix: String, showRatingCard: Boolean = false, showUpdateNote: Boolean = false) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(AmoledColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides AmoledColorScheme) {
                    WelcomeScreen(
                        recentFiles = recents,
                        showRatingCard = showRatingCard,
                        showUpdateNote = showUpdateNote,
                    )
                }
            }
        }
        val mainLooper = shadowOf(Looper.getMainLooper())
        var guard = 0
        do {
            composeRule.waitForIdle()
            mainLooper.idle()
            check(guard++ < MAX_DRAIN_PASSES) { "Main looper never went idle before capture" }
        } while (!mainLooper.isIdle)
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/welcome_amoled_$suffix.png",
            roborazziOptions = roborazziOptions,
        )
    }

    private companion object {
        const val MAX_DRAIN_PASSES = 50

        // WelcomeScreenshotTest's eight entries (the storage cap), so the pictures compare one to one.
        val recents = listOf(
            RecentFileUi(Uri.parse("content://uat/notes.md"), "notes.md", 5_000L, available = true),
            RecentFileUi(Uri.parse("content://uat/spec.md"), "spec.md", 4_000L, available = true),
            RecentFileUi(Uri.parse("content://uat/README.md"), "README.md", 3_000L, available = true),
            RecentFileUi(Uri.parse("content://uat/journal.md"), "journal.md", 2_000L, available = true),
            RecentFileUi(Uri.parse("content://uat/todo.md"), "todo.md", 1_000L, available = false),
            RecentFileUi(Uri.parse("content://uat/a.md"), "a.md", 900L, available = true),
            RecentFileUi(Uri.parse("content://uat/b.md"), "b.md", 800L, available = true),
            RecentFileUi(Uri.parse("content://uat/c.md"), "c.md", 700L, available = true),
        )
    }
}
