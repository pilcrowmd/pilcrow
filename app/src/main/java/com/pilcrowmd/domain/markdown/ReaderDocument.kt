// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Node

/**
 * The post-parse passes every reader parse site applies — the renderer, the PDF export, search and
 * the headings drawer — so all four walk the same tree and adapter positions stay 1:1.
 */
object ReaderDocument {
    fun transform(document: Node): Node = Callouts.transform(Footnotes.transform(document))
}
