// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.testing.withRealSize
import com.pilcrowmd.viewmodel.DocumentId
import io.github.rosemoe.sora.event.ScrollEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Issue #15 (M-388), the Edit side of a Read to Edit landing: the editor must open with the landing
 * line in the middle of its screen.
 *
 * On a phone the first Edit after opening a 329-line note showed the top of the note. Measured here
 * with the same note (`00-test-1014.md`): the reveal ran with the right size and the right row
 * position, and Sora's word-wrap pass ended a few milliseconds later in `scrollBy(0, 0)`, which cancels
 * a scroll that has not been drawn. [revealLineInMiddle] now applies its move at once.
 */
@RunWith(RobolectricTestRunner::class)
class EditorLineRevealTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val note = javaClass.classLoader!!.getResource("00-test-1014.md")!!.readText()

    private fun settle(millis: Long = SETTLE_STEP_MILLIS) {
        repeat(SETTLE_STEPS) {
            compose.waitForIdle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        }
        compose.waitForIdle()
    }

    @Test
    fun `the first Edit of a short note opens with the landing line in the middle third of the screen`() {
        val editor = AccessibleCodeEditor(ApplicationProvider.getApplicationContext()) // never sized: as a fresh Edit
        val landing = note.indexOf("Section 30, paragraph one")
        var show by mutableStateOf(false)
        val id = DocumentId.mint()
        compose.setContent {
            if (show) {
                Box(Modifier.height(BOX_DP.dp)) {
                    MarkdownEditor(
                        content = note,
                        onContentChange = {},
                        codeEditorInstance = editor,
                        documentId = id,
                        initialCursor = landing,
                    )
                }
            }
        }
        compose.waitForIdle()

        show = true
        settle()

        compose.runOnUiThread {
            val line = editor.cursor.leftLine
            assertEquals("the caret is on the landing line", note.substring(0, landing).count { it == '\n' }, line)
            // getCharOffsetY is the bottom of the caret's row in editor coordinates.
            val rowTop = editor.getCharOffsetY(line, 0) - editor.rowHeight
            val third = editor.height / 3f
            assertTrue("height ${editor.height} is real", editor.height > 0)
            val middleThird = third..2 * third
            assertTrue("the row top ${rowTop}px is in the middle third ($middleThird)", rowTop in middleThird)
        }
    }

    private fun revealCount(sized: Boolean = true): Pair<SettledReveal, IntArray> {
        val editor = AccessibleCodeEditor(ApplicationProvider.getApplicationContext())
        if (sized) editor.withRealSize()
        val count = IntArray(1)
        return SettledReveal(editor, line = 0, reveal = { count[0]++ }) to count
    }

    private fun SettledReveal.editor() = javaClass.getDeclaredField("editor").apply { isAccessible = true }
        .get(this) as io.github.rosemoe.sora.widget.CodeEditor

    @Test
    fun `a reveal asked for before the editor has a size waits for its first layout`() {
        val (reveal, count) = revealCount(sized = false)

        reveal.start()
        assertEquals("nothing to reveal on until the editor has a size", 0, count[0])

        reveal.editor().withRealSize()

        assertEquals("revealed when the first layout came", 1, count[0])
    }

    @Test
    fun `a sized editor is revealed at once and again each time its layout finishes`() {
        val (reveal, count) = revealCount()

        reveal.start()
        assertEquals(1, count[0])
        val editor = reveal.editor()
        editor.setLayoutBusy(true)
        assertEquals("busy is not done", 1, count[0])
        editor.setLayoutBusy(false)
        assertEquals("the wrap layout finished", 2, count[0])
    }

    @Test
    fun `the user taking over stops it`() {
        val (reveal, count) = revealCount()
        reveal.start()
        val editor = reveal.editor()

        editor.dispatchEvent(ScrollEvent(editor, 0, 0, 0, 40, ScrollEvent.CAUSE_MAKE_POSITION_VISIBLE))
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)
        assertEquals("a scroll to keep the caret visible is not the user's", 2, count[0])

        editor.dispatchEvent(ScrollEvent(editor, 0, 0, 0, 0, ScrollEvent.CAUSE_USER_DRAG))
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)
        assertEquals("Sora's own scrollBy(0, 0) reports a drag that went nowhere", 3, count[0])

        editor.dispatchEvent(ScrollEvent(editor, 0, 0, 0, 40, ScrollEvent.CAUSE_USER_DRAG))
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)
        assertEquals("after the user's drag it lets go", 3, count[0])
    }

    /** A reveal that scrolls the editor to [REVEALED_Y], as the real one scrolls it to the landing line. */
    private fun scrollingReveal(): Pair<SettledReveal, IntArray> {
        val editor = AccessibleCodeEditor(ApplicationProvider.getApplicationContext()).withRealSize()
        val count = IntArray(1)
        val reveal = SettledReveal(editor, line = 0, reveal = {
            count[0]++
            editor.scroller.startScroll(0, 0, 0, REVEALED_Y, 0)
            editor.scroller.computeScrollOffset()
        })
        return reveal to count
    }

    @Test
    fun `a scroll event that reports the reveal's own move does not stop it`() {
        val (reveal, count) = scrollingReveal()
        reveal.start()
        val editor = reveal.editor()
        assertEquals("precondition: the reveal moved the editor", REVEALED_Y, editor.offsetY)

        // On a device the scroller-driven move can itself come back as a ScrollEvent that ends where we put it.
        editor.dispatchEvent(ScrollEvent(editor, 0, 0, 0, REVEALED_Y, ScrollEvent.CAUSE_USER_FLING))
        val before = count[0]
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)

        assertEquals("still watching: the wrap layout finishing reveals again", before + 1, count[0])
    }

    @Test
    fun `a scroll event to somewhere else than the reveal put it stops it`() {
        val (reveal, count) = scrollingReveal()
        reveal.start()
        val editor = reveal.editor()

        editor.dispatchEvent(ScrollEvent(editor, 0, 0, 0, REVEALED_Y + 50, ScrollEvent.CAUSE_USER_FLING))
        val before = count[0]
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)

        assertEquals("the user took over", before, count[0])
    }

    @Test
    fun `an edit or a tap in the text stops it`() {
        val (typing, typingCount) = revealCount()
        typing.start()
        compose.runOnUiThread { typing.editor().text.insert(0, 0, "x") }
        typing.editor().setLayoutBusy(true)
        typing.editor().setLayoutBusy(false)
        assertEquals("typing stops it", 1, typingCount[0])

        val (tapping, tapCount) = revealCount()
        tapping.start()
        tapping.editor().setSelection(0, 0)
        tapping.editor().setLayoutBusy(true)
        tapping.editor().setLayoutBusy(false)
        assertEquals("moving the caret stops it", 1, tapCount[0])
    }

    @Test
    fun `the settle time is long enough for the wrap layout of a big note on a slow phone`() {
        assertTrue("$REVEAL_SETTLE_MILLIS ms", REVEAL_SETTLE_MILLIS >= TEN_SECONDS_MILLIS)
    }

    @Test
    fun `it lets go after the settle time`() {
        val (reveal, count) = revealCount()
        reveal.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_STEP_MILLIS)) // the editor's own layout ends
        val whileWatching = count[0]
        val editor = reveal.editor()
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)
        assertEquals("still watching", whileWatching + 1, count[0])

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(REVEAL_SETTLE_MILLIS))
        val atTheEnd = count[0]
        editor.setLayoutBusy(true)
        editor.setLayoutBusy(false)

        assertEquals("not any more", atTheEnd, count[0])
    }

    private companion object {
        const val BOX_DP = 400
        const val REVEALED_Y = 300
        const val TEN_SECONDS_MILLIS = 10_000L
        const val SETTLE_STEP_MILLIS = 100L
        const val SETTLE_STEPS = 5
    }
}
