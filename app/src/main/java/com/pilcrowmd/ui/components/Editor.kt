// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.ui.theme.FontSet
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PilcrowTypography
import com.pilcrowmd.ui.theme.mdColors
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeAlways

/**
 * Native markdown editor using Sora CodeEditor library (LGPL-2.1, native Android).
 *
 * Replaces hand-rolled native EditText to fix:
 * - Large-file editor drag-lag (Sora handles virtualization natively)
 * - Undo/redo support (Sora has built-in incremental undo/redo)
 * - Scroll smoothness and line-number gutter correctness
 *
 * Key behaviors preserved:
 * - Markdown syntax highlighting (now via TextMate grammar, native)
 * - Line-number gutter (built-in to Sora, auto-aligns with soft wrap)
 * - Soft-wrap (no horizontal scroll via setWordwrap(true))
 * - Scroll preservation
 * - Cursor preservation across mode toggles
 * - Round-trip fidelity (Safeguard 2: CRLF preserved via ViewModel)
 *
 * Undo/redo:
 * - Sora's CodeEditor has native incremental undo/redo (canUndo/redo, undo/redo methods)
 * - Stack persists if the same CodeEditor instance is retained across mode toggles
 * - Hoisted to MainScreen level (via remember) and passed to MarkdownEditor
 * - Toolbar undo/redo buttons call onUndo()/onRedo() callbacks
 *
 * Font scaling:
 * - fontScale multiplier applied to 14sp editor base
 * - Applied to editor.textSize (in pixels via density conversion)
 * - JetBrains Mono typeface set via setTypefaceText
 */
// Horizontal gap (dp) on each side of the line-number divider. Right (divider→content) gives the
// caret a reliable column-0 target; left (digits→divider) keeps the numbers off the divider line.
private const val GUTTER_DIVIDER_MARGIN_LEFT_DP = 6f
private const val GUTTER_DIVIDER_MARGIN_RIGHT_DP = 8f

@Composable
fun MarkdownEditor(
    modifier: Modifier = Modifier,
    content: String,
    onContentChange: (String) -> Unit,
    lineNumbersEnabled: Boolean = true,
    fontScale: Float = 1.0f,
    fontSet: FontSet = FontSets.DEFAULT,
    themeMode: ThemeMode = ThemeMode.DARK,
    scrollPosition: Int = 0,
    onScrollChanged: (Int) -> Unit = {},
    initialCursor: Int = 0,
    onCursorChange: (Int) -> Unit = {},
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    codeEditorInstance: CodeEditor? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Sora's setTextSize(float) takes SP (it applies display density internally), so pass sp directly.
    // (Passing px here double-applied density and made the text far too large.)
    // Base 13sp (intentionally smaller than the 14sp design baseline); fontScale
    // scales it live in the 0.85-1.6 range.
    val editorFontSizeSp = PilcrowTypography.EDITOR_BASE_FONT_SIZE_SP * fontScale

    // Reference to the native Sora CodeEditor for updates
    var codeEditor by remember { mutableStateOf<CodeEditor?>(null) }
    var lastAppliedFontSizeSp by remember { mutableStateOf(0f) }
    var suppressContentChange by remember { mutableStateOf(false) }

    // Keep the latest callbacks so the (long-lived) event subscriptions always call current lambdas.
    val currentOnContentChange by rememberUpdatedState(onContentChange)
    val currentOnCursorChange by rememberUpdatedState(onCursorChange)

    // CRITICAL: wire Sora's content + selection events back to the ViewModel so edits actually
    // propagate (mark dirty / save). Subscribe per editor instance and unsubscribe on dispose to
    // avoid duplicate callbacks across Reader<->Editor mode toggles (the editor instance is hoisted).
    // The ViewModel's dirty-baseline (dirty = content != originalContent) guards against a
    // programmatic setText being treated as a user edit; suppressContentChange is a second guard.
    DisposableEffect(codeEditor) {
        val editor = codeEditor
        val receipts = mutableListOf<SubscriptionReceipt<*>>()
        if (editor != null) {
            receipts += editor.subscribeAlways<ContentChangeEvent> {
                if (!suppressContentChange) {
                    currentOnContentChange(editor.text.toString())
                }
            }
            receipts += editor.subscribeAlways<SelectionChangeEvent> { event ->
                if (!suppressContentChange) {
                    try {
                        val idx = editor.text.indexer.getCharIndex(event.left.line, event.left.column)
                        currentOnCursorChange(idx)
                    } catch (e: Exception) {
                        Log.w("MarkdownEditor", "cursor index failed: ${e.message}")
                    }
                }
            }
        }
        onDispose { receipts.forEach { it.unsubscribe() } }
    }

    val c = mdColors()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.primaryBackground)
            .imePadding(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // Use hoisted CodeEditor instance to retain undo stack across mode toggles.
                // If codeEditorInstance is provided (from MainScreen-level remember), reuse it.
                // Otherwise, create a new one (fallback for dev/testing).
                val editor = codeEditorInstance ?: CodeEditor(context)

                try {
                    // Set initial content
                    editor.setText(content)

                    // Configure editor appearance and behavior
                    editor.setTextSize(editorFontSizeSp)
                    editor.setWordwrap(true) // Soft wrap, no horizontal scroll
                    editor.isLineNumberEnabled = lineNumbersEnabled

                    // Sora's default divider margins are too tight — the content text sits flush
                    // against the gutter divider, so a tap at the line start can't land the caret at
                    // column 0. Add a clear horizontal gap on both sides of the divider (left = gutter
                    // digits→divider, right = divider→content text). Direct editor property (not
                    // TextMate-theme-driven), so this single factory call is durable across themes.
                    val gutterDensity = context.resources.displayMetrics.density
                    editor.setDividerMargin(
                        GUTTER_DIVIDER_MARGIN_LEFT_DP * gutterDensity,
                        GUTTER_DIVIDER_MARGIN_RIGHT_DP * gutterDensity,
                    )

                    // Set the selected font set's monospace typeface
                    try {
                        val typeface = ResourcesCompat.getFont(context, fontSet.monoRegular)
                        if (typeface != null) {
                            editor.setTypefaceText(typeface)
                        }
                    } catch (e: Exception) {
                        Log.w("MarkdownEditor", "Failed to load JetBrains Mono font", e)
                    }

                    // Set TextMate color scheme for syntax highlighting
                    try {
                        setupTextMateHighlighting(context, editor, themeMode, c)
                    } catch (e: Exception) {
                        Log.w("MarkdownEditor", "TextMate highlighting setup failed, using default colors", e)
                        // Fallback: use a plain color scheme without highlighting (Safeguard 3)
                        editor.colorScheme = createFallbackColorScheme(c)
                    }

                    // Restore initial cursor position
                    editor.post {
                        try {
                            val clampedCursor = initialCursor.coerceIn(0, editor.text.length)
                            val pos = editor.text.indexer.getCharPosition(clampedCursor)
                            editor.setSelection(pos.line, pos.column)
                            // Ensure selection is visible
                            editor.ensureSelectionVisible()
                        } catch (e: Exception) {
                            Log.w("MarkdownEditor", "Failed to restore initial cursor", e)
                        }
                    }

                    codeEditor = editor
                    editor
                } catch (e: Exception) {
                    Log.e("MarkdownEditor", "CodeEditor init failed: ${e.message}", e)
                    throw e
                }
            },
            update = { editor ->
                try {
                    // Update editor content only if it changed upstream (not on every recomposition)
                    if (editor.text.toString() != content) {
                        suppressContentChange = true
                        editor.setText(content)
                        suppressContentChange = false

                        // Restore cursor position after setText
                        try {
                            val clampedCursor = initialCursor.coerceIn(0, content.length)
                            val pos = editor.text.indexer.getCharPosition(clampedCursor)
                            editor.setSelection(pos.line, pos.column)
                            onCursorChange(clampedCursor)
                        } catch (e: Exception) {
                            Log.w("MarkdownEditor", "Failed to restore cursor after setText", e)
                        }
                    }

                    // Update font size only when it changes (avoids spurious relayouts)
                    if (kotlin.math.abs(lastAppliedFontSizeSp - editorFontSizeSp) > 0.1f) {
                        editor.setTextSize(editorFontSizeSp)
                        lastAppliedFontSizeSp = editorFontSizeSp
                    }

                    // Update line numbers visibility
                    editor.isLineNumberEnabled = lineNumbersEnabled
                } catch (e: Exception) {
                    Log.e("MarkdownEditor", "update failed: ${e.message}", e)
                }
            },
        )
    }
}

/**
 * Setup TextMate syntax highlighting for markdown.
 * Initializes the TextMate registry, grammar, and theme once per app.
 * Loads md-dark.json or md-light.json based on themeMode.
 */
private fun setupTextMateHighlighting(
    context: Context,
    editor: CodeEditor,
    themeMode: ThemeMode = ThemeMode.DARK,
    colorScheme: PilcrowColorScheme,
) {
    try {
        // Initialize file provider for TextMate assets
        val fileProvider = io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry.getInstance()
        val assetsFileResolver = io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver(
            context.assets,
        )
        fileProvider.addFileProvider(assetsFileResolver)

        // Load the appropriate TextMate theme (dark or light) based on themeMode
        val themeRegistry = io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()
        val themeName = when (themeMode) {
            ThemeMode.DARK -> "md-dark"
            ThemeMode.LIGHT -> "md-light"
        }
        val themeFileName = "$themeName.json"
        val themeInputStream = fileProvider.tryGetInputStream("textmate/$themeFileName")
        if (themeInputStream != null) {
            val themeModel = io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel(
                org.eclipse.tm4e.core.registry.IThemeSource.fromInputStream(
                    themeInputStream,
                    themeFileName,
                    null,
                ),
                themeName,
            )
            themeRegistry.loadTheme(themeModel)
        }

        // Load the markdown grammar
        val grammarRegistry = io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry.getInstance()
        grammarRegistry.loadGrammars(
            io.github.rosemoe.sora.langs.textmate.registry.dsl.languages {
                language("text.html.markdown") {
                    grammar = "textmate/markdown/markdown.tmLanguage.json"
                    // NOTE: do NOT call defaultScopeName() — it rewrites the scope to
                    // "source.text.html.markdown", which mismatches the grammar file's declared
                    // scope "text.html.markdown" and makes grammar loading throw (→ no highlighting).
                }
            },
        )

        // Apply the theme and grammar to the editor
        val textMateColorScheme = io.github.rosemoe.sora.langs.textmate.TextMateColorScheme.create(themeRegistry)
        applyEditorTokenColors(textMateColorScheme, colorScheme)
        editor.colorScheme = textMateColorScheme
        editor.dividerWidth = context.resources.displayMetrics.density // ~1dp gutter divider
        editor.setEditorLanguage(
            io.github.rosemoe.sora.langs.textmate.TextMateLanguage.create("text.html.markdown", false),
        )

        Log.d("MarkdownEditor", "TextMate highlighting configured successfully")
    } catch (e: Exception) {
        Log.w("MarkdownEditor", "TextMate highlighting setup failed: ${e.message}", e)
        throw e // Let the caller handle the fallback
    }
}

/**
 * Gutter polish: a divider colour + a subtle background tint so the line-number column reads
 * as a distinct gutter (Safeguard 4 — colours from the token layer, no hardcoded hex). Only keys
 * NOT driven by the TextMate theme are set here; theme-backed colours (foreground, selection,
 * line-number foreground) live in md-dark.json/md-light.json, since `applyVSCTheme`
 * overwrites any setColor for those.
 *
 * CRITICAL: These setColor calls must happen AFTER the TextMate theme is loaded via
 * applyVSCTheme, so they override any theme-backed keys. Non-theme keys like LINE_NUMBER_BACKGROUND
 * will survive, ensuring the gutter is legible on Light.
 */
private fun applyEditorTokenColors(scheme: EditorColorScheme, colorScheme: PilcrowColorScheme) {
    scheme.setColor(EditorColorScheme.LINE_DIVIDER, colorScheme.border.toArgb())
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colorScheme.gutterBg.toArgb())
    // The CURRENT line's number is drawn in the app accent so it stands out from the dim
    // others (pairs with the current-line band from `editor.lineHighlightBackground`). Like the
    // keys above, LINE_NUMBER_CURRENT is not driven by the TextMate VSC theme, so this setColor —
    // run AFTER applyVSCTheme — survives.
    scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colorScheme.accent.toArgb())
}

/**
 * Fallback color scheme for the editor (no syntax highlighting).
 * Used if TextMate setup fails. Colors read from the active colorScheme (Dark or Light).
 */
private fun createFallbackColorScheme(colorScheme: PilcrowColorScheme): EditorColorScheme {
    val scheme = EditorColorScheme()
    // Source colors from the token layer (Safeguard 4) — no hardcoded hex.
    scheme.setColor(
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND,
        colorScheme.primaryBackground.toArgb(),
    )
    scheme.setColor(
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL,
        colorScheme.editorText.toArgb(),
    )
    scheme.setColor(
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER,
        colorScheme.lineNumbers.toArgb(),
    )
    scheme.setColor(
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND,
        colorScheme.gutterBg.toArgb(),
    )
    scheme.setColor(
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTION_INSERT,
        colorScheme.accent.toArgb(),
    )
    return scheme
}
