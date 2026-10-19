// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M-247: the `:shortcode:` rule the renderer and search share. Pure, so every boundary is pinned here
 * on the scanner alone; what other inline syntax (code spans, links, HTML, maths) does to a shortcode
 * is pinned against the real renderer in `EmojiShortcodeRenderingTest` and in search's parity test.
 */
class EmojiShortcodesTest {

    private val table = mapOf(
        "smile" to "😄",
        "tada" to "🎉",
        "+1" to "👍",
        "-1" to "👎",
        "b" to "🅱️",
        "30" to "X",
    )
    private val shortcodes = EmojiShortcodes { table[it] }

    private fun found(content: String) = shortcodes.ranges(content).map { content.substring(it.range) to it.emoji }

    @Test
    fun `a known name is replaced, colons included`() {
        val match = shortcodes.ranges("Hi :smile: there").single()
        assertEquals(3..9, match.range)
        assertEquals("smile", match.alias)
        assertEquals(table["smile"], match.emoji)
    }

    @Test
    fun `an unknown name stays literal`() {
        assertEquals(emptyList<Any>(), found("Hi :nosuchname: there"))
    }

    @Test
    fun `a time stays literal even when the middle number is a name`() {
        // "30" is in this table on purpose: the colon after a digit is what declines it.
        assertEquals(emptyList<Any>(), found("at 10:30:45 sharp"))
    }

    @Test
    fun `a colon after a letter never opens one`() {
        // "b" is a real gemoji alias (the B button).
        assertEquals(emptyList<Any>(), found("a:b:c"))
        assertEquals(listOf(":b:" to table["b"]), found("a :b: c"))
    }

    @Test
    fun `adjacent shortcodes are both replaced`() {
        assertEquals(listOf(":smile:" to table["smile"], ":tada:" to table["tada"]), found(":smile::tada:"))
    }

    @Test
    fun `an escaped colon stays literal, an escaped backslash does not`() {
        assertEquals(emptyList<Any>(), found("\\:smile:"))
        assertEquals(listOf(":smile:" to table["smile"]), found("\\\\:smile:"))
    }

    @Test
    fun `plus one and minus one are names`() {
        assertEquals(listOf(":+1:" to table["+1"], ":-1:" to table["-1"]), found(":+1: and :-1:"))
    }

    @Test
    fun `an empty name, an upper-case name and an unclosed name stay literal`() {
        assertEquals(emptyList<Any>(), found(":: :Smile: :smile"))
    }

    @Test
    fun `NONE replaces nothing`() {
        assertEquals(emptyList<Any>(), EmojiShortcodes.NONE.ranges(":smile: :tada:"))
        assertNull(EmojiShortcodes.NONE.matchAt(":smile:", 0))
    }
}
