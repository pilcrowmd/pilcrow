// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.view.KeyEvent
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.viewinterop.AndroidView
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.testing.useKeyboardMode
import com.pilcrowmd.ui.components.HeadingsDrawer
import com.pilcrowmd.ui.theme.DarkColorScheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-307 #3: with the TOC drawer open and a hardware keyboard, Tab walked out of the drawer into the
 * reader behind it (an Android RecyclerView inside an AndroidView), and closing the drawer did not
 * bring focus back to the Table of contents button.
 *
 * The content here is the real shape: a Compose control, the "toc" button, and an Android View
 * inside an AndroidView, so a block that only stopped Compose focus would still leak into the View.
 * Tab goes through the ACTIVITY, the route a hardware keyboard takes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
class ThemedNavigationDrawerFocusTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var drawerState: DrawerState
    private lateinit var scope: kotlinx.coroutines.CoroutineScope
    private lateinit var androidView: Button

    /** [composeControls] false leaves the Android View alone in the content: the interop view is the only way in. */
    private fun show(
        composeControls: Boolean = true,
        drawerRows: Boolean = true,
        realDrawer: Boolean = false,
        androidViewInContent: Boolean = true,
    ) {
        lateinit var manager: InputModeManager
        composeRule.setContent {
            manager = LocalInputModeManager.current
            drawerState = rememberDrawerState(DrawerValue.Closed)
            scope = rememberCoroutineScope()
            // MainScreen's Back: BackHandler -> drawerState.close().
            BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
            ThemedNavigationDrawer(
                colorScheme = DarkColorScheme,
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen,
                drawerContent = {
                    if (realDrawer) {
                        // MainScreen's drawer: its rows are focusable only while the drawer opens/is open.
                        HeadingsDrawer(
                            headings = HEADINGS,
                            rowsFocusable = { drawerState.targetValue == DrawerValue.Open },
                        )
                    } else {
                        Column(Modifier.fillMaxSize().testTag(DRAWER)) {
                            // No rows: a document without headings opens an empty drawer.
                            if (drawerRows) {
                                Text("Row 1", Modifier.testTag(ROW_1).clickable {})
                                // A heading row: selecting it closes the drawer, as onHeadingSelected does.
                                val closeDrawer = { scope.launch { drawerState.close() } }
                                Text("Row 2", Modifier.testTag(ROW_2).clickable { closeDrawer() })
                            }
                        }
                    }
                },
            ) {
                Column {
                    if (composeControls) {
                        Text("First", Modifier.testTag(FIRST).clickable {})
                        Text("TOC", Modifier.testTag(TOC).clickable {})
                    }
                    if (androidViewInContent) {
                        AndroidView(factory = { context ->
                            Button(context).also {
                                it.isFocusable = true
                                androidView = it
                            }
                        })
                    }
                }
            }
        }
        composeRule.runOnIdle { manager.useKeyboardMode() }
        if (!composeControls) return
        // Where the user was when they pressed the Table of contents button.
        composeRule.onNodeWithTag(TOC).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(TOC).assertIsFocused()
    }

    private fun openDrawer() {
        composeRule.runOnIdle { scope.launch { drawerState.open() } }
        composeRule.waitForIdle()
        assertEquals("precondition: the drawer opened", DrawerValue.Open, drawerState.currentValue)
    }

    /** Puts focus on the first drawer row by hand, so a test of Tab does not depend on the focus move. */
    private fun focusFirstRow() {
        composeRule.onNodeWithTag(ROW_1).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(ROW_1).assertIsFocused()
    }

    /** Presses Tab; true if Compose consumed it, false if it was left to the View system's own focus search. */
    private fun tab(): Boolean {
        var consumed = false
        composeRule.runOnIdle {
            consumed = composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
            composeRule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB))
        }
        composeRule.waitForIdle()
        return consumed
    }

    private fun assertContentNotFocused(afterTab: Int, composeControls: Boolean = true) {
        if (composeControls) {
            listOf(FIRST, TOC).forEach {
                composeRule.onNodeWithTag(it).assertIsNotFocused()
            }
        }
        composeRule.runOnIdle {
            assertFalse("Tab $afterTab reached the Android View behind the drawer", androidView.hasFocus())
        }
    }

    private fun rowIsFocused(tag: String): Boolean =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun headingIsFocused(text: String): Boolean =
        composeRule.onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun closeByBack() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun closeByScrimTap() {
        // The drawer sheet is 355dp wide on a 411dp screen: the right-hand edge is scrim.
        composeRule.onRoot().performTouchInput { click(Offset(width - SCRIM_INSET_PX, height / 2f)) }
    }

    private fun closeByRowTap() = composeRule.onNodeWithTag(ROW_2).performClick()

    private fun closeBySwipe() = composeRule.onNodeWithTag(DRAWER).performTouchInput { swipeLeft() }

    private fun assertClosedBy(close: () -> Unit) {
        close()
        composeRule.waitForIdle()
        assertEquals("the drawer closed", DrawerValue.Closed, drawerState.currentValue)
    }

    // ---- opening ----

    @Test
    fun inKeyboardModeOpeningTheDrawerMovesFocusIntoIt() {
        show()
        openDrawer()
        composeRule.onNodeWithTag(ROW_1).assertIsFocused()
        composeRule.onNodeWithTag(TOC).assertIsNotFocused()
    }

    // ---- Tab while open ----

    @Test
    fun tabNeverLeavesTheOpenDrawerForTheContent() {
        show()
        openDrawer()
        focusFirstRow()
        repeat(TABS) { n ->
            assertContentNotFocused(afterTab = n)
            // A Tab Compose leaves unconsumed goes to the View system, which would focus the Android View.
            assertTrue("Tab ${n + 1} was left to the View system", tab())
        }
        assertContentNotFocused(afterTab = TABS)
    }

    /**
     * On a device the Tab pressed at the LAST row comes back from Compose unconsumed and the View
     * system's own focus search carries it into the Android views behind the drawer. Robolectric may
     * not reproduce that path (this passed on 1864fd4d before the key handler), so it is a guard: every
     * Tab at the last row is consumed and a drawer row keeps focus.
     */
    @Test
    fun tabAtTheLastDrawerRowIsConsumedAndFocusStaysInTheDrawer() {
        show()
        openDrawer()
        composeRule.onNodeWithTag(ROW_2).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(ROW_2).assertIsFocused()
        repeat(TABS_AT_END) { n ->
            assertTrue("Tab ${n + 1} at the last row was left to the View system", tab())
            assertContentNotFocused(afterTab = n + 1)
            assertTrue("Tab ${n + 1} took focus out of the drawer rows", rowIsFocused(ROW_1) || rowIsFocused(ROW_2))
        }
    }

    /**
     * The drawer's content stays composed while it is closed, and its rows must not be Tab stops
     * then. The rows are made non-focusable; cancelling the move into the whole group instead cancels
     * the Tab itself, and Tab stuck on the last control (d6adb19d).
     */
    @Test
    fun tabMovesOnAndNeverStopsOnTheClosedDrawersRows() {
        // The last control in the content: the next stop in Tab order is the drawer sheet after it.
        show(realDrawer = true, androidViewInContent = false)
        composeRule.onNodeWithTag(TOC).assertIsFocused()
        tab()
        composeRule.onNodeWithTag(TOC).assertIsNotFocused()
        repeat(TABS_CLOSED - 1) { n ->
            tab()
            val onARow = headingIsFocused("Intro") || headingIsFocused("Details")
            assertFalse("Tab ${n + 2} focused a row of the CLOSED drawer", onARow)
        }
    }

    /** The other half: the same rows ARE focusable once the drawer is open, and Tab walks them. */
    @Test
    fun theOpenDrawersRowsTakeFocusAndTab() {
        show(realDrawer = true)
        openDrawer()
        assertTrue("opening never focused the first row", headingIsFocused("Intro"))
        tab()
        assertTrue("Tab never reached the second row", headingIsFocused("Details"))
    }

    /** The Android View is the ONLY thing in the content, so only the interop route can reach it. */
    @Test
    fun tabNeverReachesAnAndroidViewBehindTheOpenDrawer() {
        show(composeControls = false)
        openDrawer()
        focusFirstRow()
        repeat(TABS) { n ->
            assertTrue("Tab ${n + 1} was left to the View system", tab())
            assertContentNotFocused(afterTab = n + 1, composeControls = false)
        }
    }

    /**
     * No rows to move focus to: focus must not stay on the Table of contents button behind the scrim,
     * where Tab would walk the content (the group's `enter` block only stops ENTERING it).
     */
    @Test
    fun anEmptyDrawerLeavesNothingFocusedBehindIt() {
        show(drawerRows = false)
        openDrawer()
        assertContentNotFocused(afterTab = 0)
        repeat(TABS_EMPTY) { n ->
            assertTrue("Tab ${n + 1} was left to the View system", tab())
            assertContentNotFocused(afterTab = n + 1)
        }
        assertClosedBy(::closeByBack)
        composeRule.onNodeWithTag(TOC).assertIsFocused()
    }

    // ---- closing, by each path ----
    // Robolectric's injected clicks and swipes leave the input mode at Keyboard, so these are the
    // keyboard-mode closes: a mouse click on the scrim or a row, Enter on a row. On a phone a finger
    // flips the mode to Touch and the drawer takes the clearFocus branch instead; that branch is not
    // tested here, because flipping Robolectric to touch mode clears Compose focus by itself, so such
    // a test would pass with or without it.

    @Test
    fun closingByBackReturnsFocusToToc() = assertCloseRestoresToc(::closeByBack)

    @Test
    fun closingByScrimClickInKeyboardModeReturnsFocusToToc() = assertCloseRestoresToc(::closeByScrimTap)

    @Test
    fun closingByARowClickInKeyboardModeReturnsFocusToToc() = assertCloseRestoresToc(::closeByRowTap)

    @Test
    fun closingBySwipeInKeyboardModeReturnsFocusToToc() = assertCloseRestoresToc(::closeBySwipe)

    @Test
    fun afterBackTheContentIsReachableByTab() = assertContentReachableAfter(::closeByBack)

    @Test
    fun afterScrimTapTheContentIsReachableByTab() = assertContentReachableAfter(::closeByScrimTap)

    @Test
    fun afterARowTapTheContentIsReachableByTab() = assertContentReachableAfter(::closeByRowTap)

    @Test
    fun afterSwipeTheContentIsReachableByTab() = assertContentReachableAfter(::closeBySwipe)

    /**
     * Focus is put IN the drawer by hand before the close: left on "toc" it would still be on "toc"
     * after, and the return of focus would never be tested.
     */
    private fun assertCloseRestoresToc(close: () -> Unit) {
        show()
        openDrawer()
        focusFirstRow()
        assertClosedBy(close)
        composeRule.onNodeWithTag(TOC).assertIsFocused()
    }

    /** Independent of the focus return: focus is put back on "toc" by hand, then Tab must reach the View. */
    private fun assertContentReachableAfter(close: () -> Unit) {
        show()
        openDrawer()
        assertClosedBy(close)
        composeRule.onNodeWithTag(TOC).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag(TOC).assertIsFocused()
        tab()
        composeRule.runOnIdle { assertTrue("Tab after closing never reached the Android View", androidView.hasFocus()) }
    }

    private companion object {
        val HEADINGS = listOf(HeadingNode(1, "Intro", 0), HeadingNode(2, "Details", 1))
        const val DRAWER = "drawer"
        const val ROW_1 = "row1"
        const val ROW_2 = "row2"
        const val FIRST = "first"
        const val TOC = "toc"
        const val TABS = 6
        const val TABS_EMPTY = 4
        const val TABS_AT_END = 3
        const val TABS_CLOSED = 4
        const val SCRIM_INSET_PX = 10f
    }
}
