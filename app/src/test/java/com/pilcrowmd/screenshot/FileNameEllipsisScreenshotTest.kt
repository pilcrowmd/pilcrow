// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.ui.components.RecentRow
import com.pilcrowmd.ui.screen.StrandedSlotsDialog
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-221 goldens: two long names that differ only at the end, and one short name, in the recent-files
 * list and in the "Recover unsaved files" dialog, at OS font size 1.0 and 2.0, in both themes. The
 * long names show their start, `…` and their last 8 characters; the short name is shown whole.
 *
 * A record of the look, not the guard: `FileNameMiddleEllipsisTest` checks the laid-out string. The
 * list rows are drawn at the width they have on the S24+ (the welcome column's 32 dp padding a side).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileNameEllipsisScreenshotTest(private val themeMode: ThemeMode) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Same tolerance as the welcome suite, whose text-on-surface content this matches.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.05f),
    )

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 1.0f)
    fun recents100() = captureRecents("100")

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 2.0f)
    fun recents200() = captureRecents("200")

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 1.0f)
    fun recover100() = captureRecover("100")

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 2.0f)
    fun recover200() = captureRecover("200")

    private val colors get() = if (themeMode == ThemeMode.DARK) DarkColorScheme else LightColorScheme

    private val theme get() = if (themeMode == ThemeMode.DARK) "dark" else "light"

    private fun captureRecents(pct: String) {
        setThemed {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.primaryBackground)
                    .padding(horizontal = 32.dp, vertical = 12.dp)
                    .testTag(SCENE),
            ) {
                NAMES.forEachIndexed { i, name ->
                    RecentRow(
                        r = RecentFileUi(Uri.parse("content://uat/$i"), name, 3_000L - i, available = true),
                        onOpenRecent = {},
                        onRemoveRecent = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag(SCENE).captureRoboImage(
            filePath = "src/test/screenshots/file_name_recents_${pct}_$theme.png",
            roborazziOptions = roborazziOptions,
        )
    }

    private fun captureRecover(pct: String) {
        setThemed {
            StrandedSlotsDialog(
                slots = NAMES.mapIndexed { i, name -> StrandedSlot("$i", Uri.parse("content://uat/$i"), name) },
                onRescue = {},
                onDiscard = {},
                onDismiss = {},
            )
        }
        composeRule.onNode(isDialog()).captureRoboImage(
            filePath = "src/test/screenshots/file_name_recover_${pct}_$theme.png",
            roborazziOptions = roborazziOptions,
        )
    }

    private fun setThemed(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides colors, content = content)
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

    companion object {
        private const val MAX_DRAIN_PASSES = 50
        private const val SCENE = "file-name-scene"

        /** The S24+ in portrait: `adb shell wm size` 1080x2340, `wm density` 450. */
        private const val S24_PLUS = "w384dp-h832dp-night-450dpi"

        private val NAMES = listOf(
            "quarterly-report-final-review-with-comments-draft-v2.md",
            "quarterly-report-final-review-with-comments-draft-v3.md",
            "notes.md",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<ThemeMode> = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    }
}
