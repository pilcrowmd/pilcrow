// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import com.pilcrowmd.domain.markdown.ListContinuation.Action.Continue
import com.pilcrowmd.domain.markdown.ListContinuation.Action.EndList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-168: what Enter writes on a list or quote line. Plain strings in, a decision out. */
class ListContinuationTest {

    /** Enter with the cursor at the end of [line]. */
    private fun atEnd(line: String, above: List<String> = emptyList()) =
        ListContinuation.onEnter(line, line.length, above.asSequence())

    @Test
    fun `each bullet continues with the same bullet`() {
        assertEquals(Continue("\n- "), atEnd("- a"))
        assertEquals(Continue("\n* "), atEnd("* a"))
        assertEquals(Continue("\n+ "), atEnd("+ a"))
    }

    @Test
    fun `leading indent is kept verbatim, spaces and tabs`() {
        assertEquals(Continue("\n    - "), atEnd("    - nested"))
        assertEquals(Continue("\n\t* "), atEnd("\t* tabbed"))
        assertEquals(Continue("\n  10. "), atEnd("  9. nine"))
    }

    @Test
    fun `an ordered item continues with the next number and keeps its delimiter`() {
        assertEquals(Continue("\n2. "), atEnd("1. one"))
        assertEquals(Continue("\n10. "), atEnd("9. nine"))
        assertEquals(Continue("\n4) "), atEnd("3) three"))
    }

    @Test
    fun `a task item continues unchecked, whatever its box held`() {
        assertEquals(Continue("\n- [ ] "), atEnd("- [ ] todo"))
        assertEquals(Continue("\n- [ ] "), atEnd("- [x] done"))
        assertEquals(Continue("\n* [ ] "), atEnd("* [X] done"))
        assertEquals(Continue("\n  + [ ] "), atEnd("  + [x] nested"))
    }

    @Test
    fun `a numbered task item continues unchecked with the next number`() {
        assertEquals(Continue("\n2. [ ] "), atEnd("1. [ ] a"))
        assertEquals(Continue("\n2. [ ] "), atEnd("1. [x] a"))
        assertEquals(Continue("\n  5) [ ] "), atEnd("  4) [X] a"))
    }

    @Test
    fun `an empty numbered task item ends the list`() {
        assertEquals(EndList(0, 7), atEnd("3. [ ] "))
        assertEquals(EndList(0, 7), atEnd("3) [x] "))
        assertNull(ListContinuation.onEnter("1. [ ] a", 4, emptySequence()))
    }

    @Test
    fun `an incremented number keeps its leading-zero width`() {
        assertEquals(Continue("\n008. "), atEnd("007. a"))
        assertEquals(Continue("\n10. "), atEnd("09. a"))
        assertEquals(Continue("\n100) "), atEnd("099) a"))
        assertEquals(Continue("\n100. "), atEnd("99. a"))
    }

    @Test
    fun `a quote continues with the same prefix, and a list inside it continues too`() {
        assertEquals(Continue("\n> "), atEnd("> quoted"))
        assertEquals(Continue("\n> > "), atEnd("> > deeper"))
        assertEquals(Continue("\n> - "), atEnd("> - a"))
        assertEquals(Continue("\n> 3. "), atEnd("> 2. b"))
    }

    @Test
    fun `Enter in the middle of the text splits after the new marker`() {
        // The new marker is inserted AT the cursor, so "bar" follows it on the new line.
        assertEquals(Continue("\n- "), ListContinuation.onEnter("- foo bar", 6, emptySequence()))
        assertEquals(Continue("\n2. "), ListContinuation.onEnter("1. foo bar", 3, emptySequence()))
    }

    @Test
    fun `Enter on an empty item removes the whole marker and ends the list`() {
        assertEquals(EndList(0, 2), atEnd("- "))
        assertEquals(EndList(0, 6), atEnd("  10. "))
        assertEquals(EndList(0, 6), atEnd("- [x] "))
        assertEquals(EndList(0, 5), atEnd("- [ ]"))
        assertEquals(EndList(0, 2), atEnd("> "))
        assertEquals(EndList(0, 4), atEnd("> > "))
        assertEquals(EndList(0, 4), atEnd("> - "))
        assertEquals(EndList(0, 5), atEnd("-    "))
    }

    @Test
    fun `a cursor inside or before the marker is a plain Enter`() {
        assertNull(ListContinuation.onEnter("- foo", 0, emptySequence()))
        assertNull(ListContinuation.onEnter("- foo", 1, emptySequence()))
        assertNull(ListContinuation.onEnter("  - foo", 1, emptySequence()))
        assertNull(ListContinuation.onEnter("10. foo", 2, emptySequence()))
        assertNull(ListContinuation.onEnter("- [ ] foo", 3, emptySequence()))
        assertNull(ListContinuation.onEnter("> - foo", 2, emptySequence()))
    }

    @Test
    fun `lines that only look like markers are a plain Enter`() {
        assertNull(atEnd("plain text"))
        assertNull(atEnd(""))
        assertNull(atEnd("-foo"))
        assertNull(atEnd("-"))
        assertNull(atEnd("---"))
        assertNull(atEnd("- - -"))
        assertNull(atEnd("* * *"))
        assertNull(atEnd("**bold**"))
        assertNull(atEnd("1.5 litres"))
        assertNull(atEnd("1234567890. too long for a list number"))
    }

    @Test
    fun `a list line inside an open fence is a plain Enter`() {
        assertNull(atEnd("- a", listOf("text", "```kotlin")))
        assertNull(atEnd("- a", listOf("~~~")))
        assertNull(atEnd("> a", listOf("  ```")))
    }

    @Test
    fun `a closed fence above does not stop continuation`() {
        assertEquals(Continue("\n- "), atEnd("- a", listOf("```", "code", "```")))
        assertEquals(Continue("\n- "), atEnd("- a", listOf("~~~~", "```", "~~~~")))
    }

    @Test
    fun `a fence indented four or more spaces is not a fence`() {
        assertEquals(Continue("\n- "), atEnd("- a", listOf("    ```")))
        assertEquals(Continue("\n- "), atEnd("- a", listOf("\t```")))
        assertNull(atEnd("- a", listOf("   ```")))
        // ...and it does not close one either.
        assertTrue(ListContinuation.isInsideFence(sequenceOf("```", "    ```", "x")))
    }

    @Test
    fun `the fence scan follows the opening fence's character and length`() {
        assertTrue(ListContinuation.isInsideFence(sequenceOf("````", "```")))
        assertTrue(ListContinuation.isInsideFence(sequenceOf("~~~", "```")))
        assertTrue(ListContinuation.isInsideFence(sequenceOf("```", "``` not a close")))
        assertFalse(ListContinuation.isInsideFence(sequenceOf("```", "````")))
        assertFalse(ListContinuation.isInsideFence(sequenceOf("``", "inline")))
        assertFalse(ListContinuation.isInsideFence(sequenceOf("``` a`b")))
        assertFalse(ListContinuation.isInsideFence(emptySequence()))
    }
}
