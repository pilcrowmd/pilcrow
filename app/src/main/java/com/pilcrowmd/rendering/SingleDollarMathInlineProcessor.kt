// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.InlineMathDelimiters
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.inlineparser.InlineProcessor
import org.commonmark.node.Node

/**
 * Inline single-`$...$` LaTeX math (currency-aware; renders via the shared JLatexMath path).
 * The library's own inline processor matches `$$...$$` only; this one adds `$...$`, emitting the
 * library's public [JLatexMathNode] so the existing JLatexMathPlugin visitor renders it into the same
 * `JLatexAsyncDrawableSpan` the preview paints and the PDF sync-resolver walks - no new
 * render/PDF code.
 *
 * Parse-time only: it consumes from the inline parse stream and emits a node; it never mutates the
 * document text, so the editor's saved source is untouched (Safeguard 2) and source-rendered char
 * offsets stay aligned (search/editor parity).
 *
 * The single-`$` opener/closer rules live in [InlineMathDelimiters] (pure, shared with the search use
 * case so search and render agree on what is math). `\$` is consumed upstream by CommonMark's
 * backslash-escape processor, so it never reaches here and renders as a literal `$`. A `$$` is declined
 * (the rule returns null) so the library's display processor handles it.
 */
class SingleDollarMathInlineProcessor : InlineProcessor() {

    override fun specialCharacter(): Char = '$'

    override fun parse(): Node? {
        val closer = InlineMathDelimiters.singleDollarCloser(input, index) ?: return null
        val node = JLatexMathNode()
        node.latex(input.substring(index + 1, closer))
        index = closer + 1 // consume through the closing `$`
        return node
    }
}
