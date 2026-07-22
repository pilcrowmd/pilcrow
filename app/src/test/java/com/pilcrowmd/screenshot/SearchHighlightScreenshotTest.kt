// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.components.MarkdownPreview
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
 * Visual-regression golden for the search FOCUS highlight. A paragraph with two "free"
 * matches is rendered with the 2nd focused (currentMatchIndex = 1): the focused occurrence must paint
 * in `searchHighlightFocused` and the other in `searchHighlight` (Safeguard 4 — both from the token
 * layer). This golden is the regression guard that the focused match is visibly distinct, and the one
 * to re-record if the focused token is ever re-tuned for on-device legibility.
 *
 *   Record:  ./gradlew recordRoborazziDebug
 *   Verify:  ./gradlew verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SearchHighlightScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
    )

    @Test
    fun focusedMatchIsDistinctFromOthers() {
        val content = "The free sample and the free trial are both available."
        val searchUseCase = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())
        val matches = searchUseCase.findSearchMatches(content, "free")

        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DarkColorScheme.primaryBackground),
            ) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    MarkdownPreview(
                        content = content,
                        renderer = renderer,
                        searchMatches = matches,
                        currentMatchIndex = 1, // focus the 2nd "free"
                    )
                }
            }
        }

        drainMainLooper()

        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/markdown_search-focus_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    /** Drain Compose + posted RecyclerView/highlight work so the focus span exists at capture. */
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
        private const val MAX_DRAIN_PASSES = 50
    }
}
