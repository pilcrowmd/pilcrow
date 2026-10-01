// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.parser.PostProcessor

/**
 * What a reader parse returns in place of a tree nested deeper than [NestingLimit.MAX_DEPTH]: an empty
 * document, so nothing downstream has anything to recurse into. The parse sites show the source as
 * plain text instead (see `ReaderTree`). The source itself is untouched (Safeguard 2).
 */
class TooDeepDocument : Document()

/**
 * Safeguard 3 for documents nested so deeply that a recursive walk of the tree overflows the stack
 * (NEW-11). Every walker over a parsed tree recurses per level — commonmark's `AbstractVisitor`,
 * Markwon's render visitor, the task-list post-processor that runs INSIDE `markwon.parse`, the
 * footnote pass and search — and a `StackOverflowError` is an `Error`, which no catch in this app
 * handles. 400 nested `>` overflowed a ~1 MB test thread; 20,000 overflowed the 8 MB main thread.
 *
 * [postProcessor] is registered FIRST on every reader parser, so it runs before any other
 * post-processor can walk the tree.
 */
object NestingLimit {

    /**
     * Deepest node depth a reader will render as Markdown. A top-level block is depth 1, so 100 nested
     * `>` put their text at depth 102. The deepest of 3,733 real `.md` files measured was 24; a tree at
     * this depth renders on a 128 KB stack, and the shallowest overflow seen (CI, ~1 MB) was 402 (NEW-11).
     */
    const val MAX_DEPTH = 100

    /**
     * The depth of the deepest node under [root] (its children are depth 1; a childless root is 0).
     * Iterative — a first-child / next-sibling / parent walk with no stack — so the measurement
     * cannot overflow on the very input it exists to catch.
     */
    fun depth(root: Node): Int {
        var node = root.firstChild ?: return 0
        var depth = 1
        var deepest = 1
        while (true) {
            val child = node.firstChild
            if (child != null) {
                node = child
                depth++
                if (depth > deepest) deepest = depth
                continue
            }
            var current: Node = node
            while (current.next == null) {
                val parent = current.parent
                if (parent == null || parent === root) return deepest
                current = parent
                depth--
            }
            node = current.next
        }
    }

    /** Replaces a tree deeper than [MAX_DEPTH] with a [TooDeepDocument]. */
    val postProcessor = PostProcessor { document ->
        if (depth(document) > MAX_DEPTH) TooDeepDocument() else document
    }
}
