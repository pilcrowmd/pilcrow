// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRequest
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * S8-A7: the full-screen diagram viewer. It has a labelled close button that works, the picture is
 * there for TalkBack, and its panel takes the colour of whichever theme is active: the dialog is a
 * window of its own, so this also proves the theme reaches into it (a viewer that fell back to the
 * composition local's default would be dark in Light).
 *
 * Native graphics: the panel colour is read from the drawn pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class DiagramViewerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var closed = 0

    private fun show(scheme: PilcrowColorScheme) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides scheme) {
                DiagramViewer(
                    picture = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).asImageBitmap(),
                    onClose = { closed++ },
                )
            }
        }
    }

    @Test
    fun `the close button is labelled and closes the viewer`() {
        show(DarkColorScheme)

        composeRule.onNodeWithContentDescription("Close diagram").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Close diagram").performClick()

        assertEquals(1, closed)
    }

    @Test
    fun `the picture is announced as the diagram`() {
        show(DarkColorScheme)

        composeRule.onNodeWithContentDescription("Mermaid diagram, full screen").assertIsDisplayed()
    }

    @Test
    fun `the panel follows the dark theme`() = assertPanelColour(DarkColorScheme)

    @Test
    fun `the panel follows the light theme`() = assertPanelColour(LightColorScheme)

    /**
     * Measured on S24plus_API36: a dialog window keeps fitting the system bars (WindowManager framed it
     * [0,79][2340,1012] in landscape), while Compose sizes the dialog's content to the whole screen
     * (2340x1080), so the bottom of the picture was cut off. A window that fits no bars is framed to
     * the whole screen, which is the size its content is measured for.
     */
    @Test
    fun `the viewer window fits no system bars, so it is as large as its content`() {
        show(DarkColorScheme)

        composeRule.waitForIdle()
        assertEquals(0, ShadowDialog.getLatestDialog().window!!.attributes.fitInsetsTypes)
    }

    @Test
    fun `the system bar icons are light on the dark panel`() {
        assertEquals("status, navigation", false to false, lightBarIcons(DarkColorScheme))
    }

    @Test
    fun `the system bar icons are dark on the light panel`() {
        assertEquals("status, navigation", true to true, lightBarIcons(LightColorScheme))
    }

    /** Whether the status and the navigation bar draw dark ("light appearance") icons over the panel. */
    private fun lightBarIcons(scheme: PilcrowColorScheme): Pair<Boolean, Boolean> {
        show(scheme)
        composeRule.waitForIdle()
        val window = ShadowDialog.getLatestDialog().window!!
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        return bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
    }

    @Test
    fun `the open diagram survives a rotation, and a closed viewer stays closed`() {
        val tester = StateRestorationTester(composeRule)
        var open: MutableState<MermaidDiagramRequest?>? = null
        tester.setContent {
            open = rememberSaveable(stateSaver = MermaidDiagramRequestSaver) { mutableStateOf(null) }
        }
        open!!.value = MermaidDiagramRequest("flowchart TD\n  A --> B\n", 912)
        composeRule.waitForIdle()

        open = null
        tester.emulateSavedInstanceStateRestore()
        assertEquals(MermaidDiagramRequest("flowchart TD\n  A --> B\n", 912), open!!.value)

        open!!.value = null
        composeRule.waitForIdle()
        open = null
        tester.emulateSavedInstanceStateRestore()
        assertEquals(null, open!!.value)
    }

    @Test
    fun `a diagram too large for the saved state closes on rotation instead of being saved`() {
        val tester = StateRestorationTester(composeRule)
        var open: MutableState<MermaidDiagramRequest?>? = null
        tester.setContent {
            open = rememberSaveable(stateSaver = MermaidDiagramRequestSaver) { mutableStateOf(null) }
        }
        open!!.value = MermaidDiagramRequest("x".repeat(MAX_SAVED_DIAGRAM_SOURCE + 1), 912)
        composeRule.waitForIdle()

        open = null
        tester.emulateSavedInstanceStateRestore()
        assertEquals(null, open!!.value)
    }

    /** The close button's top-left pixel is the panel: nothing is drawn there but the viewer's background. */
    private fun assertPanelColour(scheme: PilcrowColorScheme) {
        show(scheme)

        composeRule.waitForIdle()
        val decor = ShadowDialog.getLatestDialog().window!!.decorView
        val drawn = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(drawn))
        assertEquals("size ${decor.width}x${decor.height}", scheme.codeBlockBg.toArgb(), drawn.getPixel(0, 0))
    }
}
