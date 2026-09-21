// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Node
import org.commonmark.node.OrderedList

/**
 * Keeps a numbered list's numbers stable when the SAME parsed node is rendered more than once
 * (M-119).
 *
 * **The defect this exists for.** Markwon 4.6.2's `CorePlugin` `ListItem` visitor advances its
 * parent list's counter *while rendering it* — `setStartNumber(getStartNumber() + 1)`, once per
 * item (confirmed in `CorePlugin$9.class`, offsets 81-86). For `Markwon.setMarkdown()` that is
 * harmless: it re-parses on every call and discards the tree. The reader does not work that way —
 * `MarkwonAdapter` **holds the parsed nodes and re-renders the same `Node` objects on every bind**,
 * so a list written `1.` `2.` was observed on-device as `3.` `4.`, then `9.` `10.`, climbing by one
 * per item per re-bind. Anything that re-binds advances it: scrolling the list back into view, the
 * end-of-gesture adapter rebuild, a PDF export.
 *
 * **Why a plugin rather than a fix at the call sites.** There are five `markwon.render(node)` call
 * sites today and nothing stops a sixth being added; a guarantee that lives in the callers is one
 * a future caller can omit silently. `beforeRender`/`afterRender` run for **every** render on this
 * instance — the reader's and the PDF export's alike — so the invariant holds by construction.
 *
 * **Why a `ThreadLocal` and not a field.** The one `Markwon` instance is shared: the reader renders
 * on the main thread while a PDF export renders on a background one. A plain mutable field here
 * would be exactly the ambient cross-thread state that M-135 rules out (constraint C3). The
 * snapshot is confined to the thread doing the render, taken and released inside a single
 * `render()` call.
 *
 * The snapshot restores the value that was there, rather than subtracting the item count. Both
 * work, but subtracting encodes an assumption about *how much* the library increments, which is a
 * detail of a dependency we do not control; restoring what we saw does not.
 *
 * **One slot, not a stack — and that is a decision, not an oversight.** A single slot is wrong if
 * a render can begin while another is still running **on the same thread**, because the inner
 * `beforeRender` would clobber the outer's snapshot and the outer list would never be restored —
 * the very bug this class exists to prevent. **Verified that cannot happen here:** no plugin,
 * visitor or inline processor in `app/src/main` calls `render` or `toMarkdown`, and all five
 * `markwon.render(node)` call sites are reached from a `bindHolder`, sequentially — including
 * `TableBlockEntry.renderCell`, which loops cells one after another rather than nesting.
 *
 * A stack would survive nesting, and it would trade a **bounded** leak for an **unbounded** one:
 * Markwon has no `try`/`finally` around `node.accept(visitor)`, so if a visitor throws,
 * `afterRender` never runs and that frame is stranded. With one slot the next `beforeRender`
 * overwrites it, so at most one stale snapshot per thread ever exists. With a stack they
 * accumulate. Given nesting is unreachable, one slot is bounded where a stack is not.
 *
 * **If a nested render is ever introduced, this breaks silently** — so [OrderedListRenumberTest]
 * pins the sequential contract that makes it safe.
 */
internal class OrderedListRebindPlugin : AbstractMarkwonPlugin() {

    private val snapshot = ThreadLocal<List<Pair<OrderedList, Int>>>()

    override fun beforeRender(node: Node) {
        val saved = mutableListOf<Pair<OrderedList, Int>>()
        node.accept(
            object : AbstractVisitor() {
                override fun visit(orderedList: OrderedList) {
                    saved.add(orderedList to orderedList.startNumber)
                    visitChildren(orderedList) // nested lists are separate instances of the defect
                }
            },
        )
        // Overwrite unconditionally. Markwon has no try/finally around the visit, so a throwing
        // visitor strands this frame; overwriting bounds that at one stale snapshot per thread
        // instead of letting them accumulate. Safe only because renders never nest — see the KDoc.
        snapshot.set(saved)
    }

    override fun afterRender(node: Node, visitor: MarkwonVisitor) {
        snapshot.get()?.forEach { (list, startNumber) -> list.startNumber = startNumber }
        snapshot.remove()
    }
}
