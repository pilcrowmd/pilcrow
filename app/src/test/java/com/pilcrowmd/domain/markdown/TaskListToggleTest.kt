// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ticking a task box changes one character of the source, the right one, or nothing. */
class TaskListToggleTest {

    private val doc = """
        |# Todo
        |
        |- [ ] first
        |- [x] second
        |  - [ ] nested
        |- plain item
        |
        |> - [ ] quoted
        |
        |```
        |- [ ] not a task, code
        |```
        |
        |1. [X] numbered
    """.trimMargin()

    private fun block(text: String): Pair<Int, Int> {
        val start = doc.indexOf(text)
        return start to doc.indexOf("\n\n", start).let { if (it < 0) doc.length else it }
    }

    @Test
    fun `ticks the n-th box of a block, in source order, nested items included`() {
        val (from, to) = block("- [ ] first")
        assertEquals(doc.replace("- [ ] first", "- [x] first"), TaskListToggle.toggle(doc, from, to, 0))
        assertEquals(doc.replace("- [x] second", "- [ ] second"), TaskListToggle.toggle(doc, from, to, 1))
        assertEquals(doc.replace("- [ ] nested", "- [x] nested"), TaskListToggle.toggle(doc, from, to, 2))
        assertNull("the block has three boxes", TaskListToggle.toggle(doc, from, to, 3))
    }

    @Test
    fun `a quoted task and a numbered one count, an upper-case X unticks to a space`() {
        val (qFrom, qTo) = block("> - [ ] quoted")
        assertEquals(doc.replace("> - [ ] quoted", "> - [x] quoted"), TaskListToggle.toggle(doc, qFrom, qTo, 0))
        val (nFrom, nTo) = block("1. [X] numbered")
        assertEquals(doc.replace("1. [X] numbered", "1. [ ] numbered"), TaskListToggle.toggle(doc, nFrom, nTo, 0))
    }

    @Test
    fun `a task-looking line inside a code fence is not a task`() {
        val (from, to) = block("```")
        assertNull(TaskListToggle.toggle(doc, from, to, 0))
    }

    @Test
    fun `a count that disagrees with the reader changes nothing`() {
        val (from, to) = block("- [ ] first")
        assertNull(TaskListToggle.toggle(doc, from, to, 0, expectedCount = 2))
        val ticked = doc.replace("- [ ] first", "- [x] first")
        assertEquals(ticked, TaskListToggle.toggle(doc, from, to, 0, expectedCount = 3))
    }

    @Test
    fun `the whole document is one range too`() {
        assertEquals(5, TaskListToggle.taskBoxOffsets(doc, 0, doc.length).size)
    }

    @Test
    fun `a box without a list marker is not a task`() {
        assertEquals(emptyList<Int>(), TaskListToggle.taskBoxOffsets("[ ] alone\nfoo [ ] bar", 0, 21))
    }
}
