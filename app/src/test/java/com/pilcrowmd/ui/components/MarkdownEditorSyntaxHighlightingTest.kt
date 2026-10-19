// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-154: the editor's `syntaxHighlighting = false` tier. Without it a large document gets Sora's
 * TextMate analyzer — a second copy of the buffer plus per-line tokens, three quarters of the
 * editor's memory. Small documents here; the memory itself is `LargeDocumentEditorTest`'s job.
 *
 * Every language check is `is TextMateLanguage`, never `is EmptyLanguage`: TextMateLanguage EXTENDS
 * EmptyLanguage, so the latter is true either way.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownEditorSyntaxHighlightingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun hoistedEditor(): CodeEditor = CodeEditor(ApplicationProvider.getApplicationContext())

    /** The control: the default still highlights, so the checks below can tell the two apart. */
    @Test
    fun highlightingIsOnByDefault() {
        val hoisted = hoistedEditor()
        compose.setContent {
            MarkdownEditor(content = "# Title\n\nText\n", onContentChange = {}, codeEditorInstance = hoisted)
        }
        compose.waitForIdle()
        assertTrue(hoisted.editorLanguage is TextMateLanguage)
    }

    @Test
    fun withoutHighlightingTheEditorHasNoAnalyzerAndTypingStillReachesTheCaller() {
        val hoisted = hoistedEditor()
        var received: String? = null
        compose.setContent {
            MarkdownEditor(
                content = "# Title\n\nText\n",
                onContentChange = { received = it },
                codeEditorInstance = hoisted,
                syntaxHighlighting = false,
            )
        }
        compose.waitForIdle()
        assertFalse(hoisted.editorLanguage is TextMateLanguage)

        compose.runOnUiThread { hoisted.text.insert(0, 0, "x") }
        compose.waitForIdle()
        assertEquals("x# Title\n\nText\n", received)
    }

    /**
     * The hoisted instance outlives a document: a highlighted one, then a large one, must not leave
     * the first document's TextMate language on the editor that now holds the large text.
     */
    @Test
    fun aHoistedEditorDropsThePreviousDocumentsHighlighter() {
        val hoisted = hoistedEditor()
        var highlighted by mutableStateOf(true)
        compose.setContent {
            key(highlighted) {
                MarkdownEditor(
                    content = if (highlighted) "# small\n" else "# large\n",
                    onContentChange = {},
                    codeEditorInstance = hoisted,
                    syntaxHighlighting = highlighted,
                )
            }
        }
        compose.waitForIdle()
        assertTrue(hoisted.editorLanguage is TextMateLanguage)

        highlighted = false
        compose.waitForIdle()
        assertFalse(hoisted.editorLanguage is TextMateLanguage)
        assertEquals("# large\n", hoisted.text.toString())
    }
}
