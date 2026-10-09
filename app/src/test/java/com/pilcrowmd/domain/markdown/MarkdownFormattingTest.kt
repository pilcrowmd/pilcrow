// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-217: what each formatting-bar button writes. Plain strings in, edits out.
 *
 * In the fixtures `«` and `»` mark the selection and `‸` marks a cursor with no selection, both
 * before and after the edit, so every expectation pins the text AND where the selection ends up.
 */
class MarkdownFormattingTest {

    private fun format(marked: String, action: FormatAction): String {
        val text = marked.filterNot { it in MARKS }
        val cursor = marked.indexOf(CURSOR)
        val start = if (cursor >= 0) cursor else marked.indexOf(SEL_START)
        val end = if (cursor >= 0) cursor else marked.indexOf(SEL_END) - 1

        val result = MarkdownFormatting.apply(text, start, end, action)

        result.replacements.zipWithNext().forEach { (a, b) ->
            assertTrue("replacements are sorted and do not overlap: $a, $b", a.end <= b.start)
        }
        val out = StringBuilder(text)
        result.replacements.asReversed().forEach { out.replace(it.start, it.end, it.text) }
        return if (result.selectionStart == result.selectionEnd) {
            out.insert(result.selectionStart, CURSOR).toString()
        } else {
            out.insert(result.selectionEnd, SEL_END).insert(result.selectionStart, SEL_START).toString()
        }
    }

    // ---- Inline styles ----

    @Test
    fun `bold wraps the selection and keeps the same text selected`() {
        assertEquals("a **«word»** b", format("a «word» b", FormatAction.Bold))
    }

    @Test
    fun `bold is removed when the selection includes its markers`() {
        assertEquals("a «word» b", format("a «**word**» b", FormatAction.Bold))
    }

    @Test
    fun `bold is removed when its markers sit right outside the selection`() {
        assertEquals("a «word» b", format("a **«word»** b", FormatAction.Bold))
    }

    @Test
    fun `italic wraps and toggles off`() {
        assertEquals("*«w»*", format("«w»", FormatAction.Italic))
        assertEquals("«w»", format("*«w»*", FormatAction.Italic))
        assertEquals("«w»", format("«*w*»", FormatAction.Italic))
    }

    @Test
    fun `italic on bold text adds italic instead of eating half of the bold`() {
        assertEquals("***«w»***", format("**«w»**", FormatAction.Italic))
        assertEquals("*«**w**»*", format("«**w**»", FormatAction.Italic))
    }

    @Test
    fun `italic on bold italic text removes only the italic`() {
        assertEquals("**«w»**", format("***«w»***", FormatAction.Italic))
    }

    @Test
    fun `bold on italic text adds bold, and on bold italic removes only the bold`() {
        assertEquals("***«w»***", format("*«w»*", FormatAction.Bold))
        assertEquals("*«w»*", format("***«w»***", FormatAction.Bold))
    }

    @Test
    fun `inline code wraps and toggles off`() {
        assertEquals("run `«ls»`", format("run «ls»", FormatAction.InlineCode))
        assertEquals("run «ls»", format("run `«ls»`", FormatAction.InlineCode))
        assertEquals("run «ls»", format("run «`ls`»", FormatAction.InlineCode))
    }

    @Test
    fun `strikethrough wraps and toggles off`() {
        assertEquals("~~«old»~~", format("«old»", FormatAction.Strikethrough))
        assertEquals("«old»", format("~~«old»~~", FormatAction.Strikethrough))
        assertEquals("«old»", format("«~~old~~»", FormatAction.Strikethrough))
    }

    @Test
    fun `with no selection an inline style inserts the pair with the cursor between`() {
        assertEquals("a**‸**b", format("a‸b", FormatAction.Bold))
        assertEquals("a*‸*b", format("a‸b", FormatAction.Italic))
        assertEquals("a`‸`b", format("a‸b", FormatAction.InlineCode))
        assertEquals("a~~‸~~b", format("a‸b", FormatAction.Strikethrough))
    }

    @Test
    fun `a selection over several lines is wrapped as it is`() {
        assertEquals("**«a\nb»**", format("«a\nb»", FormatAction.Bold))
    }

    @Test
    fun `a single marker on one side only is wrapped, not unwrapped`() {
        assertEquals("****«a»**", format("**«a»", FormatAction.Bold))
    }

    // ---- Link ----

    @Test
    fun `link around a selection puts the cursor inside the parentheses`() {
        assertEquals("see [docs](‸) now", format("see «docs» now", FormatAction.Link))
    }

    @Test
    fun `link with no selection puts the cursor inside the brackets`() {
        assertEquals("a[‸]()b", format("a‸b", FormatAction.Link))
    }

    // ---- Heading ----

    @Test
    fun `heading cycles plain, one, two, three, plain`() {
        assertEquals("# Ti‸tle", format("Ti‸tle", FormatAction.Heading))
        assertEquals("## Ti‸tle", format("# Ti‸tle", FormatAction.Heading))
        assertEquals("### Ti‸tle", format("## Ti‸tle", FormatAction.Heading))
        assertEquals("Ti‸tle", format("### Ti‸tle", FormatAction.Heading))
    }

    @Test
    fun `a deeper heading goes to plain`() {
        assertEquals("Ti‸tle", format("#### Ti‸tle", FormatAction.Heading))
        assertEquals("Ti‸tle", format("###### Ti‸tle", FormatAction.Heading))
    }

    @Test
    fun `heading keeps the indent and moves a cursor at the line start past the new prefix`() {
        assertEquals("  # Ti‸tle", format("  Ti‸tle", FormatAction.Heading))
        assertEquals("# ‸Title", format("‸Title", FormatAction.Heading))
    }

    @Test
    fun `hash without a space is text, not a heading`() {
        assertEquals("# #‸tag", format("#‸tag", FormatAction.Heading))
    }

    @Test
    fun `heading over several lines gives every line the first line's next level`() {
        assertEquals("# «a\n# b»", format("«a\n## b»", FormatAction.Heading))
    }

    // ---- Bullet ----

    @Test
    fun `bullet adds a dash and toggles it off`() {
        assertEquals("- it‸em", format("it‸em", FormatAction.BulletList))
        assertEquals("it‸em", format("- it‸em", FormatAction.BulletList))
    }

    @Test
    fun `bullet replaces another list or task marker with a dash`() {
        assertEquals("- it‸em", format("* it‸em", FormatAction.BulletList))
        assertEquals("- it‸em", format("+ it‸em", FormatAction.BulletList))
        assertEquals("- it‸em", format("1. it‸em", FormatAction.BulletList))
        assertEquals("- it‸em", format("- [ ] it‸em", FormatAction.BulletList))
        assertEquals("- it‸em", format("- [x] it‸em", FormatAction.BulletList))
    }

    @Test
    fun `bullet keeps the indent`() {
        assertEquals("  - it‸em", format("  it‸em", FormatAction.BulletList))
        assertEquals("  it‸em", format("  - it‸em", FormatAction.BulletList))
    }

    @Test
    fun `bullet on an empty line writes the marker with the cursor after it`() {
        assertEquals("- ‸", format("‸", FormatAction.BulletList))
    }

    @Test
    fun `bullet over several lines adds where missing, removes only when every line has one`() {
        assertEquals("«- a\n- b»", format("«- a\nb»", FormatAction.BulletList))
        assertEquals("«a\nb»", format("«- a\n- b»", FormatAction.BulletList))
    }

    @Test
    fun `blank lines inside a several-line selection are left alone`() {
        assertEquals("- «a\n\n- b»", format("«a\n\nb»", FormatAction.BulletList))
    }

    @Test
    fun `a selection ending at the start of a line does not touch that line`() {
        assertEquals("- «a\n»b", format("«a\n»b", FormatAction.BulletList))
    }

    @Test
    fun `a thematic break is not read as a list marker`() {
        assertEquals("- ---‸", format("---‸", FormatAction.BulletList))
    }

    // ---- Checkbox ----

    @Test
    fun `checkbox adds a task box and toggles it off, checked or not`() {
        assertEquals("- [ ] it‸em", format("it‸em", FormatAction.Checkbox))
        assertEquals("it‸em", format("- [ ] it‸em", FormatAction.Checkbox))
        assertEquals("it‸em", format("- [x] it‸em", FormatAction.Checkbox))
    }

    @Test
    fun `checkbox replaces another list marker`() {
        assertEquals("- [ ] it‸em", format("- it‸em", FormatAction.Checkbox))
        assertEquals("  - [ ] it‸em", format("  1. it‸em", FormatAction.Checkbox))
    }

    // ---- Quote ----

    @Test
    fun `quote adds a quote prefix and toggles one level off`() {
        assertEquals("> te‸xt", format("te‸xt", FormatAction.Quote))
        assertEquals("te‸xt", format("> te‸xt", FormatAction.Quote))
        assertEquals("te‸xt", format(">te‸xt", FormatAction.Quote))
        assertEquals("> te‸xt", format("> > te‸xt", FormatAction.Quote))
    }

    @Test
    fun `quote keeps the indent and goes before a list marker`() {
        assertEquals("  > - te‸xt", format("  - te‸xt", FormatAction.Quote))
    }

    // ---- Indent / Outdent ----

    @Test
    fun `indent adds four spaces at the start of every touched line`() {
        assertEquals("    it‸em", format("it‸em", FormatAction.Indent))
        assertEquals("    «- a\n    - b»", format("«- a\n- b»", FormatAction.Indent))
    }

    @Test
    fun `outdent removes up to four spaces or one tab`() {
        assertEquals("  it‸em", format("      it‸em", FormatAction.Outdent))
        assertEquals("it‸em", format("  it‸em", FormatAction.Outdent))
        assertEquals("\tit‸em", format("\t\tit‸em", FormatAction.Outdent))
        assertEquals("it‸em", format("it‸em", FormatAction.Outdent))
    }

    @Test
    fun `outdent with the cursor inside the removed indent puts it at the line start`() {
        assertEquals("‸item", format("  ‸  item", FormatAction.Outdent))
    }

    // ---- Table ----

    @Test
    fun `table on an empty line has the cursor in the first empty cell`() {
        assertEquals("$TABLE_HEAD| ‸ |  |\n|  |  |", format("‸", FormatAction.Table))
    }

    @Test
    fun `table after a line with text gets a blank line before it, never splitting the line`() {
        assertEquals("text\n\n$TABLE_HEAD| ‸ |  |\n|  |  |", format("te‸xt", FormatAction.Table))
    }

    @Test
    fun `table on an empty line under a paragraph gets a blank line before it`() {
        assertEquals("para\n\n$TABLE_HEAD| ‸ |  |\n|  |  |", format("para\n‸", FormatAction.Table))
    }

    @Test
    fun `table on an empty line under a blank line adds no other blank line`() {
        assertEquals("para\n\n$TABLE_HEAD| ‸ |  |\n|  |  |", format("para\n\n‸", FormatAction.Table))
    }

    @Test
    fun `table before a line with text leaves a blank line after it`() {
        assertEquals(
            "text\n\n$TABLE_HEAD| ‸ |  |\n|  |  |\n\nnext",
            format("text‸\nnext", FormatAction.Table),
        )
    }

    @Test
    fun `table before a blank line adds nothing after it`() {
        assertEquals(
            "text\n\n$TABLE_HEAD| ‸ |  |\n|  |  |\n\nmore",
            format("text‸\n\nmore", FormatAction.Table),
        )
    }

    // ---- Divider ----

    @Test
    fun `divider after a paragraph line gets a blank line before it`() {
        assertEquals("para\n\n---‸", format("pa‸ra", FormatAction.Divider))
        assertEquals("para\n\n---‸\nnext", format("para‸\nnext", FormatAction.Divider))
    }

    @Test
    fun `divider on an empty line under a blank line is written there`() {
        assertEquals("para\n\n---‸", format("para\n\n‸", FormatAction.Divider))
        assertEquals("---‸", format("‸", FormatAction.Divider))
    }

    @Test
    fun `divider on an empty line right under a paragraph gets a blank line first`() {
        assertEquals("para\n\n---‸", format("para\n‸", FormatAction.Divider))
        assertEquals("para\n\n---‸\nnext", format("para\n‸\nnext", FormatAction.Divider))
    }

    @Test
    fun `divider after a whitespace-only line needs no blank line`() {
        assertEquals("  \n---‸", format("  ‸", FormatAction.Divider))
    }

    @Test
    fun `divider with a selection goes after the line the selection ends on`() {
        assertEquals("a\nb\n\n---‸", format("«a\nb»", FormatAction.Divider))
    }

    // ---- Empty and edge selections ----

    @Test
    fun `a line action over lines that are all blank does nothing`() {
        ALL.filterIsInstance<FormatAction.LinePrefix>().forEach { action ->
            assertEquals("$action", "«\n  \n\t»", format("«\n  \n\t»", action))
            assertEquals("$action", "a\n«\n\n»b", format("a\n«\n\n»b", action))
        }
    }

    @Test
    fun `every action works on an empty document`() {
        assertEquals("**‸**", format("‸", FormatAction.Bold))
        assertEquals("[‸]()", format("‸", FormatAction.Link))
        assertEquals("# ‸", format("‸", FormatAction.Heading))
        assertEquals("- ‸", format("‸", FormatAction.BulletList))
        assertEquals("- [ ] ‸", format("‸", FormatAction.Checkbox))
        assertEquals("> ‸", format("‸", FormatAction.Quote))
        assertEquals("    ‸", format("‸", FormatAction.Indent))
        assertEquals("‸", format("‸", FormatAction.Outdent))
        assertEquals("---‸", format("‸", FormatAction.Divider))
    }

    @Test
    fun `every action works with the cursor at the start and at the end of the document`() {
        ALL.forEach { action ->
            format("‸ab\ncd", action)
            format("ab\ncd‸", action)
            format("ab\ncd\n‸", action)
        }
        assertEquals("# ‸ab\ncd", format("‸ab\ncd", FormatAction.Heading))
        assertEquals("ab\n# cd‸", format("ab\ncd‸", FormatAction.Heading))
        assertEquals("ab\ncd**‸**", format("ab\ncd‸", FormatAction.Bold))
        assertEquals("**‸**ab", format("‸ab", FormatAction.Bold))
    }

    // ---- Quote prefixes and line kinds ----

    @Test
    fun `list buttons work after a quote prefix and keep it`() {
        assertEquals("> - a‸", format("> a‸", FormatAction.BulletList))
        assertEquals("> a‸", format("> - a‸", FormatAction.BulletList))
        assertEquals("> - [ ] a‸", format("> - a‸", FormatAction.Checkbox))
        assertEquals("> > a‸", format("> > - [x] a‸", FormatAction.Checkbox))
        assertEquals("  > - a‸", format("  > a‸", FormatAction.BulletList))
    }

    @Test
    fun `heading works after a quote prefix and keeps it`() {
        assertEquals("> # a‸", format("> a‸", FormatAction.Heading))
        assertEquals("> ## a‸", format("> # a‸", FormatAction.Heading))
    }

    @Test
    fun `quote still toggles only the quote prefix`() {
        assertEquals("- a‸", format("> - a‸", FormatAction.Quote))
        assertEquals("> # a‸", format("# a‸", FormatAction.Quote))
    }

    @Test
    fun `heading, bullet and checkbox replace one another`() {
        assertEquals("# item‸", format("- item‸", FormatAction.Heading))
        assertEquals("# t‸", format("- [ ] t‸", FormatAction.Heading))
        assertEquals("# x‸", format("1. x‸", FormatAction.Heading))
        assertEquals("- t‸", format("## t‸", FormatAction.BulletList))
        assertEquals("- [ ] t‸", format("# t‸", FormatAction.Checkbox))
        assertEquals("- [ ] x‸", format("1. x‸", FormatAction.Checkbox))
    }

    // ---- Inline styles across several spans ----

    @Test
    fun `a selection holding the marker inside is wrapped, not unwrapped`() {
        assertEquals("**«**a** and **b**»**", format("«**a** and **b**»", FormatAction.Bold))
        assertEquals("*«*a* and *b*»*", format("«*a* and *b*»", FormatAction.Italic))
        assertEquals("~~«~~a~~ b ~~c~~»~~", format("«~~a~~ b ~~c~~»", FormatAction.Strikethrough))
    }

    @Test
    fun `markers outside a selection that holds the marker are not taken away`() {
        assertEquals("****«a** and **b»****", format("**«a** and **b»**", FormatAction.Bold))
    }

    @Test
    fun `a selection of marker characters only is wrapped`() {
        assertEquals("**«****»**", format("«****»", FormatAction.Bold))
    }

    // ---- Nothing else changes ----

    @Test
    fun `text outside the selection is unchanged`() {
        val doc = "# Top\n\nkeep «this» here\n- list\nlast"
        assertEquals("# Top\n\nkeep **«this»** here\n- list\nlast", format(doc, FormatAction.Bold))
        assertEquals("# Top\n\nkeep [this](‸) here\n- list\nlast", format(doc, FormatAction.Link))
        assertEquals("# Top\n\n- keep «this» here\n- list\nlast", format(doc, FormatAction.BulletList))
        assertEquals("# Top\n\n> keep «this» here\n- list\nlast", format(doc, FormatAction.Quote))
        assertEquals("# Top\n\n# keep «this» here\n- list\nlast", format(doc, FormatAction.Heading))
    }

    private companion object {
        const val SEL_START = '«'
        const val SEL_END = '»'
        const val CURSOR = '‸'
        const val MARKS = "«»‸"
        val ALL = listOf(
            FormatAction.Bold, FormatAction.Italic, FormatAction.InlineCode, FormatAction.Strikethrough,
            FormatAction.Link, FormatAction.Heading, FormatAction.BulletList, FormatAction.Checkbox,
            FormatAction.Quote, FormatAction.Indent, FormatAction.Outdent, FormatAction.Table,
            FormatAction.Divider,
        )
        const val TABLE_HEAD = "| Column | Column |\n| --- | --- |\n"
    }
}
