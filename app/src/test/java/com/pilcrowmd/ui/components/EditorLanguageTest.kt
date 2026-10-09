// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.theme.DarkColorScheme
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-31: a `.txt` is shown in the editor as plain text, a Markdown document keeps the
 * Markdown grammar. Driven through the same two functions `MarkdownEditor` uses (the factory's
 * [setupTextMateHighlighting] and the update path's [applyEditorLanguage]) on a bare [CodeEditor],
 * because composing the editor with word wrap can spin Sora's layout under Robolectric.
 *
 * The editor instance is hoisted and shared across documents, so the switch must flip the
 * language every time, in both directions, without replacing the theme's colour scheme.
 */
@RunWith(RobolectricTestRunner::class)
class EditorLanguageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Starts from a Markdown editor: a new CodeEditor already has EmptyLanguage, so a plain-text
    // factory run on a fresh editor could not tell a fix from no fix.
    @Test
    fun factoryForAPlainTextDocumentReplacesTheMarkdownGrammar() {
        val editor = CodeEditor(context)
        setupTextMateHighlighting(context, editor, ThemeMode.DARK, DarkColorScheme, plainText = false)
        assertMarkdown(editor)

        setupTextMateHighlighting(context, editor, ThemeMode.DARK, DarkColorScheme, plainText = true)

        assertPlain(editor)
    }

    // The factory's fallback when TextMate setup fails: a plain EditorColorScheme. The update path
    // must not install the grammar on that editor, or retry it on every keystroke.
    @Test
    fun markdownIsNotInstalledWhenTheSchemeIsNotTextMate() {
        val editor = CodeEditor(context)
        // Loads the grammar registry, so only the scheme check can stop the install below.
        setupTextMateHighlighting(context, editor, ThemeMode.DARK, DarkColorScheme, plainText = true)
        editor.colorScheme = EditorColorScheme()

        applyEditorLanguage(editor, plainText = false)

        assertPlain(editor)
    }

    @Test
    fun switchingBetweenMarkdownAndPlainTextFlipsTheLanguageEachTime() {
        val editor = CodeEditor(context)
        setupTextMateHighlighting(context, editor, ThemeMode.DARK, DarkColorScheme, plainText = false)
        val scheme = editor.colorScheme
        assertMarkdown(editor)

        applyEditorLanguage(editor, plainText = true)
        assertPlain(editor)
        assertSame("plain text must keep the theme's colour scheme", scheme, editor.colorScheme)

        applyEditorLanguage(editor, plainText = false)
        assertMarkdown(editor)

        applyEditorLanguage(editor, plainText = true)
        assertPlain(editor)
        assertSame("plain text must keep the theme's colour scheme", scheme, editor.colorScheme)
    }

    // TextMateLanguage EXTENDS EmptyLanguage, so `is EmptyLanguage` alone passes for the Markdown
    // grammar too; the exact class is what tells plain text apart.
    private fun assertPlain(editor: CodeEditor) {
        val language = editor.editorLanguage
        assertTrue(
            "expected EmptyLanguage, was ${language.javaClass.name}",
            language.javaClass == EmptyLanguage::class.java,
        )
    }

    private fun assertMarkdown(editor: CodeEditor) {
        val language = editor.editorLanguage
        assertTrue("expected TextMateLanguage, was ${language.javaClass.name}", language is TextMateLanguage)
    }
}
