// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.ui.screen.AllowEditingDialog
import com.pilcrowmd.ui.screen.EditChoiceDialog
import com.pilcrowmd.ui.screen.NotSameFileDialog
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens for the three S8-A55 "Allow editing" dialogs: the choice Save opens for a file another app
 * opened read-only, the note before the picker, and "That isn't the same file". Dark only (v1), on
 * the S24+ in portrait at OS font size 1.0. A record of the look, not the guard: the logic is in
 * `MarkdownViewModelAllowEditingTest`. A re-record must be looked at (M-56).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = S24_PLUS, fontScale = 1.0f)
class AllowEditingDialogsScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Same tolerance as the other dialog goldens.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    fun editChoice() {
        capture("edit_choice_dark") { EditChoiceDialog(onAllowEditing = {}, onSaveCopy = {}, onDismiss = {}) }
    }

    @Test
    fun allowEditing() {
        capture("allow_editing_dark") { AllowEditingDialog(onContinue = {}, onDismiss = {}) }
    }

    @Test
    fun notSameFile() {
        capture("not_same_file_dark") { NotSameFileDialog(onPickAgain = {}, onDismiss = {}) }
    }

    private fun capture(name: String, dialog: @Composable () -> Unit) {
        setThemed(dialog)
        composeRule.onNode(isDialog()).captureRoboImage(
            filePath = "src/test/screenshots/$name.png",
            roborazziOptions = roborazziOptions,
        )
    }

    private fun setThemed(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme, content = content)
        }
        drainMainLooper()
    }

    private fun drainMainLooper() {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var guard = 0
        do {
            composeRule.waitForIdle()
            mainLooper.idle()
            check(guard++ < MAX_DRAIN_PASSES) { "Main looper never went idle before capture" }
        } while (!mainLooper.isIdle)
    }

    private companion object {
        const val MAX_DRAIN_PASSES = 50
    }
}

/** The S24+ in portrait: `adb shell wm size` 1080x2340, `wm density` 450. */
private const val S24_PLUS = "w384dp-h832dp-night-450dpi"
