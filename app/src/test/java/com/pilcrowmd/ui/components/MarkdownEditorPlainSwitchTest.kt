// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.testing.withRealSize
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * M-31: a `.md` open in the editor and then a `.txt` kept its Markdown colouring.
 * Composes the real [MarkdownEditor] on the hoisted editor the app shares across documents and
 * replays the order that failed (Markdown first, then plain text), instead of calling
 * [applyEditorLanguage] directly.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownEditorPlainSwitchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private companion object {
        const val ANALYSIS_HEAD_START_MS = 500L
    }

    private val markdown = "# Heading\n\n- **bold** item\n"
    private val plain = "# not a heading\n\n- **not bold** item\n"

    // onCursorChange runs on the main thread right after the update path's setText. Holding it up
    // gives the Markdown analysis of the new text time to finish, which is what a real document's
    // layout work does; the test must not depend on that race going one way.
    private fun setContent(editor: CodeEditor, content: () -> String, plainText: () -> Boolean) {
        compose.setContent {
            MarkdownEditor(
                content = content(),
                onContentChange = {},
                plainText = plainText(),
                onCursorChange = { Thread.sleep(ANALYSIS_HEAD_START_MS) },
                codeEditorInstance = editor,
            )
        }
        compose.waitForIdle()
    }

    private fun awaitMarkdownStyles(editor: CodeEditor) {
        val deadline = System.currentTimeMillis() + 30_000
        while (editor.styles == null && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
        assertNotNull("precondition: the Markdown grammar must have produced styles", editor.styles)
        assertTrue("precondition: Markdown styles carry several colours", foregroundIds(editor).size > 1)
    }

    // Every foreground colour id the editor would draw: styles of a plain-text document hold no
    // token colour, so at most the one normal-text id may remain.
    private fun foregroundIds(editor: CodeEditor): Set<Int> {
        val styles = editor.styles ?: return emptySet()
        val reader = styles.spans.read()
        val ids = mutableSetOf<Int>()
        for (line in 0 until styles.spans.lineCount) {
            reader.moveToLine(line)
            for (i in 0 until reader.spanCount) ids += reader.getSpanAt(i).foregroundColorId
        }
        return ids
    }

    private fun assertPlain(editor: CodeEditor) {
        assertEquals(EmptyLanguage::class.java, editor.editorLanguage.javaClass)
        val ids = foregroundIds(editor)
        assertTrue("token colours survive on a plain-text document: $ids", ids.size <= 1)
    }

    // Both the content and the kind change in one recomposition.
    @Test
    fun plainTextOpenedAfterMarkdownLosesTheMarkdownColouring() {
        val editor = CodeEditor(ApplicationProvider.getApplicationContext()).withRealSize()
        var content by mutableStateOf(markdown)
        var plainText by mutableStateOf(false)
        setContent(editor, { content }, { plainText })
        awaitMarkdownStyles(editor)

        content = plain
        plainText = true
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()

        assertPlain(editor)
    }

    // The hoisted editor outlives its node: a node recreated for a .txt (back from the reader, say)
    // runs the factory on an editor that still holds the previous Markdown document.
    @Test
    fun plainTextOpenedByTheFactoryOnAMarkdownEditorLosesTheMarkdownColouring() {
        // The factory sets the text size after the text and before the language; holding that
        // step up gives the Markdown analysis of the new text time to finish (see setContent).
        val editor = object : CodeEditor(ApplicationProvider.getApplicationContext()) {
            override fun setTextSize(size: Float) {
                Thread.sleep(ANALYSIS_HEAD_START_MS)
                super.setTextSize(size)
            }
        }.withRealSize()
        var content by mutableStateOf(markdown)
        var plainText by mutableStateOf(false)
        var generation by mutableStateOf(0)
        compose.setContent {
            androidx.compose.runtime.key(generation) {
                MarkdownEditor(
                    content = content,
                    onContentChange = {},
                    plainText = plainText,
                    codeEditorInstance = editor,
                )
            }
        }
        compose.waitForIdle()
        awaitMarkdownStyles(editor)

        content = plain
        plainText = true
        generation = 1 // the node is recreated, so the factory runs on the Markdown editor
        compose.waitForIdle()
        ShadowLooper.idleMainLooper()

        assertPlain(editor)
    }
}
