// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.text.Spanned
import androidx.test.core.app.ApplicationProvider
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import org.commonmark.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-232: a numbered list draws its own delimiter, so `1)` stays `1)` instead of becoming `1.`.
 *
 * The number is not in the rendered text; it lives only in [OrderedListItemSpan]'s private
 * `number` field, so it is read by reflection, as in [OrderedListRenumberTest].
 */
@RunWith(RobolectricTestRunner::class)
class OrderedListDelimiterTest {

    private lateinit var markwon: Markwon

    @Before
    fun setup() {
        markwon = buildPilcrowMarkwon(ApplicationProvider.getApplicationContext())
    }

    private fun firstBlock(source: String): Node = markwon.parse(source).firstChild

    private fun renderedNumbers(node: Node): List<String> {
        val spanned = markwon.render(node) as Spanned
        val field = OrderedListItemSpan::class.java.getDeclaredField("number").apply { isAccessible = true }
        return spanned.getSpans(0, spanned.length, OrderedListItemSpan::class.java)
            .sortedBy { spanned.getSpanStart(it) }
            .map { (field.get(it) as String).trim() }
    }

    @Test
    fun parenthesisListDrawsParenthesis() {
        assertEquals(listOf("1)", "2)"), renderedNumbers(firstBlock("1) one\n2) two\n")))
    }

    @Test
    fun periodListStillDrawsPeriod() {
        assertEquals(listOf("1.", "2."), renderedNumbers(firstBlock("1. one\n2. two\n")))
    }

    @Test
    fun nestedParenthesisInsidePeriodKeepsEachDelimiter() {
        val list = firstBlock("1. outer\n   1) inner-a\n   2) inner-b\n2. outer-two\n")

        assertEquals(listOf("1.", "1)", "2)", "2."), renderedNumbers(list))
    }

    @Test
    fun nestedPeriodInsideParenthesisKeepsEachDelimiter() {
        val list = firstBlock("1) outer\n   1. inner-a\n   2. inner-b\n2) outer-two\n")

        assertEquals(listOf("1)", "1.", "2.", "2)"), renderedNumbers(list))
    }

    @Test
    fun parenthesisListDoesNotClimbOnRerender() {
        val list = firstBlock("3) three\n4) four\n")

        assertEquals(listOf("3)", "4)"), renderedNumbers(list))
        assertEquals(listOf("3)", "4)"), renderedNumbers(list))
    }

    @Test
    fun bulletListIsStillDrawnWithBullets() {
        val spanned = markwon.render(firstBlock("- a\n- b\n")) as Spanned

        assertEquals(2, spanned.getSpans(0, spanned.length, BulletListItemSpan::class.java).size)
        assertEquals(0, spanned.getSpans(0, spanned.length, OrderedListItemSpan::class.java).size)
    }
}
