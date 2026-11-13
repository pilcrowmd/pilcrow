// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.pilcrowmd.domain.model.SearchMatches
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S8-A94 (large-documents spec, Rule 4): a search stopped at its cap shows the total as "10,000+". */
@RunWith(RobolectricTestRunner::class)
class SearchBarCountTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(count: Int, capped: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                SearchBar(query = "e", matchCount = count, matchCountCapped = capped, currentIndex = 2)
            }
        }
    }

    @Test
    fun `a capped search reads 10,000 plus`() {
        show(SearchMatches.MAX_MATCHES, capped = true)
        compose.onNodeWithText("3/10,000+").assertIsDisplayed()
    }

    @Test
    fun `a search under the cap reads its count as before`() {
        show(SearchMatches.MAX_MATCHES, capped = false)
        compose.onNodeWithText("3/10000").assertIsDisplayed()
    }
}
