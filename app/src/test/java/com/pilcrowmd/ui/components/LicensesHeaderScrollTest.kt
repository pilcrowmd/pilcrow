// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * S8-A37 — the licence list must stay reachable when the header is tall: a landscape-sized window
 * (800 x 150 dp) at font scale 2.0 gave the pinned header the whole height and the list none.
 * The title, the app notice and the section 7 permission now scroll WITH the rows, so the first
 * licence row is reached by scrolling. The window is shorter than the header alone, so a header
 * outside the scrolling list leaves the list no viewport and the test fails.
 */
@RunWith(RobolectricTestRunner::class)
class LicensesHeaderScrollTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theFirstLicenceRowIsReachableAtFontScale2InALandscapeWindow() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalMDColors provides DarkColorScheme,
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.size(width = 800.dp, height = 150.dp)) {
                    LicensesListView(context = composeRule.activity, onSelectLicense = {}, onClose = {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).assertHeightIsAtLeast(1.dp)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Source Serif 4"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Source Serif 4").assertIsDisplayed()
    }
}
