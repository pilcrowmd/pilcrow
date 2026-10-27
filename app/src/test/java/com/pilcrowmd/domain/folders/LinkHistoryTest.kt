// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-160: K10 link history and the K2/P14 outermost-folder pick. */
class LinkHistoryTest {

    @Test
    fun back_is_offered_only_on_the_note_the_last_link_opened() {
        val history = LinkHistory<String>()
        assertFalse(history.canGoBack("a"))
        history.push("a", "b")
        assertTrue(history.canGoBack("b"))
        assertFalse(history.canGoBack("a"))
        assertFalse(history.canGoBack(null))
        assertEquals("a", history.peek("b"))
        assertNull(history.peek("c"))
    }

    @Test
    fun back_unwinds_a_chain_of_links_in_order() {
        val history = LinkHistory<String>()
        history.push("a", "b")
        history.push("b", "c")
        assertEquals("b", history.back())
        assertTrue(history.canGoBack("b"))
        assertEquals("a", history.back())
        assertFalse(history.canGoBack("a"))
        assertNull(history.back())
    }

    @Test
    fun clear_ends_the_history() {
        val history = LinkHistory<String>()
        history.push("a", "b")
        history.clear()
        assertFalse(history.canGoBack("b"))
        assertNull(history.peek("b"))
    }

    @Test
    fun outermost_folder_is_the_longest_chain_never_the_first_added() {
        val inner = "projects" to listOf("p", "plan")
        val outer = "notes" to listOf("n", "p", "plan")
        assertEquals(outer, outermostFolder(listOf(inner, outer)))
        assertEquals(outer, outermostFolder(listOf(outer, inner)))
        assertNull(outermostFolder(emptyList<Pair<String, List<String>>>()))
    }
}
