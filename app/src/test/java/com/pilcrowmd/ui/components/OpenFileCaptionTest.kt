// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line that names the open file, pinned above the editor (Sora has no header row to scroll
 * away). The text is the pure function's output ([com.pilcrowmd.domain.folders.OpenFileLineTest]);
 * what is checked here is that the editor shows it, that TalkBack reads it once and in full, that it
 * cannot be tapped, and that the document in the editor is untouched.
 */
@RunWith(RobolectricTestRunner::class)
class OpenFileCaptionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val text = "# Title\n\nText\n"

    private fun showEditor(openFile: OpenFileLine?): CodeEditor {
        val hoisted = CodeEditor(ApplicationProvider.getApplicationContext())
        compose.setContent {
            MarkdownEditor(
                content = text,
                onContentChange = {},
                codeEditorInstance = hoisted,
                openFile = openFile,
            )
        }
        compose.waitForIdle()
        return hoisted
    }

    @Test
    fun aNoteFromMyFoldersShowsItsFolderPathAndTalkBackReadsItOnce() {
        showEditor(OpenFileLine("plan.md", listOf("Notes", "projects")))

        compose.onNodeWithTag(OPEN_FILE_CAPTION_TAG).assertContentDescriptionEquals("File: Notes, projects, plan.md")
        compose.onNodeWithText("Notes › projects › plan.md", useUnmergedTree = true).assertExists()
    }

    @Test
    fun anyOtherFileShowsItsNameAlone() {
        showEditor(OpenFileLine("plan.md"))

        compose.onNodeWithTag(OPEN_FILE_CAPTION_TAG).assertContentDescriptionEquals("File: plan.md")
        compose.onNodeWithText("plan.md", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theLineCannotBeTapped() {
        showEditor(OpenFileLine("plan.md"))

        compose.onNodeWithTag(OPEN_FILE_CAPTION_TAG).assertHasNoClickAction()
    }

    @Test
    fun withoutALineNothingIsShownAboveTheEditor() {
        showEditor(null)

        compose.onAllNodesWithTag(OPEN_FILE_CAPTION_TAG).assertCountEquals(0)
    }

    @Test
    fun theLineNeverTouchesTheDocumentInTheEditor() {
        val editor = showEditor(OpenFileLine("plan.md", listOf("Notes")))

        assertEquals(text, editor.text.toString())
    }

    /** GitHub #19: the line ends in "*" while the file has unsaved changes, and TalkBack says so. */
    @Test
    fun anUnsavedFileShowsAnAsteriskAndTalkBackSaysUnsavedChanges() {
        showEditor(OpenFileLine("plan.md", unsaved = true))

        compose.onNodeWithTag(OPEN_FILE_CAPTION_TAG).assertContentDescriptionEquals("File: plan.md, unsaved changes")
        compose.onNodeWithText("plan.md*", useUnmergedTree = true).assertExists()
    }

    /** The reader's line, a view of its own, shows and speaks the same. */
    @Test
    fun theReadersLineShowsAndSpeaksTheSame() {
        val line = OpenFileCaptionView(ApplicationProvider.getApplicationContext())

        line.bind(OpenFileLine("plan.md", listOf("Notes"), unsaved = true), DarkColorScheme, 1f)
        assertEquals("Notes › plan.md*", line.text.toString())
        assertEquals("File: Notes, plan.md, unsaved changes", line.contentDescription)

        line.bind(OpenFileLine("plan.md", listOf("Notes")), DarkColorScheme, 1f)
        assertEquals("Notes › plan.md", line.text.toString())
        assertEquals("File: Notes, plan.md", line.contentDescription)
    }
}
