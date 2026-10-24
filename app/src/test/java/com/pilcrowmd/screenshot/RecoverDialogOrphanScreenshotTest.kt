// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.ui.screen.StrandedSlotsDialog
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.OrphanDraft
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Golden of the "Recover unsaved files" dialog with one stranded slot and one orphan draft (the
 * unsaved edits of a file that can no longer be opened), which also shows the time line of the draft.
 * The time zone is pinned: the draft's time is formatted in the zone of the machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoverDialogOrphanScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var zone: TimeZone? = null

    @Before
    fun pinTimeZone() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        zone?.let { TimeZone.setDefault(it) }
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp-night-450dpi", fontScale = 1.0f)
    fun slotAndOrphanDraft() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                StrandedSlotsDialog(
                    slots = listOf(StrandedSlot("0", Uri.parse("content://uat/0"), "meeting-notes.md")),
                    onRescue = {},
                    onDiscard = {},
                    onDismiss = {},
                    orphans = listOf(OrphanDraft(Uri.parse("content://uat/1"), "quarterly-report.md", SAVED_AT)),
                )
            }
        }
        val mainLooper = shadowOf(Looper.getMainLooper())
        var guard = 0
        do {
            composeRule.waitForIdle()
            mainLooper.idle()
            check(guard++ < MAX_DRAIN_PASSES) { "Main looper never went idle before capture" }
        } while (!mainLooper.isIdle)
        composeRule.onNode(isDialog()).captureRoboImage(
            filePath = "src/test/screenshots/recover_dialog_slot_and_orphan_draft.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
            ),
        )
    }

    private companion object {
        const val MAX_DRAIN_PASSES = 50

        /** 14 November 2023, 22:13 UTC: not today, so the date is shown as well as the time. */
        const val SAVED_AT = 1_700_000_000_000L
    }
}
