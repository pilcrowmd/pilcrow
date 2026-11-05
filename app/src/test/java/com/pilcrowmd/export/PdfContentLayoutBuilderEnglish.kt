// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.graphics.Bitmap
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.rendering.CodeHighlighter

/**
 * A [PdfContentLayoutBuilder] with the source-language reader words, for tests. The production
 * constructor takes the words with no default, so no production code can build one that forgets them.
 */
internal fun PdfContentLayoutBuilder(
    context: Context,
    codeHighlighter: CodeHighlighter? = null,
    diagram: (source: String, widthPx: Int) -> Bitmap? = { _, _ -> null },
    prism4jHighlighter: CodeHighlighter? = null,
): PdfContentLayoutBuilder =
    PdfContentLayoutBuilder(context, ReaderWords.ENGLISH, codeHighlighter, diagram, prism4jHighlighter)
