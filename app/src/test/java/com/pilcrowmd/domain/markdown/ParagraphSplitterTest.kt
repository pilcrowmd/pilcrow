// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ParagraphSplitter] on hand-built trees (M-328): the cut keeps every child object, never goes
 * inside a link, emphasis, code span or formula, touches only a top-level paragraph, and reports
 * where its seams are. The parser-based proofs (positions, search parity) live next to the use
 * cases that rely on them.
 */
class ParagraphSplitterTest {

    /** The contract, spelled out: raising the constant must fail these tests, not move them along. */
    private val limit = 150

    /** Text at even indexes, a StrongEmphasis holding one Text at odd ones; each text ends in a space. */
    private fun alternating(count: Int): List<Node> = List(count) { i ->
        if (i % 2 == 0) {
            Text("t$i ")
        } else {
            StrongEmphasis().also { it.appendChild(Text("s$i")) }
        }
    }

    private fun paragraphOf(children: List<Node>): Paragraph =
        Paragraph().also { p -> children.forEach { p.appendChild(it) } }

    private fun documentOf(vararg blocks: Node): Document =
        Document().also { d -> blocks.forEach { d.appendChild(it) } }

    private fun childrenOf(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    private fun blocksOf(document: Node): List<Node> = childrenOf(document)

    @Test
    fun `a 1000 child paragraph becomes plain pieces of at most 150 children with the original nodes in order`() {
        val kids = alternating(1000)
        val document = documentOf(paragraphOf(kids))

        val seams = ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("more than one piece, got ${pieces.size}", pieces.size > 1)
        pieces.forEach {
            assertEquals("every piece is a plain Paragraph", Paragraph::class.java, it.javaClass)
            assertTrue("a piece has at most $limit children, got ${childrenOf(it).size}", childrenOf(it).size <= limit)
        }
        val moved = pieces.flatMap { childrenOf(it) }
        assertEquals(kids.size, moved.size)
        kids.forEachIndexed { i, kid -> assertSame("child $i is the original object, in order", kid, moved[i]) }
        moved.filterIsInstance<StrongEmphasis>().forEach {
            assertSame("a strong node keeps its one child", it.firstChild, it.lastChild)
            assertTrue(it.firstChild is Text)
        }

        assertFalse(seams.continuesPrevious(pieces.first()))
        assertTrue(seams.continuesNext(pieces.first()))
        pieces.subList(1, pieces.lastIndex).forEach {
            assertTrue(seams.continuesPrevious(it))
            assertTrue(seams.continuesNext(it))
        }
        assertTrue(seams.continuesPrevious(pieces.last()))
        assertFalse(seams.continuesNext(pieces.last()))
        val stranger = Paragraph()
        assertFalse("a node that is not a piece", seams.continuesPrevious(stranger))
        assertFalse("a node that is not a piece", seams.continuesNext(stranger))
    }

    @Test
    fun `a cut through a text that ends mid-word hands the last word to the next piece`() {
        val kids = alternating(300).toMutableList()
        kids[limit - 1] = Text("foo bar")
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val first = pieces[0].lastChild as Text
        assertSame(kids[limit - 1], first)
        assertEquals("foo ", first.literal)
        val second = pieces[1].firstChild as Text
        assertEquals("bar", second.literal)
        assertEquals("the concatenated text is unchanged", "foo bar", first.literal + second.literal)
    }

    @Test
    fun `a line break inside the cut window is the cut and is dropped`() {
        val kids = alternating(300)
        val lineBreak = SoftLineBreak()
        val withBreak = kids.subList(0, 101) + lineBreak + kids.subList(101, 300)
        val document = documentOf(paragraphOf(withBreak))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val first = childrenOf(pieces[0])
        assertEquals(101, first.size)
        assertSame(kids[100], first.last())
        assertSame(kids[101], pieces[1].firstChild)
        assertFalse("the line break belongs to no piece", pieces.flatMap { childrenOf(it) }.any { it === lineBreak })
    }

    @Test
    fun `a paragraph of exactly 150 children is untouched`() {
        val paragraph = paragraphOf(alternating(limit))
        val document = documentOf(paragraph)

        val seams = ParagraphSplitter.split(document)

        assertSame(paragraph, document.firstChild)
        assertSame(paragraph, document.lastChild)
        assertEquals(limit, childrenOf(paragraph).size)
        assertFalse(seams.continuesPrevious(paragraph))
        assertFalse(seams.continuesNext(paragraph))
    }

    @Test
    fun `a 1000 child paragraph inside a quote is untouched`() {
        val paragraph = paragraphOf(alternating(1000))
        val quote = BlockQuote().also { it.appendChild(paragraph) }
        val document = documentOf(quote)

        ParagraphSplitter.split(document)

        assertSame(quote, document.firstChild)
        assertSame(paragraph, quote.firstChild)
        assertSame(paragraph, quote.lastChild)
        assertEquals(1000, childrenOf(paragraph).size)
    }

    @Test
    fun `a 1000 child paragraph inside a list item is untouched`() {
        val paragraph = paragraphOf(alternating(1000))
        val item = ListItem().also { it.appendChild(paragraph) }
        val list = BulletList().also { it.appendChild(item) }
        val document = documentOf(list)

        ParagraphSplitter.split(document)

        assertSame(list, document.firstChild)
        assertSame(paragraph, item.firstChild)
        assertSame(paragraph, item.lastChild)
        assertEquals(1000, childrenOf(paragraph).size)
    }

    private fun assertTravelsWhole(kids: List<Node>) {
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("it was cut", pieces.size > 1)
        val moved = pieces.flatMap { childrenOf(it) }
        assertEquals(kids.size, moved.size)
        kids.forEachIndexed { i, kid -> assertSame("child $i travels as the same object", kid, moved[i]) }
    }

    @Test
    fun `a code span travels whole`() {
        assertTravelsWhole(List(700) { i -> if (i % 3 == 2) Code("c$i") else Text("t$i ") })
    }

    @Test
    fun `a link and a strong node travel whole with their children`() {
        val links = List(700) { i ->
            if (i % 3 == 2) Link("https://x", null).also { it.appendChild(Text("l$i")) } else Text("t$i ")
        }
        assertTravelsWhole(links)
        links.filterIsInstance<Link>().forEach {
            assertSame("a link keeps its one child", it.firstChild, it.lastChild)
            assertTrue(it.firstChild is Text)
        }

        val strongs = alternating(700)
        assertTravelsWhole(strongs)
        strongs.filterIsInstance<StrongEmphasis>().forEach {
            assertSame("a strong node keeps its one child", it.firstChild, it.lastChild)
            assertTrue(it.firstChild is Text)
        }
    }

    @Test
    fun `a heading between two oversized paragraphs keeps its place between their pieces`() {
        val heading = Heading().also {
            it.level = 2
            it.appendChild(Text("Between"))
        }
        val document = documentOf(paragraphOf(alternating(400)), heading, paragraphOf(alternating(400)))

        val seams = ParagraphSplitter.split(document)

        val blocks = blocksOf(document)
        val at = blocks.indexOfFirst { it === heading }
        assertTrue("pieces before the heading", at > 1)
        assertTrue("pieces after the heading", blocks.lastIndex - at > 1)
        blocks.subList(0, at).forEach { assertTrue(it is Paragraph) }
        blocks.subList(at + 1, blocks.size).forEach { assertTrue(it is Paragraph) }
        assertFalse("the last piece before the heading ends there", seams.continuesNext(blocks[at - 1]))
        assertFalse("the first piece after the heading starts there", seams.continuesPrevious(blocks[at + 1]))
        assertFalse(seams.continuesPrevious(heading))
        assertFalse(seams.continuesNext(heading))
    }

    // Window edges. A piece may hold 150 children, so the cut window of a piece starting at 0 is
    // indexes 75 (the half) to 150 (the limit), both inclusive for a line break.

    private fun withReplaced(count: Int, vararg replacements: Pair<Int, Node>): List<Node> =
        alternating(count).toMutableList().also { kids -> replacements.forEach { (i, node) -> kids[i] = node } }

    @Test
    fun `a line break at exactly index 150 is the cut`() {
        val kids = withReplaced(300, 150 to SoftLineBreak())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals(listOf(150, 149), pieces.map { childrenOf(it).size })
        assertSame(kids[149], pieces[0].lastChild)
        assertSame(kids[151], pieces[1].firstChild)
        assertFalse(pieces.flatMap { childrenOf(it) }.any { it === kids[150] })
    }

    @Test
    fun `a line break at exactly index 75 is taken only when no later break is in the window`() {
        val alone = withReplaced(300, 75 to SoftLineBreak())
        val aloneDocument = documentOf(paragraphOf(alone))
        ParagraphSplitter.split(aloneDocument)
        val aloneFirst = childrenOf(blocksOf(aloneDocument)[0])
        assertEquals("the break at the half of the window is the cut", 75, aloneFirst.size)
        assertSame(alone[76], blocksOf(aloneDocument)[1].firstChild)

        val later = withReplaced(300, 75 to SoftLineBreak(), 100 to SoftLineBreak())
        val laterDocument = documentOf(paragraphOf(later))
        ParagraphSplitter.split(laterDocument)
        assertEquals("the later break wins", 100, childrenOf(blocksOf(laterDocument)[0]).size)
    }

    @Test
    fun `a break just before the half of the window is not taken`() {
        val kids = withReplaced(300, 74 to SoftLineBreak())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val first = childrenOf(blocksOf(document)[0])
        assertTrue("the break at 74 is outside the window, got ${first.size} children", first.size > 74)
        assertTrue("and it is kept in a piece", blocksOf(document).flatMap { childrenOf(it) }.any { it === kids[74] })
    }

    @Test
    fun `a paragraph of 151 children becomes a piece ending at the last text and a piece of two`() {
        // Observed: the last text child in the window 75..149 is index 148 ("t148 ", ends in a space),
        // so the cut falls after it: 149 children, then 2.
        val kids = alternating(151)
        val document = documentOf(paragraphOf(kids))

        val seams = ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals(listOf(149, 2), pieces.map { childrenOf(it).size })
        assertSame(kids[148], pieces[0].lastChild)
        assertSame(kids[149], pieces[1].firstChild)
        assertTrue(seams.continuesNext(pieces[0]))
        assertTrue(seams.continuesPrevious(pieces[1]))
    }

    @Test
    fun `a window with no text in its back half is cut at exactly 150`() {
        val strongs = (75..149).map { it to (StrongEmphasis().also { s -> s.appendChild(Text("s$it")) } as Node) }
        val kids = withReplaced(300, *strongs.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals(150, childrenOf(pieces[0]).size)
        assertSame(kids[149], pieces[0].lastChild)
        assertSame(kids[150], pieces[1].firstChild)
        assertEquals("nothing was inserted", 300, pieces.flatMap { childrenOf(it) }.size)
    }

    @Test
    fun `a text with no whitespace at the cut is left whole`() {
        val kids = withReplaced(300, 149 to Text("foobar"))
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val moved = pieces.flatMap { childrenOf(it) }
        assertEquals("no Text was inserted", 300, moved.size)
        kids.forEachIndexed { i, kid -> assertSame(kid, moved[i]) }
        assertEquals("foobar", (pieces[0].lastChild as Text).literal)
        assertSame(kids[150], pieces[1].firstChild)
    }

    private fun html(literal: String) = HtmlInline().also { it.literal = literal }

    private fun pieceHolding(pieces: List<Node>, child: Node): Int =
        pieces.indexOfFirst { piece -> childrenOf(piece).any { it === child } }

    @Test
    fun `a cut is never taken between an inline HTML tag and its closing tag`() {
        val open = html("<u>")
        val close = html("</u>")
        val inside = (141..160).map { it to (Text(" word ") as Node) }
        val kids = withReplaced(400, 140 to open, 161 to close, *inside.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("it was cut", pieces.size > 1)
        val openIn = pieceHolding(pieces, open)
        assertTrue(openIn >= 0)
        assertEquals("both tags in the same piece", openIn, pieceHolding(pieces, close))
        inside.forEach { (_, text) -> assertEquals(openIn, pieceHolding(pieces, text)) }
        pieces.forEach { assertTrue(childrenOf(it).size <= 150) }
    }

    @Test
    fun `a comment and a self-closing tag do not count as open tags`() {
        val comment = html("<!-- x -->")
        val br = html("<br/>")
        val texts = (140..160).map { it to (Text(" word ") as Node) }
        val kids = withReplaced(400, 138 to comment, 139 to br, *texts.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val first = childrenOf(blocksOf(document)[0])
        assertEquals("the cut falls after index 149, past the comment and the br", 150, first.size)
        assertSame(comment, first[138])
        assertSame(br, first[139])
    }

    @Test
    fun `a void element without a slash does not keep the tag depth open`() {
        val br = html("<br>")
        val texts = (140..145).map { it to (Text(" word ") as Node) }
        val kids = withReplaced(400, 139 to br, *texts.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val first = childrenOf(blocksOf(document)[0])
        assertEquals("the cut falls after the last text before the limit, not before the br", 149, first.size)
        assertSame(br, first[139])
    }

    @Test
    fun `a stray closing tag of a void element does not close the pair around it`() {
        val open = html("<u>")
        val strayClose = html("</br>")
        val close = html("</u>")
        val inside = (142..160).map { it to (Text(" word ") as Node) }
        val kids = withReplaced(400, 140 to open, 141 to strayClose, 161 to close, *inside.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val openIn = pieceHolding(pieces, open)
        assertTrue(openIn >= 0)
        assertEquals("both tags in the same piece", openIn, pieceHolding(pieces, close))
    }

    @Test
    fun `a custom element whose name starts like a void element is a pair`() {
        val open = html("<br-note>")
        val close = html("</br-note>")
        val inside = (141..160).map { it to (Text(" word ") as Node) }
        val kids = withReplaced(400, 140 to open, 161 to close, *inside.toTypedArray())
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val openIn = pieceHolding(pieces, open)
        assertTrue(openIn >= 0)
        assertEquals("both tags in the same piece", openIn, pieceHolding(pieces, close))
    }

    // Every cut through these is mid-word, so every cut carries a word into the next piece.

    private fun midWordTexts(count: Int): List<Node> = List(count) { i -> Text("a$i b$i") }

    private fun literalsOf(nodes: List<Node>) =
        nodes.joinToString("") { (it as? Text)?.literal ?: "<${it.javaClass.simpleName}>" }

    private fun assertSplitsCleanly(kids: List<Node>) {
        val before = literalsOf(kids)
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("${kids.size} children: it was cut", pieces.size > 1)
        pieces.forEach {
            val size = childrenOf(it).size
            assertTrue("${kids.size} children: a piece has at most $limit children, got $size", size <= limit)
        }
        val after = literalsOf(pieces.flatMap { childrenOf(it) })
        assertEquals("${kids.size} children: the text is unchanged", before, after)
    }

    @Test
    fun `a 449 child paragraph cut mid-word at every cut keeps every piece and all its text`() {
        // 449 is the first size where planning against a list that the cuts had grown threw.
        assertSplitsCleanly(midWordTexts(449))
    }

    @Test
    fun `every size from 151 to 2000 of mid-word texts splits without throwing`() {
        (151..2000).forEach { assertSplitsCleanly(midWordTexts(it)) }
    }

    @Test
    fun `a word carried over by an earlier cut does not let a later cut land inside a tag pair`() {
        // The first cut falls after 149 and carries "b149", so the second piece starts with it and
        // ends at snapshot index 298 at the latest. The last text outside the pair is 279.
        val open = html("<u>")
        val close = html("</u>")
        val kids = midWordTexts(400).toMutableList().also {
            it[280] = open
            it[298] = close
        }
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val openIn = pieceHolding(pieces, open)
        assertTrue(openIn >= 0)
        assertEquals("both tags in the same piece", openIn, pieceHolding(pieces, close))
        (281..297).forEach { assertEquals("text $it stays inside the pair", openIn, pieceHolding(pieces, kids[it])) }
        assertSame("the second piece ends at the last text before the pair", kids[279], pieces[1].lastChild)
        pieces.forEach { assertTrue(childrenOf(it).size <= limit) }
    }

    @Test
    fun `a line break that is the last child leaves no empty piece`() {
        val kids = withReplaced(151, 150 to SoftLineBreak())
        val document = documentOf(paragraphOf(kids))

        val seams = ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals("one piece, no empty trailing one", listOf(150), pieces.map { childrenOf(it).size })
        assertFalse(seams.continuesNext(pieces[0]))
        assertFalse(seams.continuesPrevious(pieces[0]))
    }

    // S8-A61: a character limit next to the child count. One unbroken run is one Text child, so the
    // count rule can never fire on these fixtures: only the character limit can cut them.

    private val maxChars = LongLineCut.MAX_BLOCK_CHARS

    /** The characters a block paints from its Text and Code nodes, in order. */
    private fun textOf(node: Node): String = buildString {
        fun walk(n: Node) {
            when (n) {
                is Text -> append(n.literal)
                is Code -> append(n.literal)
                else -> generateSequence(n.firstChild) { it.next }.forEach { walk(it) }
            }
        }
        walk(node)
    }

    /** Every node under [node], depth first. */
    private fun descendants(node: Node): List<Node> = childrenOf(node).flatMap { listOf(it) + descendants(it) }

    @Test
    fun `one 20K run with no space becomes at least three pieces within the character limit`() {
        val run = "x".repeat(20_000)
        val document = documentOf(paragraphOf(listOf(Text(run))))

        val seams = ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("at least three pieces, got ${pieces.size}", pieces.size >= 3)
        pieces.forEach {
            assertEquals(Paragraph::class.java, it.javaClass)
            val chars = textOf(it).length
            assertTrue("a piece paints at most $maxChars chars, got $chars", chars <= maxChars)
        }
        assertEquals("every character, once, in order", run, pieces.joinToString("") { textOf(it) })
        assertFalse(seams.continuesPrevious(pieces.first()))
        pieces.drop(1).forEach { assertTrue(seams.continuesPrevious(it)) }
        pieces.dropLast(1).forEach { assertTrue(seams.continuesNext(it)) }
    }

    @Test
    fun `a run with one space near the limit is cut just after the space`() {
        val head = "a".repeat(maxChars - 10) + " "
        val run = head + "b".repeat(12_000)
        val document = documentOf(paragraphOf(listOf(Text(run))))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals("the first piece ends on the space", head, textOf(pieces[0]))
        assertEquals(run, pieces.joinToString("") { textOf(it) })
    }

    @Test
    fun `a paragraph under the child limit is still cut when its characters are over`() {
        // 140 children: under the count limit. 100 chars each: 14,000 chars, over the character one.
        val kids = List(140) { Text("t".repeat(99) + " ") }
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("more than one piece, got ${pieces.size}", pieces.size > 1)
        pieces.forEach { assertTrue(textOf(it).length <= maxChars) }
        val moved = pieces.flatMap { childrenOf(it) }
        kids.forEachIndexed { i, kid -> assertSame("child $i is the original object, in order", kid, moved[i]) }
    }

    @Test
    fun `a word carried over a cut by the character limit starts the next piece`() {
        // 7,009 + 2,000 chars do not fit one piece; the text runs into the emphasis mid-word, so its
        // last word goes to the next piece with it.
        val text = Text("w".repeat(7_000) + " tailword")
        val emphasis = org.commonmark.node.Emphasis("*").also { it.appendChild(Text("e".repeat(2_000))) }
        val document = documentOf(paragraphOf(listOf(text, emphasis)))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertEquals(2, pieces.size)
        assertEquals("w".repeat(7_000) + " ", textOf(pieces[0]))
        assertEquals("tailword" + "e".repeat(2_000), textOf(pieces[1]))
        assertSame(emphasis, pieces[1].lastChild)
    }

    @Test
    fun `several long texts that each carry a word still end, every piece within the limit`() {
        // Three 6,500-char texts with spaces, each running into the next mid-word: every cut carries a
        // word, and two neighbours never fit one piece. The cut must still move on each time.
        val kids = List(3) { Text("w".repeat(3_000) + " " + "v".repeat(3_499)) }
        val document = documentOf(paragraphOf(kids))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue("more than one piece, got ${pieces.size}", pieces.size > 1)
        pieces.forEach { assertTrue(textOf(it).length <= 2 * maxChars) }
        assertEquals(kids.size * 6_500, pieces.sumOf { textOf(it).length })
    }

    @Test
    fun `a short paragraph is left as the very node the parser built`() {
        val text = Text("y".repeat(maxChars))
        val paragraph = paragraphOf(listOf(text))
        val document = documentOf(paragraph)

        ParagraphSplitter.split(document)

        assertSame(paragraph, document.firstChild)
        assertSame(text, paragraph.firstChild)
    }

    @Test
    fun `bold across the cut stays bold on both sides`() {
        val bold = StrongEmphasis().also { it.appendChild(Text("b".repeat(20_000))) }
        val document = documentOf(paragraphOf(listOf(Text("lead "), bold, Text(" tail"))))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        assertTrue(pieces.size >= 3)
        assertEquals("lead " + "b".repeat(20_000) + " tail", pieces.joinToString("") { textOf(it) })
        pieces.forEach { piece ->
            assertTrue(textOf(piece).length <= maxChars)
            descendants(piece).filterIsInstance<Text>().filter { 'b' in it.literal }.forEach {
                assertTrue("every bold char is under a StrongEmphasis", it.parent is StrongEmphasis)
            }
        }
    }

    @Test
    fun `a link and a code span across the cut keep their kind and destination on every side`() {
        val link = Link("https://example.com/x", "title").also { it.appendChild(Text("l".repeat(20_000))) }
        val code = Code("c".repeat(20_000))
        val document = documentOf(paragraphOf(listOf(link, Text(" "), code)))

        ParagraphSplitter.split(document)

        val pieces = blocksOf(document)
        val links = pieces.flatMap { descendants(it) }.filterIsInstance<Link>()
        assertTrue("the link is drawn in several pieces, got ${links.size}", links.size >= 3)
        links.forEach {
            assertEquals("https://example.com/x", it.destination)
            assertEquals("title", it.title)
        }
        assertEquals("l".repeat(20_000), links.joinToString("") { textOf(it) })
        val codes = pieces.flatMap { descendants(it) }.filterIsInstance<Code>()
        assertTrue(codes.size >= 3)
        assertEquals("c".repeat(20_000), codes.joinToString("") { it.literal })
        pieces.forEach { assertTrue(textOf(it).length <= maxChars) }
    }

    @Test
    fun `emoji and CJK runs are never cut inside a character`() {
        // One char in front, so a cut at the bare limit would land inside a pair or a sequence.
        val emoji = "a" + "\uD83D\uDE00".repeat(10_000)
        val family = "a" + "\uD83D\uDC69\u200D\uD83D\uDCBB".repeat(4_000)
        val cjk = "a" + "\uD840\uDC00漢".repeat(7_000)
        for (run in listOf(emoji, family, cjk)) {
            val document = documentOf(paragraphOf(listOf(Text(run))))

            ParagraphSplitter.split(document)

            val pieces = blocksOf(document).map { textOf(it) }
            assertTrue(pieces.size >= 3)
            assertEquals(run, pieces.joinToString(""))
            pieces.forEach {
                assertFalse("no piece starts inside a pair", it.first().isLowSurrogate())
                assertFalse("no piece starts on the second half of a sequence", it.startsWith("\uD83D\uDCBB"))
                assertFalse("no piece starts with a joiner", it.first() == '\u200D')
                assertFalse("no piece ends inside a pair", it.last().isHighSurrogate())
                assertFalse("no piece ends with a joiner", it.last() == '\u200D')
            }
        }
    }
}
