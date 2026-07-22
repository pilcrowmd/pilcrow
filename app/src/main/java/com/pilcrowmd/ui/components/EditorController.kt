// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.util.Log
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * EditorController: thin wrapper around Sora CodeEditor.
 * Encapsulates editor internals (text.indexer, setSelectionRegion, setSelection, ensureSelectionVisible)
 * behind intent-level operations (selectAndRevealMatch, scrollToHeading).
 *
 * Screen-scoped (created via remember in MainScreen; dies on mode toggle if not hoisted, but hoisting
 * is handled upstream). Does not hold state — all mutable state stays in ViewModel/MainScreen.
 *
 * Rationale (UI business-logic cleanup): Composables must remain passive. LaunchedEffect bodies
 * that compute offsets, call editor internals, and manage selection are business logic and belong
 * in a layer above the Composable. EditorController is that layer: it lives as a UI-control helper
 * (not domain logic, not a ViewModel), wrapping Sora's API in intent-level methods.
 *
 * All internal editor calls are wrapped in try/catch for graceful degradation (Safeguard 3);
 * on failure, we log and do nothing (no crash, no retry, no user-facing error).
 */
class EditorController(private val editor: CodeEditor?) {

    /**
     * Select a text range and scroll it into view.
     * Used for search-match navigation.
     *
     * @param startOffset Character offset of the match start (0-indexed into content string)
     * @param endOffset Character offset of the match end (exclusive)
     * @param contentLength Total length of the document (for bounds checking)
     */
    fun selectAndRevealMatch(startOffset: Int, endOffset: Int, contentLength: Int) {
        if (editor == null) return
        try {
            // Clamp offsets to content bounds
            val clampedStart = startOffset.coerceIn(0, contentLength)
            val clampedEnd = endOffset.coerceIn(0, contentLength)

            // Convert character offsets to line/column positions using Sora's indexer.
            // The indexer handles soft-wrapped content correctly (visual lines != logical lines).
            val startPos = editor.text.indexer.getCharPosition(clampedStart)
            val endPos = editor.text.indexer.getCharPosition(clampedEnd)

            // Set selection region and ensure it's visible in the viewport.
            editor.setSelectionRegion(startPos.line, startPos.column, endPos.line, endPos.column)
            editor.ensureSelectionVisible()
        } catch (e: Exception) {
            Log.w("EditorController", "Failed to select and reveal match: ${e.message}")
            // Graceful degradation: do nothing on error (no crash, no retry)
        }
    }

    /**
     * Jump to a heading in the editor and scroll it into view.
     * Used for TOC (table of contents) navigation.
     *
     * FRAGILE: Uses string matching to find the heading in the source
     * (`"#".repeat(level) + " " + text`). This can match the wrong occurrence
     * if a heading's text appears multiple times at the same level. This is a
     * known limitation. Do NOT fix or change
     * the matching logic here — preserve exact behavior.
     *
     * @param headingLevel The heading level (1-6, i.e., number of # symbols)
     * @param headingText The heading text (e.g., "Introduction")
     * @param content The full document content (to search for the heading marker)
     * @param contentLength Total length of the document (for bounds checking)
     */
    fun scrollToHeading(headingLevel: Int, headingText: String, content: String, contentLength: Int) {
        if (editor == null) return
        try {
            // Compute the heading marker: the exact string to search for.
            // This is the fragile bit: simple indexOf will match the first occurrence,
            // which may be wrong if the same text appears elsewhere at the same level.
            // Preserve this behavior exactly (do NOT fix) — known limitation.
            val headingMarker = "#".repeat(headingLevel) + " " + headingText
            val offset = content.indexOf(headingMarker)

            if (offset >= 0) {
                val clampedOffset = offset.coerceIn(0, contentLength)

                // Convert character offset to line/column using Sora's indexer.
                val pos = editor.text.indexer.getCharPosition(clampedOffset)
                editor.setSelection(pos.line, pos.column)

                // Ensure selection is visible. ensureSelectionVisible performs a minimum scroll,
                // which may land the heading at the bottom of the viewport if jumping forward,
                // or at the top if jumping backward. This is the current behavior and is preserved.
                // NOTE: Forcing the heading to ALWAYS land at the very top would require Sora's
                // EditorScroller API and is deferred as minor polish (DEFERRED).
                editor.ensureSelectionVisible()
            }
        } catch (e: Exception) {
            Log.w("EditorController", "Failed to scroll to heading: ${e.message}")
            // Graceful degradation: do nothing on error (no crash, no retry)
        }
    }
}
