// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.screen.StrandedSlotsDialog
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.RecentFileUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M-221 on screen: in the recent-files list and in the "Recover unsaved files" dialog, a long file
 * name is shown on one line as its start, `…` and its last 8 characters, while TalkBack still gets
 * the full name. A short name is shown whole.
 *
 * What is checked is the string the Text actually laid out (through `GetTextLayoutResult`), and that
 * it fits its line without an end-ellipsis. Before the fix the list laid out the full name and cut it at the end
 * (the layout overflows), and the dialog laid out the full name over two lines.
 *
 * NATIVE graphics, because the fit is decided by measured pixels, and Robolectric's legacy text
 * metrics differ from the native ones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileNameMiddleEllipsisTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 1.0f)
    fun recentsShowTheLastEightCharactersOfALongName() = checkRecents()

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 2.0f)
    fun recentsShowTheLastEightCharactersOfALongNameAtTheLargestFont() = checkRecents()

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 1.0f)
    fun recoverDialogShowsTheLastEightCharactersOfALongName() = checkDialog()

    @Test
    @Config(qualifiers = S24_PLUS, fontScale = 2.0f)
    fun recoverDialogShowsTheLastEightCharactersOfALongNameAtTheLargestFont() = checkDialog()

    private fun checkRecents() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                    WelcomeScreen(
                        recentFiles = listOf(
                            RecentFileUi(Uri.parse("content://uat/v2"), LONG_V2, 2_000L, available = true),
                            RecentFileUi(Uri.parse("content://uat/short"), SHORT, 1_000L, available = true),
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertShortenedWithFullNameForTalkBack()
    }

    private fun checkDialog() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                StrandedSlotsDialog(
                    slots = listOf(
                        StrandedSlot("v2", Uri.parse("content://uat/v2"), LONG_V2),
                        StrandedSlot("short", Uri.parse("content://uat/short"), SHORT),
                    ),
                    onRescue = {},
                    onDiscard = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertShortenedWithFullNameForTalkBack()
    }

    private fun assertShortenedWithFullNameForTalkBack() {
        val long = layoutOf(LONG_V2)
        val shown = long.layoutInput.text.text
        assertTrue(
            "The long name must end with its last 8 characters after '…', but the Text laid out " +
                "'$shown' (overflow=${long.hasVisualOverflow}, lines=${long.lineCount})",
            shown.contains("…") && shown.endsWith("…" + LONG_V2.takeLast(8)),
        )
        assertTrue("'$shown' must start with the start of the name", LONG_V2.startsWith(shown.substringBefore("…")))
        // Not `hasVisualOverflow`: with `softWrap = false` and `Ellipsis` the paragraph is laid out at
        // the full width while the node takes the text's own width, so it reports an overflow for
        // text that fits. The line's own ellipsis flag and the intrinsic width are the real test.
        assertFalse("'$shown' must not be cut at the end", long.isLineEllipsized(0))
        assertTrue(
            "'$shown' must fit: ${long.multiParagraph.intrinsics.maxIntrinsicWidth}px of " +
                "${long.layoutInput.constraints.maxWidth}px",
            long.multiParagraph.intrinsics.maxIntrinsicWidth <= long.layoutInput.constraints.maxWidth,
        )
        assertEquals("'$shown' must be one line", 1, long.lineCount)

        val short = layoutOf(SHORT)
        assertEquals("A name that fits is shown whole", SHORT, short.layoutInput.text.text)

        // TalkBack reads the merged node; it must carry the full name and never the shortened one.
        val merged = composeRule.onNodeWithText(LONG_V2).fetchSemanticsNode().config
        val texts = merged.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("TalkBack must get the full name, got $texts", LONG_V2 in texts)
        assertFalse("TalkBack must not get the shortened name, got $texts", shown in texts)
    }

    /** The layout of the Text whose accessible name is [name], as it was actually drawn. */
    private fun layoutOf(name: String): TextLayoutResult {
        val node = composeRule.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private companion object {
        /** The S24+ in portrait: `adb shell wm size` 1080x2340, `wm density` 450. */
        const val S24_PLUS = "w384dp-h832dp-night-450dpi"
        const val LONG_V2 = "quarterly-report-final-review-with-comments-draft-v2.md"
        const val SHORT = "notes.md"
    }
}
