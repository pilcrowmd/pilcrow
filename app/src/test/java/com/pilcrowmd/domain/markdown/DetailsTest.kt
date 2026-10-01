// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-161: reading `<details>` headers and pairing them into sections over the top-level blocks. */
class DetailsTest {

    private fun sections(markdown: String) = Details.sections(Parser.builder().build().parse(markdown))

    @Test
    fun `a header carries its summary as plain text, and whether it starts open`() {
        val header = Details.parseHeader("<details>\n<summary>Install <b>now</b> &amp; later</summary>")!!
        assertEquals("Install now & later", header.summary)
        assertEquals("", header.inlineBody)
        assertFalse(header.openByDefault)
        assertFalse(header.closesItself)
        assertTrue(Details.parseHeader("<details open>\n<summary>S</summary>")!!.openByDefault)
        assertFalse(Details.parseHeader("<details class=\"opener\">")!!.openByDefault)
    }

    @Test
    fun `the one-line form keeps its body and closes itself`() {
        val header = Details.parseHeader("<details><summary>Tight</summary>Its body.</details>")!!
        assertEquals("Tight", header.summary)
        assertEquals("Its body.", header.inlineBody)
        assertTrue(header.closesItself)
    }

    @Test
    fun `no summary reads as Details, and other HTML is not a header`() {
        assertEquals(Details.DEFAULT_SUMMARY, Details.parseHeader("<details>")!!.summary)
        assertNull(Details.parseHeader("<div>not a section</div>"))
        assertNull(Details.parseHeader("</details>"))
        assertTrue(Details.isCloseBlock("</details>"))
        assertFalse(Details.isCloseBlock("</details><p>more</p>"))
    }

    @Test
    fun `a section runs from its header to its close block`() {
        // 0 para, 1 <details>, 2 para, 3 list, 4 </details>, 5 para
        val found = sections("A\n\n<details>\n<summary>S</summary>\n\nBody\n\n- item\n</details>\n\nB")
        assertEquals(listOf(DetailsSection(header = 1, last = 4, closed = true, openByDefault = false)), found)
        assertEquals(4, found.single().closeBlock)
    }

    @Test
    fun `sections nest, and a one-block section has no close block`() {
        val found = sections(
            "<details>\n<summary>Outer</summary>\n\n<details>\n<summary>Inner</summary>\n\nx\n</details>\n\n" +
                "<details><summary>One</summary>y</details>\n\n</details>",
        )
        assertEquals(listOf(0, 1, 4), found.map { it.header })
        assertEquals(listOf(5, 3, 4), found.map { it.last })
        assertNull(found.single { it.header == 4 }.closeBlock)
    }

    @Test
    fun `an unclosed section runs to the end, and a stray close is ignored`() {
        val found = sections("</details>\n\n<details>\n<summary>S</summary>\n\nA\n\nB")
        assertEquals(listOf(DetailsSection(header = 1, last = 3, closed = false, openByDefault = false)), found)
        assertNull(found.single().closeBlock)
    }

    /**
     * The GitHub FAQ form: `</details>` followed straight away by the next `<details>`. With no blank
     * line between them CommonMark makes the two tags one HTML block, which both closes the first
     * section and opens the second.
     */
    @Test
    fun `back-to-back sections, the close and the next header in one block, stay two sections`() {
        // 0 <details>A · 1 Body A · 2 </details><details>B · 3 Body B · 4 </details>
        val found = sections(
            "<details>\n<summary>A</summary>\n\nBody A\n\n" +
                "</details>\n<details>\n<summary>B</summary>\n\nBody B\n\n</details>",
        )
        assertEquals(
            listOf(
                DetailsSection(header = 0, last = 1, closed = false, openByDefault = false),
                DetailsSection(header = 2, last = 4, closed = true, openByDefault = false),
            ),
            found,
        )
        assertEquals("B", Details.parseHeader("</details>\n<details>\n<summary>B</summary>")!!.summary)
    }

    /**
     * `</details>` with a text line straight under it is one HTML block, not a bare close block. It
     * must still end the section, or the section runs to the end of the document and hides all of it.
     * The block keeps painting, since it carries text.
     */
    @Test
    fun `a close tag with text straight after it still ends the section, and the block stays visible`() {
        // 0 <details> · 1 Body · 2 </details>+After text · 3 Tail
        val found = sections("<details>\n<summary>S</summary>\n\nBody\n\n</details>\nAfter text\n\nTail")
        assertEquals(listOf(DetailsSection(header = 0, last = 1, closed = false, openByDefault = false)), found)
        assertNull(found.single().closeBlock)
    }

    @Test
    fun `two close tags in one block end two sections, with or without text after them`() {
        // 0 outer · 1 inner · 2 x · 3 </details></details>(+after) · 4 Tail
        val outerInner = "<details>\n<summary>O</summary>\n\n<details>\n<summary>I</summary>\n\nx\n\n"
        val withText = sections("$outerInner</details>\n</details>\nafter\n\nTail")
        assertEquals(
            listOf(
                DetailsSection(header = 0, last = 2, closed = false, openByDefault = false),
                DetailsSection(header = 1, last = 2, closed = false, openByDefault = false),
            ),
            withText,
        )
        val bare = sections("$outerInner</details>\n</details>\n\nTail")
        assertEquals(
            listOf(
                DetailsSection(header = 0, last = 3, closed = true, openByDefault = false),
                DetailsSection(header = 1, last = 3, closed = true, openByDefault = false),
            ),
            bare,
        )
        assertTrue(Details.isCloseBlock("</details>\n</details>"))
        assertFalse(Details.isCloseBlock("</details>\n</details>\nafter"))
    }

    /**
     * Markwon's reducer drops a link reference definition before the adapter numbers its blocks, so a
     * definition must not take an index here either, or every section after it is off by one.
     */
    @Test
    fun `a link reference definition is not a block, so it does not shift the indices`() {
        // adapter positions, the definition dropped: 0 <details> · 1 Body · 2 </details> · 3 After
        val section = "<details>\n<summary>S</summary>\n\nBody\n\n</details>\n\nAfter"
        val before = sections("[r]: https://example.test\n\n$section")
        assertEquals(listOf(DetailsSection(header = 0, last = 2, closed = true, openByDefault = false)), before)
        val inside = sections(
            "<details>\n<summary>S</summary>\n\n[r]: https://example.test\n\nBody\n\n</details>\n\nAfter",
        )
        assertEquals(listOf(DetailsSection(header = 0, last = 2, closed = true, openByDefault = false)), inside)
    }
}
