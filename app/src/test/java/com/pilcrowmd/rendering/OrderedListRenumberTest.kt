// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.OrderedListItemSpan
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-119 — a numbered list must render the same numbers every time the SAME parsed node is
 * rendered.
 *
 * **Why this renders twice, and why one render can never catch the defect.** Markwon 4.6.2's
 * `CorePlugin` `ListItem` visitor does `setStartNumber(getStartNumber() + 1)` on the `OrderedList`
 * node *while rendering it*. `Markwon.setMarkdown()` re-parses on every call and throws the tree
 * away, so the mutation is invisible there. `MarkwonAdapter` — how the reader draws every block —
 * **keeps the parsed nodes and re-renders the same `Node` objects on every bind**, so each re-bind
 * advances the counter by one per list item. **The first render is always correct; the bug lives
 * entirely in the second.** That is why 97 goldens and 400+ unit tests, every one of which renders
 * once, missed a defect visible on the first document anyone opens.
 *
 * **Why it asserts on spans and not on text — a first version of this test was GREEN against the
 * broken code.** Markwon does not put the number into the rendered text: `render(node).toString()`
 * is `"alpha\nbeta"`, with no digits anywhere. A text comparison therefore compares two strings
 * that never contained the thing under test, and passes whatever the numbering does. The number
 * exists only inside [OrderedListItemSpan]'s private `number` field, so that is what this reads.
 *
 * Seen failing against `main` before the fix landed — `2./3.` on the second render, `3./4.` on the
 * third — per the standing rule that a guard never watched failing is a guard taken on faith.
 */
@RunWith(RobolectricTestRunner::class)
class OrderedListRenumberTest {

    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    /** The top-level ordered list of a parsed document — the node the adapter holds and re-binds. */
    private fun orderedListNode(source: String): Node = markwon.parse(source).firstChild

    /**
     * The list markers as actually rendered, in document order. Read by reflection because
     * [OrderedListItemSpan] exposes no getter for its `number` — and the rendered text does not
     * contain the number at all, so there is nowhere else to read it from.
     */
    private fun renderedNumbers(node: Node): List<String> {
        val spanned = markwon.render(node) as Spanned
        val field = OrderedListItemSpan::class.java.getDeclaredField("number").apply { isAccessible = true }
        return spanned.getSpans(0, spanned.length, OrderedListItemSpan::class.java)
            .sortedBy { spanned.getSpanStart(it) }
            .map { (field.get(it) as String).trim() }
    }

    @Test
    fun sameOrderedListNodeRenderedThreeTimesKeepsItsNumbers() {
        val list = orderedListNode("1. alpha\n2. beta\n")

        val first = renderedNumbers(list)
        val second = renderedNumbers(list)
        val third = renderedNumbers(list)

        assertEquals("the first render must be right", listOf("1.", "2."), first)
        assertEquals("the second render must equal the first", first, second)
        assertEquals("the third render must equal the first", first, third)
    }

    @Test
    fun explicitStartNumberIsHonouredAndPreservedAcrossRebinds() {
        // Content fidelity: a list written `5.` keeps starting at 5 — the fix must not reset to 1.
        val list = orderedListNode("5. five\n6. six\n")

        val first = renderedNumbers(list)
        val second = renderedNumbers(list)

        assertEquals("the source's start number must be honoured", listOf("5.", "6."), first)
        assertEquals("the second render must equal the first", first, second)
    }

    @Test
    fun sequentialRendersOfDifferentNodesDoNotInterfere() {
        // Pins the contract that makes OrderedListRebindPlugin's SINGLE-SLOT snapshot safe: renders
        // run one after another, never nested. TableBlockEntry renders each cell in a loop, which is
        // this shape. If a nested render is ever introduced the single slot silently stops
        // restoring the outer list — this test is where that contract is written down.
        val first = orderedListNode("1. alpha\n2. beta\n")
        val second = orderedListNode("7. seven\n8. eight\n")

        // Interleave them the way a bind loop would.
        renderedNumbers(first)
        renderedNumbers(second)

        assertEquals(
            "the first list must be unaffected by the second render",
            listOf("1.", "2."),
            renderedNumbers(first),
        )
        assertEquals(
            "the second list must be unaffected by the first",
            listOf("7.", "8."),
            renderedNumbers(second),
        )
    }

    @Test
    fun nestedOrderedListsAlsoSurviveARebind() {
        // The visitor mutates every OrderedList it walks, so a nested list is a second instance of
        // the same defect inside one bind.
        val list = orderedListNode("1. outer\n    1. inner-a\n    2. inner-b\n2. outer-two\n")

        val first = renderedNumbers(list)
        val second = renderedNumbers(list)

        assertEquals("the second render must equal the first", first, second)
        assertEquals("every marker must still be present", first.size, second.size)
    }
}
