// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** M-160: the K13/K14 candidate finder; whether a candidate becomes a link is the resolver's call. */
class BareNoteLinksTest {

    @Test
    fun `K13 drops a quoted title from the destination`() {
        assertEquals(
            listOf(BareLink(0, 28, "x", "projects/plan.md")),
            BareNoteLinks.find("[x](projects/plan.md \"Plan\")"),
        )
        assertEquals(listOf("My plan.md"), BareNoteLinks.find("[x](My plan.md\t'v2' )").map { it.destination })
    }

    @Test
    fun `extension is matched in any case, other extensions are not candidates`() {
        assertEquals(1, BareNoteLinks.find("[a](My Note.MARKDOWN)").size)
        assertEquals(emptyList<BareLink>(), BareNoteLinks.find("[a](My Note.txt) and <a b.png>"))
    }

    @Test
    fun `a newline or a nested bracket ends the candidate`() {
        assertEquals(emptyList<BareLink>(), BareNoteLinks.find("[a](My\nNote.md) and <My\nNote.md>"))
        assertEquals(emptyList<BareLink>(), BareNoteLinks.find("[x](\nNote 2.md)"))
        assertEquals(emptyList<BareLink>(), BareNoteLinks.find("[x](Note 2.md \"t\"\n)"))
        assertEquals(emptyList<BareLink>(), BareNoteLinks.find("[a [b]](c d.md)"))
        assertEquals(listOf(BareLink(3, 9, "b.md", "b.md")), BareNoteLinks.find("<a <b.md>>"))
    }

    @Test
    fun `several candidates are found left to right without overlap`() {
        val found = BareNoteLinks.find("<A b.md> then [c](D e.md) then <F.md>")
        assertEquals(listOf("A b.md", "D e.md", "F.md"), found.map { it.destination })
        assertEquals(listOf(0, 14, 31), found.map { it.start })
    }

    @Test
    fun `long runs of blanks or brackets finish fast`() {
        val blanks = " \t".repeat(LONG_RUN / 2)
        val inputs = listOf(
            "[x](" + blanks,
            "[x](a" + blanks,
            "[x](a.md" + blanks,
            "<" + blanks,
            "<a" + blanks,
            "[".repeat(LONG_RUN),
            "<".repeat(LONG_RUN),
            "[x](".repeat(LONG_RUN / 4),
        )
        val millis = measureTimeMillis { inputs.forEach { BareNoteLinks.find(it) } }
        assertTrue("took $millis ms", millis < BOUND_MILLIS)
    }

    private companion object {
        const val LONG_RUN = 100_000
        const val BOUND_MILLIS = 2_000L
    }
}
