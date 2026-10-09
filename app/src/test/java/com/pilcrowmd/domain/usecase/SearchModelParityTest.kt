// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Search must count only what the reader paints, block by block: the highlighter re-searches the
 * painted text and focuses the match whose ordinal the model chose, so one extra model match shifts
 * every later ordinal in that block. Each case below is a place where the model saw text the reader
 * does not paint. The painted side of each is pinned in `rendering/SearchPaintedParityTest`.
 */
class SearchModelParityTest {

    private val useCase = SearchMarkdownUseCase(ParseMarkdownHeadingsUseCase())

    // --- M-28: blocks inside a container are painted on separate lines ---

    @Test
    fun `a query straddling two paragraphs of a quote does not match`() {
        // Painted "one\n\ntwo": the join "etw" exists only if the paragraphs are glued together.
        assertEquals(0, useCase.findSearchMatches("> one\n>\n> two", "etw").size)
    }

    @Test
    fun `a query straddling two list items does not match`() {
        // Painted "foo\nbar": the bullets are margin spans, not text.
        assertEquals(0, useCase.findSearchMatches("- foo\n- bar", "obar").size)
    }

    @Test
    fun `a straddling phantom does not shift the ordinal of the real match after it`() {
        // Glued, "xa" + "ab aa" is "xaab aa": a phantom "aa" at the join takes ordinal 0, so the
        // reader focused its own second match, which does not exist, and lit nothing.
        val content = "> xa\n>\n> ab aa"
        val matches = useCase.findSearchMatches(content, "aa")
        assertEquals(1, matches.size)
        assertEquals(content.lastIndexOf("aa"), matches[0].startIndex)
        assertEquals(0, matches[0].occurrenceInBlock)
    }

    @Test
    fun `the separator leaves every raw offset where it was`() {
        // "> one\n>\n> two": "one" starts at 2 and "two" at 10.
        assertEquals(2, useCase.findSearchMatches("> one\n>\n> two", "one")[0].startIndex)
        assertEquals(10, useCase.findSearchMatches("> one\n>\n> two", "two")[0].startIndex)
        // "- foo\n- bar": "bar" starts at 8.
        assertEquals(8, useCase.findSearchMatches("- foo\n- bar", "bar")[0].startIndex)
        // The separator itself is anchored where the first block's text ends, at 5 (the '\n' after
        // "one") and at 5 again in the list, and it consumes no source.
        assertEquals(5, useCase.findSearchMatches("> one\n>\n> two", "\ntwo")[0].startIndex)
        assertEquals(5, useCase.findSearchMatches("- foo\n- bar", "\nbar")[0].startIndex)
    }

    // --- M-33: code and inline HTML inside a formula are part of the formula ---

    @Test
    fun `inline code inside a formula is not searchable`() {
        // `$b `czz` d$` paints ONE formula image; the code span is inside it.
        assertEquals(0, useCase.findSearchMatches("aa \$b `czz` d\$ ee", "czz").size)
        // Control: the same formula without the code span.
        assertEquals(0, useCase.findSearchMatches("aa \$b czz d\$ ee", "czz").size)
    }

    @Test
    fun `a code span that only contains dollars stays searchable`() {
        // Painted literally as code. The raw-source math scan marks `$x$` as maths, so a check on the
        // code's own characters would hide it; the check is on the opening backtick.
        assertEquals(1, useCase.findSearchMatches("`\$x\$`", "x").size)
    }

    @Test
    fun `a code span right after a formula stays searchable`() {
        // `$a$` is a formula, then a code span painting "$b$": its backtick is outside the formula.
        val content = "\$a\$`\$b\$`"
        val matches = useCase.findSearchMatches(content, "b")
        assertEquals(1, matches.size)
        assertEquals(content.lastIndexOf("b"), matches[0].startIndex)
    }

    @Test
    fun `an inline HTML tag inside a formula is not searchable`() {
        assertEquals(0, useCase.findSearchMatches("aa \$b <i>q</i> d\$ ee", "<i").size)
        // The tag joins the formula's single placeholder rather than splitting it.
        assertEquals(1, useCase.findSearchMatches("aa \$b <i>q</i> d\$ ee", "\uFFFC").size)
        // Outside a formula a CDATA section paints its text, so inside one it must be masked.
        assertEquals(0, useCase.findSearchMatches("aa \$b <![CDATA[zz]]> d\$ ee", "zz").size)
    }

    @Test
    fun `a dollar inside a code span does not open a formula`() {
        // The reader paints three code spans and no formula: the `$` in the first code span is
        // code, so it cannot pair with the `$` in the last one and hide everything in between.
        val content = "`\$HOME/bin`, `PATH`, and `\$PWD`"
        assertEquals(listOf(14), useCase.findSearchMatches(content, "PATH").map { it.startIndex })
        assertEquals(listOf(26), useCase.findSearchMatches(content, "\$PWD").map { it.startIndex })
        assertEquals(listOf(21), useCase.findSearchMatches(content, "and").map { it.startIndex })
    }

    @Test
    fun `a formula that opens before a code span still closes inside it`() {
        // `$a `b$` is one formula, as the reader parses it; only " c" after it is text.
        assertEquals(0, useCase.findSearchMatches("\$a `b\$` c", "b").size)
        assertEquals(1, useCase.findSearchMatches("\$a `b\$` c", "c").size)
    }

    // --- M-331: inline HTML counts what it paints, never its markup ---

    @Test
    fun `an inline tag's markup is not searchable, the text between its tags is`() {
        val content = "Press <kbd>Ctrl</kbd> now"
        assertEquals(0, useCase.findSearchMatches(content, "kbd").size)
        val ctrl = useCase.findSearchMatches(content, "Ctrl")
        assertEquals(1, ctrl.size)
        assertEquals(content.indexOf("Ctrl"), ctrl[0].startIndex)
        assertEquals(content.indexOf("now"), useCase.findSearchMatches(content, "now")[0].startIndex)
    }

    @Test
    fun `a tag that paints nothing joins the text either side of it`() {
        // Painted "xy": the empty <b></b> draws nothing at all.
        assertEquals(1, useCase.findSearchMatches("x<b></b>y", "xy").size)
        // A comment draws nothing either.
        assertEquals(0, useCase.findSearchMatches("a <!-- hid --> b", "hid").size)
    }

    @Test
    fun `a br tag paints a line break, which no query can straddle`() {
        assertEquals(0, useCase.findSearchMatches("one<br>two", "onetwo").size)
        assertEquals(0, useCase.findSearchMatches("one<BR />two", "onetwo").size)
        // A closing </br> draws nothing, so the text either side joins.
        assertEquals(1, useCase.findSearchMatches("one</br>two", "onetwo").size)
    }

    @Test
    fun `an img tag paints one picture and none of its markup`() {
        val content = "foo<img src=\"a.png\" alt=\"cat\">bar"
        assertEquals(0, useCase.findSearchMatches(content, "foobar").size)
        assertEquals(0, useCase.findSearchMatches(content, "img").size)
        assertEquals(0, useCase.findSearchMatches(content, "cat").size)
        assertEquals(1, useCase.findSearchMatches(content, "\uFFFC").size)
    }

    @Test
    fun `an empty iframe paints a no-break space`() {
        assertEquals(1, useCase.findSearchMatches("a<iframe src=\"x\"></iframe>b", "a\u00A0b").size)
        assertEquals(0, useCase.findSearchMatches("a<iframe src=\"x\"></iframe>b", "ab").size)
        // With content it is not empty, and paints only that content.
        assertEquals(1, useCase.findSearchMatches("a<iframe src=\"x\">in</iframe>b", "ainb").size)
    }

    @Test
    fun `a CDATA section paints its text`() {
        val content = "x <![CDATA[ zz ]]> y"
        assertEquals(1, useCase.findSearchMatches(content, "zz").size)
        assertEquals(0, useCase.findSearchMatches(content, "CDATA").size)
    }

    @Test
    fun `a CDATA section paints its whitespace collapsed to one space`() {
        assertEquals(1, useCase.findSearchMatches("x <![CDATA[ a   b ]]> y", "a b").size)
        // Inside <pre> it is painted as written.
        assertEquals(1, useCase.findSearchMatches("a<pre><![CDATA[ p  q ]]></pre>b", "p  q").size)
    }

    // --- block-level tags inside a paragraph start a new line ---

    @Test
    fun `a block-level tag inside a paragraph starts a new line`() {
        // Painted "a\nxb": the text after </div> is drawn by Markdown and joins the div's text.
        assertEquals(0, useCase.findSearchMatches("a<div>x</div>b", "ax").size)
        assertEquals(1, useCase.findSearchMatches("a<div>x</div>b", "xb").size)
        // Painted "a\nb": an empty div still starts its line.
        assertEquals(0, useCase.findSearchMatches("a<div></div>b", "ab").size)
        // Painted "a\nb": hr is a block-level tag with no content.
        assertEquals(0, useCase.findSearchMatches("a<hr>b", "ab").size)
    }

    @Test
    fun `a paragraph tag also ends its line`() {
        // Painted "a\nx\nb".
        assertEquals(0, useCase.findSearchMatches("a<p>x</p>b", "xb").size)
        // Painted "a\nx\ny": a second <p> closes the first.
        assertEquals(0, useCase.findSearchMatches("a<p>x<p>y", "xy").size)
        // Painted "a\nx\ny": a tag that is not block-level still closes an open <p>, with a '\n'.
        assertEquals(0, useCase.findSearchMatches("a<p>x<mark>y", "xy").size)
    }

    @Test
    fun `a second list item tag closes the first`() {
        // So the second </li> finds no open li, and the <b> after it stays on the same line.
        assertEquals(1, useCase.findSearchMatches("a<li>x<li>y</li><b>z</b></li>c<b>d</b>", "cd").size)
    }

    @Test
    fun `a tag that is neither inline nor block-level paints no line break`() {
        assertEquals(1, useCase.findSearchMatches("a<mark>x</mark>b", "axb").size)
        assertEquals(1, useCase.findSearchMatches("a<details>x</details>b", "axb").size)
    }

    @Test
    fun `the tag after a closed block-level tag starts a new line`() {
        // Painted "a\nxb\nc": </div> leaves the next tag on a new line, here an inline <b>.
        assertEquals(0, useCase.findSearchMatches("a<div>x</div>b<b>c</b>", "bc").size)
        assertEquals(1, useCase.findSearchMatches("a<div>x</div>b<b>c</b>", "xb").size)
        assertEquals(0, useCase.findSearchMatches("a<div>x</div>b<mark>c</mark>", "bc").size)
    }
}
