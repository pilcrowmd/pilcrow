// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-268: the Settings controls outside the switch rows read correctly under TalkBack.
 *
 * - The text-size steppers were two "A" glyphs; TalkBack said "capital A". They are now buttons
 *   named "Smaller text" / "Larger text", with the glyph's own "A" cleared from what is read.
 * - Theme and font choices were bare `clickable`s, so TalkBack could not say which one was chosen.
 *   They are now radio buttons whose Selected state follows the current setting.
 * - The size slider spoke the fraction of its 85–160 % range ("20 percent" at 100 %), not the
 *   percent the readout beside it shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class SettingsAccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var previewScale: Float? = null

    private fun show(
        themeMode: ThemeMode = ThemeMode.DARK,
        fontSetId: String = "source",
        previewFontScale: Float = 1.0f,
        editorFontScale: Float = 1.0f,
    ) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().background(DarkColorScheme.primaryBackground)) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    SettingsScreen(
                        themeMode = themeMode,
                        fontSetId = fontSetId,
                        previewFontScale = previewFontScale,
                        onPreviewFontScaleChanged = { previewScale = it },
                        editorFontScale = editorFontScale,
                        appVersion = "1.0.0",
                    )
                }
            }
        }
    }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val isRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
    private val isSlider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun assertStepper(name: String, expected: Float) {
        show()
        val steppers = composeRule.onAllNodes(hasContentDescription(name) and isButton)
        assertEquals("one \"$name\" button per size control", 2, steppers.fetchSemanticsNodes().size)
        // TalkBack reads the name alone, not "Smaller text, A": the glyph's own text is cleared,
        // while the button role and click action survive.
        for (i in 0 until 2) {
            steppers[i]
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
                .assert(hasClickAction())
        }
        // The first is the preview size control.
        steppers[0].performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("\"$name\" steps the preview scale", expected, previewScale)
    }

    @Test
    fun smallerTextStepperIsANamedButton() = assertStepper("Smaller text", 0.95f)

    @Test
    fun largerTextStepperIsANamedButton() = assertStepper("Larger text", 1.05f)

    @Test
    fun themeOptionsAreRadioButtonsSelectedByTheCurrentTheme() {
        show(themeMode = ThemeMode.LIGHT)
        composeRule.onNode(hasText("Light") and isRadio).assertIsSelected()
        composeRule.onNode(hasText("Dark") and isRadio).assertIsNotSelected()
    }

    @Test
    fun fontPillsAreRadioButtonsSelectedByTheCurrentSet() {
        show(fontSetId = "book")
        composeRule.onNode(hasText("Book") and isRadio).assertIsSelected()
        composeRule.onNode(hasText("Classic") and isRadio).assertIsNotSelected()
        composeRule.onNode(hasText("Modern") and isRadio).assertIsNotSelected()
    }

    @Test
    fun sliderSpeaksTheReadoutPercent() {
        show(previewFontScale = 1.0f, editorFontScale = 1.2f)
        val sliders = composeRule.onAllNodes(isSlider).fetchSemanticsNodes()
        assertEquals("one slider per size control", 2, sliders.size)
        val spoken = sliders.map { it.config[SemanticsProperties.StateDescription] }
        assertEquals(listOf("100%", "120%"), spoken)
    }
}
