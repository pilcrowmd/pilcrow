// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-220: the reader's jump buttons with TalkBack on (touch exploration). The accessibility service
 * is reported as running, so Compose builds its accessibility tree exactly as it does for TalkBack,
 * and the checks read that tree: [AccessibilityNodeInfo]s from the Compose view's provider, not
 * the test framework's semantics view of it.
 *
 * Native graphics: Compose decides which nodes are on screen with `Region` arithmetic, which the
 * legacy graphics mode stubs out, leaving every node out of the tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h832dp-night-450dpi")
class JumpControlsTalkBackTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val longDocument = (1..60).joinToString("\n\n") { "Paragraph $it of a document longer than the screen." }

    private val accessibility: AccessibilityManager
        get() = composeRule.activity.getSystemService(AccessibilityManager::class.java)

    /**
     * The first frame the buttons are in the tree, each is already visible to the user, a button,
     * and labelled on the clickable node itself. Before M-220 they faded in from alpha 0, which
     * Compose reports as not visible, and no event followed when the alpha rose.
     */
    @Test
    fun withTalkBackBothButtonsAreVisibleLabelledButtonsAtFirstLayout() {
        startTalkBack(touchExploration = true)
        showReader(settle = false)

        var frames = 0
        while (buttonNodeIds().isEmpty() && frames < MAX_FRAMES_TO_APPEAR) {
            frame()
            frames++
        }
        // No scroll, drag or wait: only the frames it takes the list to lay out once.
        listOf(SCROLL_TO_TOP, SCROLL_TO_BOTTOM).forEach { label ->
            val info = nodeInfo(composeRule.onNodeWithContentDescription(label).fetchSemanticsNode().id)
            assertTrue("$label is visible to TalkBack after $frames frames", info.isVisibleToUser)
            assertEquals(label, info.contentDescription?.toString())
            assertEquals(Button::class.java.name, info.className?.toString())

            // The label is set on the node that clicks, not merged up from a child.
            composeRule.onNodeWithContentDescription(label, useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        }
    }

    /**
     * Compose's traversal links, as TalkBack receives them: the text above the reader (standing in
     * for the toolbar), then Scroll to top, then Scroll to bottom, then the list's interop view. The
     * list's paragraphs are children of that view, so this puts both buttons before the first one.
     * Compose exposes the links through these test extras because `traversalBefore` cannot be read
     * back from an unsealed node; what TalkBack then does with them is a device check.
     */
    @Test
    fun withTalkBackTheButtonsComeAfterTheContentAboveAndBeforeTheDocument() {
        startTalkBack(touchExploration = true)
        showReader(withTextAbove = true)

        val above = composeRule.onNodeWithText(ABOVE).fetchSemanticsNode().id
        val top = composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).fetchSemanticsNode().id
        val bottom = composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).fetchSemanticsNode().id
        val list = recyclerView().parent as View

        assertEquals("after the text above comes Scroll to top", top, nodeInfo(above).extras.getInt(BEFORE, NONE))
        assertEquals("then Scroll to bottom", bottom, nodeInfo(top).extras.getInt(BEFORE, NONE))
        val listInfo = list.createAccessibilityNodeInfo()
        assertEquals("then the document", bottom, listInfo.extras.getInt(AFTER, NONE))
    }

    @Test
    fun withTalkBackAJumpSaysWhereItLanded() {
        startTalkBack(touchExploration = true)
        showReader()

        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).performClick()
        settle()
        assertEquals(listOf("End of document"), announcements())

        composeRule.onNodeWithContentDescription(SCROLL_TO_TOP).performClick()
        settle()
        assertEquals(listOf("End of document", "Top of document"), announcements())
    }

    /**
     * Without touch exploration nothing is announced, with an accessibility service still running
     * (a password manager, Switch Access), so an announcement would have been delivered.
     */
    @Test
    fun withoutTouchExplorationAJumpIsSilent() {
        startTalkBack(touchExploration = false)
        showReader()
        // Shown by a drag, as a sighted user sees them; held still before the release so there is
        // no fling, and tapped well inside the linger.
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -DRAG_STEP_PX)) }
            advanceEventTime(HOLD_BEFORE_RELEASE_MS)
            up()
        }
        settle()

        composeRule.onNodeWithContentDescription(SCROLL_TO_BOTTOM).performClick()
        settle()
        assertEquals(emptyList<String>(), announcements())
    }

    private fun startTalkBack(touchExploration: Boolean) {
        shadowOf(accessibility).setEnabled(true)
        shadowOf(accessibility).setTouchExplorationEnabled(touchExploration)
        shadowOf(accessibility).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    private fun showReader(withTextAbove: Boolean = false, settle: Boolean = true) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val context = LocalContext.current
            val renderer = remember { MarkwonRenderer(context) }
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                Column {
                    if (withTextAbove) Text(ABOVE)
                    MarkdownPreview(content = longDocument, renderer = renderer)
                }
            }
        }
        if (settle) settle()
    }

    private fun buttonNodeIds(): List<Int> = listOf(SCROLL_TO_TOP, SCROLL_TO_BOTTOM).flatMap { label ->
        composeRule.onAllNodesWithContentDescription(label)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .map { it.id }
    }

    // TYPE_ANNOUNCEMENT is deprecated with announceForAccessibility (API 36); it is what the reader sends.
    @Suppress("DEPRECATION")
    private fun announcements(): List<String> = shadowOf(accessibility).sentAccessibilityEvents
        .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
        .map { it.text.joinToString() }

    /** The node TalkBack would get for the Compose node [id], from the Compose view's provider. */
    private fun nodeInfo(id: Int): AccessibilityNodeInfo =
        checkNotNull(composeView().accessibilityNodeProvider?.createAccessibilityNodeInfo(id)) {
            "no accessibility node for semantics id $id"
        }

    private fun composeView(): View {
        val view = findView(composeRule.activity.window.decorView) { it.javaClass.simpleName == "AndroidComposeView" }
        return checkNotNull(view)
    }

    private fun recyclerView(): RecyclerView =
        checkNotNull(findView(composeRule.activity.window.decorView) { it is RecyclerView }) as RecyclerView

    private fun findView(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findView(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun frame() {
        composeRule.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun settle() = repeat(SETTLE_PASSES) { frame() }

    private companion object {
        const val SCROLL_TO_TOP = "Scroll to top"
        const val SCROLL_TO_BOTTOM = "Scroll to bottom"
        const val ABOVE = "Above the reader"
        const val BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
        const val AFTER = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALAFTER_VAL"
        const val NONE = -1
        const val MAX_FRAMES_TO_APPEAR = 10
        const val DRAG_STEPS = 10
        const val DRAG_STEP_PX = 40f
        const val HOLD_BEFORE_RELEASE_MS = 500L
        const val SETTLE_PASSES = 10
    }
}
