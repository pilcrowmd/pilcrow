// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.ui.components.HeadingsDrawer
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.mdColors
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-171 — in the light theme the TOC drawer must be drawn in the LIGHT scheme.
 *
 * [HeadingsDrawer] paints its background and text from `mdColors()`, so the drawer is exactly as
 * light or dark as the scheme `mdColors()` returns inside `drawerContent`. The defect was not a
 * wrong colour value but WHERE the scheme was provided: a provider inside the drawer's content slot
 * does not reach `drawerContent`, which then read `LocalMDColors`' Dark default. This records the
 * scheme the drawer content actually reads, from the same composition that draws it.
 *
 * Seen failing with the provider moved back around the content slot only — main's structure: the
 * drawer read DarkColorScheme.
 */
@RunWith(RobolectricTestRunner::class)
class ThemedNavigationDrawerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawerContentReadsTheLightSchemeInLightTheme() {
        var drawerScheme: PilcrowColorScheme? = null
        composeRule.setContent {
            ThemedNavigationDrawer(
                colorScheme = LightColorScheme,
                drawerState = rememberDrawerState(DrawerValue.Open),
                gesturesEnabled = true,
                drawerContent = {
                    drawerScheme = mdColors()
                    HeadingsDrawer(headings = listOf(HeadingNode(1, "Only heading", 0)))
                },
            ) {
                Box {}
            }
        }
        composeRule.waitForIdle()

        assertSame("the drawer content must read the active (Light) scheme", LightColorScheme, drawerScheme)
    }
}
