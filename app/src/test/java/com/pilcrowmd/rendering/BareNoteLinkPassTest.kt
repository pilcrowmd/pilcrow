// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-160 (K13/K14, cases 19 and 20): bare note links become links only when the resolver found a
 * note for them; everything else is left exactly as parsed.
 */
@RunWith(RobolectricTestRunner::class)
class BareNoteLinkPassTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon = buildPilcrowMarkwon(context)

    private fun parse(markdown: String, resolvable: Set<String>): Node =
        markwon.parse(markdown).also { linkBareNotes(it, resolvable) }

    /** Each link as destination to label, in document order. */
    private fun links(document: Node): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        document.accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                found += link.destination to textOf(link)
            }
        })
        return found
    }

    /** All the text the reader would show, links' labels and code included. */
    private fun textOf(node: Node): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                sb.append(code.literal)
            }
        })
        return sb.toString()
    }

    @Test
    fun k13_a_bracket_link_with_spaces_becomes_a_link_when_it_names_a_note() {
        val document = parse("see [x](Note 2.md) now", setOf("Note 2.md"))
        assertEquals(listOf("Note 2.md" to "x"), links(document))
        assertEquals("see x now", textOf(document))
    }

    @Test
    fun case19_a_bare_link_that_names_no_note_stays_plain_text() {
        val markdown = "see [x](No such note.md) now"
        val document = parse(markdown, setOf("Note 2.md"))
        assertEquals(emptyList<Pair<String, String>>(), links(document))
        assertEquals(markdown, textOf(document))
    }

    @Test
    fun an_empty_set_changes_nothing() {
        val markdown = "see [x](Note 2.md) and <Note 2.md> now"
        val document = parse(markdown, emptySet())
        assertEquals(emptyList<Pair<String, String>>(), links(document))
        assertEquals(markdown, textOf(document))
    }

    @Test
    fun case20_an_angle_bracket_name_becomes_a_link_shown_as_the_name() {
        val document = parse("open <Note 2.md> please", setOf("Note 2.md"))
        assertEquals(listOf("Note 2.md" to "Note 2.md"), links(document))
        assertEquals("open Note 2.md please", textOf(document))
    }

    @Test
    fun never_inside_inline_code_or_an_existing_link() {
        val document = parse("`[x](Note 2.md)` and [see [y](Note 2.md)](https://example.com)", setOf("Note 2.md"))
        assertEquals(listOf("https://example.com" to "see [y](Note 2.md)"), links(document))
    }

    @Test
    fun parsing_the_same_document_twice_wraps_once_each_time() {
        val markdown = "a [x](Note 2.md) b <Note 2.md> c"
        repeat(2) {
            val document = parse(markdown, setOf("Note 2.md"))
            assertEquals(listOf("Note 2.md" to "x", "Note 2.md" to "Note 2.md"), links(document))
            assertEquals("a x b Note 2.md c", textOf(document))
        }
    }

    /** Every inline HTML literal, in document order. */
    private fun htmlOf(document: Node): List<String> {
        val found = mutableListOf<String>()
        document.accept(object : AbstractVisitor() {
            override fun visit(htmlInline: HtmlInline) {
                found += htmlInline.literal
            }
        })
        return found
    }

    @Test
    fun k14_a_name_the_parser_reads_as_inline_html_becomes_a_link_only_when_it_names_a_note() {
        val markdown = "about <Note two.md> here"
        val linked = parse(markdown, setOf("Note two.md"))
        assertEquals(listOf("Note two.md" to "Note two.md"), links(linked))
        assertEquals(emptyList<String>(), htmlOf(linked))
        assertEquals("about Note two.md here", textOf(linked))

        // Control: not in the set, the inline HTML stays exactly as the parser left it (today's behaviour).
        val untouched = parse(markdown, setOf("Note 2.md"))
        assertEquals(emptyList<Pair<String, String>>(), links(untouched))
        assertEquals(listOf("<Note two.md>"), htmlOf(untouched))
    }

    @Test
    fun other_inline_html_is_never_touched() {
        val document = parse("a <span title=\"x.md\"> b </span> c", setOf("x.md"))
        assertEquals(emptyList<Pair<String, String>>(), links(document))
        assertEquals(listOf("<span title=\"x.md\">", "</span>"), htmlOf(document))
    }
}
