// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node

/**
 * The top-level blocks of a parsed document, numbered the way the reader adapter numbers its items.
 * **M-214.**
 *
 * `MarkwonAdapter` does not take every top-level node: its default reducer,
 * `MarkwonReducer.directChildren()`, drops a link reference definition (`[r]: https://…`), which
 * paints nothing. Every place that turns a block into an adapter position — the contents drawer,
 * search, footnote jumps, `<details>` sections — numbers the list returned here, so a definition
 * takes no position anywhere. `ParseParityTest` checks this list against the reducer's.
 *
 * Plain commonmark, no Markwon, like the rest of this package.
 */
object AdapterBlocks {

    /** [document]'s top-level children, minus link reference definitions; index = adapter position. */
    fun of(document: Node): List<Node> = generateSequence(document.firstChild) { it.next }
        .filterNot { it is LinkReferenceDefinition }
        .toList()
}
