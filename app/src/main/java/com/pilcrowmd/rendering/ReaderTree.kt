// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.TooDeepDocument
import io.noties.markwon.Markwon
import org.commonmark.node.Node

/** The tree the PDF export paints for [content] (the reader takes `PrepareReaderModelUseCase`'s model). */
object ReaderTree {

    /**
     * [plain]: the verbatim chunk tree, no parser. Otherwise the parsed tree with the shared post-parse
     * passes — or, for a document nested past `NestingLimit.MAX_DEPTH`, the same verbatim chunk tree,
     * because rendering it as Markdown would overflow the stack (NEW-11, Safeguard 3). A long paragraph
     * is NOT split here (M-328): the PDF lays its pages out off the main thread and prints it whole.
     */
    fun build(markwon: Markwon, content: String, plain: Boolean): Node {
        if (plain) return PlainTextBlocks.build(content)
        val parsed = markwon.parse(content)
        if (parsed is TooDeepDocument) return PlainTextBlocks.build(content)
        return ReaderDocument.transform(parsed, splitLongParagraphs = false, inlineSource = ::calloutTitleSource)
    }
}
