// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pilcrowmd.screenshot.FilesSample
import com.pilcrowmd.ui.components.FilesScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.viewmodel.FileRowUi
import com.pilcrowmd.viewmodel.ListingUi
import com.pilcrowmd.viewmodel.NotesFoldersUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M-162b: what TalkBack hears on the Files screen (M8), and its Mark all as seen action (M5). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FilesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private var backs = 0

    private fun show(
        state: NotesFoldersUiState = FilesSample.loaded,
        openNoteId: String? = null,
        onMarkAllSeen: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMDColors provides DarkColorScheme) {
                FilesScreen(
                    state = state,
                    openNoteId = openNoteId,
                    onBack = { backs++ },
                    onOpenNote = { opened += it },
                    onMarkAllSeen = onMarkAllSeen,
                )
            }
        }
    }

    @Test
    fun a_New_note_is_read_in_full_words() {
        show()
        composeRule.onNodeWithContentDescription("Note 2.md, new, added 12 minutes ago").assertExists()
    }

    @Test
    fun an_Updated_note_is_read_in_full_words() {
        show()
        composeRule.onNodeWithContentDescription("plan.md, updated, 1 hour ago").assertExists()
    }

    @Test
    fun an_unmarked_note_and_the_folders_are_read_plainly() {
        show()
        composeRule.onNodeWithContentDescription("index.md").assertExists()
        composeRule.onNodeWithContentDescription("archive, folder, collapsed, 2 marked notes").assertExists()
        composeRule.onNodeWithContentDescription("projects, folder, expanded").assertExists()
    }

    @Test
    fun mark_all_as_seen_calls_the_view_model() {
        var calls = 0
        show(onMarkAllSeen = { calls++ })

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Mark all as seen").performClick()
        composeRule.waitForIdle()

        assertEquals(1, calls)
    }

    @Test
    fun mark_all_as_seen_is_off_when_nothing_is_marked() {
        val folders = FilesSample.loaded.folders.map { it.copy(markCount = it.markCount?.let { 0 }) }
        show(FilesSample.loaded.copy(folders = folders))

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Mark all as seen").assertIsNotEnabled()
    }

    @Test
    fun tapping_the_note_already_open_only_closes_files() {
        show(openNoteId = FilesSample.OPEN_NOTE)

        composeRule.onNodeWithContentDescription("index.md").performClick()
        composeRule.onNodeWithContentDescription("Note 10.md").performClick()
        composeRule.waitForIdle()

        assertEquals("the open note is not opened again", listOf("note10"), opened)
        assertEquals(1, backs)
    }

    @Test
    fun one_document_in_two_open_folders_shows_twice() {
        val loaded = FilesSample.loaded.listing as ListingUi.Loaded
        val shared = FileRowUi.Note("doc-1", "shared.md", depth = 1, mark = null, ago = null)
        val rows = listOf(
            FileRowUi.Folder("f1", "one", depth = 0, expanded = true, markCount = 0, key = "f1"),
            shared.copy(key = "f1/doc-1"),
            FileRowUi.Folder("f2", "two", depth = 0, expanded = true, markCount = 0, key = "f2"),
            shared.copy(key = "f2/doc-1"),
        )
        show(FilesSample.loaded.copy(listing = loaded.copy(rows = rows)))

        assertEquals(2, composeRule.onAllNodesWithContentDescription("shared.md").fetchSemanticsNodes().size)
    }
}
