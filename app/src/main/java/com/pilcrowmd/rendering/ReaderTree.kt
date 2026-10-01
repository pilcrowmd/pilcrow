// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.domain.markdown.TooDeepDocument
import io.noties.markwon.Markwon
import org.commonmark.node.Node

/** The tree the reader paints for [content] — shared by the preview and the PDF export. */
object ReaderTree {

    /**
     * [plain]: the verbatim chunk tree, no parser. Otherwise the parsed tree with the shared post-parse
     * passes — or, for a document nested past `NestingLimit.MAX_DEPTH`, the same verbatim chunk tree,
     * because rendering it as Markdown would overflow the stack (NEW-11, Safeguard 3).
     */
    fun build(markwon: Markwon, content: String, plain: Boolean): Node {
        if (plain) return PlainTextBlocks.build(content)
        val parsed = markwon.parse(content)
        if (parsed is TooDeepDocument) return PlainTextBlocks.build(content)
        return ReaderDocument.transform(parsed)
    }
}
