// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the footnote block parser and the post-parse transform.
 *
 * The defect-fixing cases are first and deliberately explicit: before this change a single-token
 * body made the reference a real `Link` to a garbage destination, and an indented continuation
 * rendered as a code block. Those are regression guards, not decoration.
 */
class FootnotesTest {

    private fun parse(markdown: String): Document = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .customBlockParserFactory(FootnoteBlockParserFactory())
        .build()
        .parse(markdown) as Document

    private fun transformed(markdown: String): Document = Footnotes.transform(parse(markdown)) as Document

    private fun topLevel(doc: Document): List<Node> = buildList {
        var n = doc.firstChild
        while (n != null) {
            add(n)
            n = n.next
        }
    }

    private fun descendants(node: Node): List<Node> = buildList {
        var c = node.firstChild
        while (c != null) {
            add(c)
            addAll(descendants(c))
            c = c.next
        }
    }

    private fun visibleText(node: Node): String =
        descendants(node).filterIsInstance<Text>().joinToString("") { it.literal }

    private fun references(doc: Document): List<FootnoteReference> =
        descendants(doc).filterIsInstance<FootnoteReference>()

    private fun definitions(doc: Document): List<FootnoteDefinitionBlock> =
        topLevel(doc).filterIsInstance<FootnoteDefinitionBlock>()

    // --- the three measured defects this change exists to fix ---

    @Test
    fun singleTokenBodyIsNoLongerALinkReferenceDefinition() {
        // THE defect: `[^1]: body` is valid CommonMark link-reference-definition syntax, so `[^1]`
        // used to render as a tappable Link to the destination "body" and the definition vanished.
        val doc = transformed("Real[^1] ref.\n\n[^1]: body\n")
        assertTrue("no Link may survive", descendants(doc).none { it is Link })
        assertTrue("no LinkReferenceDefinition", topLevel(doc).none { it is LinkReferenceDefinition })
        assertEquals(1, definitions(doc).size)
        assertEquals("body", visibleText(definitions(doc)[0]).trim())
    }

    @Test
    fun indentedContinuationIsProseNotACodeBlock() {
        // Used to render the second paragraph of the note as a dark code block mid-prose.
        val doc = transformed("Body[^n].\n\n[^n]: First para.\n\n    Second para of the note.\n\nAfter.\n")
        val definition = definitions(doc).single()
        assertTrue("no code block inside the note", descendants(definition).none { it is IndentedCodeBlock })
        assertEquals(2, descendants(definition).filterIsInstance<Paragraph>().size)
        assertTrue(visibleText(definition).contains("Second para of the note."))
        assertTrue("prose after the note is unaffected", topLevel(doc).any { visibleText(it) == "After." })
    }

    @Test
    fun multiWordBodyIsNoLongerAStrayParagraph() {
        val doc = transformed("Ref[^1].\n\n[^1]: The footnote body.\n")
        assertEquals(1, definitions(doc).size)
        val strayDefinitionParagraph = topLevel(doc).any {
            it is Paragraph && visibleText(it).startsWith("[^1]")
        }
        assertTrue("body is not a bare top-level paragraph", !strayDefinitionParagraph)
    }

    // --- the block parser must not hijack anything else ---

    @Test
    fun genuineLinkReferenceDefinitionsStillResolve() {
        val doc = transformed("See [ref] here.\n\n[ref]: https://example.com\n")
        val link = descendants(doc).filterIsInstance<Link>().single()
        assertEquals("https://example.com", link.destination)
    }

    @Test
    fun definitionSyntaxInsideCodeIsNotConsumed() {
        val fenced = transformed("```\n[^1]: not a footnote\n```\n")
        assertTrue(topLevel(fenced).single() is FencedCodeBlock)
        assertEquals(0, definitions(fenced).size)

        val indented = transformed("    [^1]: indented\n")
        assertTrue(topLevel(indented).single() is IndentedCodeBlock)
        assertEquals(0, definitions(indented).size)
    }

    @Test
    fun definitionBodyKeepsInlineFormatting() {
        val doc = transformed("Ref[^1].\n\n[^1]: body with **bold** inside.\n")
        assertTrue(descendants(definitions(doc).single()).any { it is StrongEmphasis })
    }

    // --- numbering and resolution rules (verified against GitHub's renderer) ---

    @Test
    fun markersAreNumberedByOrderOfFirstReference() {
        // `[^note]` is referenced first, so it is 1 even though `[^1]` is defined first.
        val doc = transformed("A[^note] B[^one] C[^note].\n\n[^one]: first\n[^note]: named\n")
        assertEquals(listOf(1, 2, 1), references(doc).map { it.ordinal })
        assertEquals(listOf("note", "one", "note"), references(doc).map { it.label })
        assertEquals(1, definitions(doc).first { it.label == "note" }.ordinal)
        assertEquals(2, definitions(doc).first { it.label == "one" }.ordinal)
    }

    @Test
    fun orphanReferenceStaysLiteral() {
        val doc = transformed("Only a ref[^missing] here.\n\n[^other]: body\n")
        assertEquals(0, references(doc).size)
        assertTrue(visibleText(topLevel(doc).first()).contains("[^missing]"))
    }

    @Test
    fun unreferencedDefinitionIsRenderedWithNoOrdinal() {
        // GitHub omits it entirely; we never drop content the user wrote.
        val doc = transformed("No refs here.\n\n[^unused]: still visible\n")
        val definition = definitions(doc).single()
        assertNull("no ordinal to show", definition.ordinal)
        assertEquals("still visible", visibleText(definition).trim())
    }

    @Test
    fun duplicateDefinitionsKeepFirstAndStillRenderTheSecond() {
        val doc = transformed("Dup[^d].\n\n[^d]: first body\n[^d]: second body\n")
        assertEquals("both definitions survive", 2, definitions(doc).size)
        assertEquals(1, definitions(doc)[0].ordinal)
        assertNull("the duplicate resolves to nothing", definitions(doc)[1].ordinal)
        assertTrue(visibleText(definitions(doc)[1]).contains("second body"))
    }

    @Test
    fun referenceInsideACodeSpanStaysLiteral() {
        val doc = transformed("Code `[^1]` and real[^1].\n\n[^1]: body\n")
        assertEquals("only the prose one becomes a marker", 1, references(doc).size)
    }

    @Test
    fun referenceInsideATableCellResolves() {
        val doc = transformed("| x[^1] | y |\n|---|---|\n| a | b |\n\n[^1]: body\n")
        assertEquals(1, references(doc).size)
    }

    @Test
    fun referenceInsideAFootnoteBodyStaysLiteralForNow() {
        // Documented v1.0.4 scope fence: nested footnotes are not resolved.
        val doc = transformed("Ref[^a].\n\n[^a]: see also [^b]\n\n[^b]: other\n")
        assertEquals(1, references(doc).size)
        assertEquals("a", references(doc).single().label)
    }

    // --- the untouched-path guarantee ---

    @Test
    fun aDocumentWithNoDefinitionsIsReturnedUntouched() {
        // Same reference, and the same Document instance: the transform must be a no-op without
        // definitions, so every ordinary .md pays nothing (the CeMacroShim discipline).
        val doc = parse("# Heading\n\nA paragraph with a[^1] orphan.\n")
        val before = topLevel(doc).map { it.javaClass.simpleName }
        assertSame(doc, Footnotes.transform(doc))
        assertEquals(before, topLevel(doc).map { it.javaClass.simpleName })
        assertEquals(0, references(doc).size)
    }

    // --- jump targets (top-level index == MarkwonAdapter position) ---

    @Test
    fun markerCarriesTheDefinitionsTopLevelPosition() {
        // Blocks: 0 = paragraph, 1 = heading, 2 = definition. The marker must point at 2 — that is
        // the RecyclerView position the tap scrolls to, with no extra lookup.
        val doc = transformed("See[^1].\n\n# Notes\n\n[^1]: body\n")
        assertEquals(2, references(doc).single().definitionBlockIndex)
        assertEquals(2, definitions(doc).single().blockIndex)
    }

    @Test
    fun definitionLinksBackToTheBlockOfItsFirstReferenceOnly() {
        // Two references in two different paragraphs; the back-link targets the first (blocks:
        // 0 = "First A", 1 = "Then A again", 2 = definition).
        val doc = transformed("First[^a].\n\nThen[^a] again.\n\n[^a]: body\n")
        assertEquals(0, definitions(doc).single().firstReferenceBlockIndex)
        assertEquals(listOf(2, 2), references(doc).map { it.definitionBlockIndex })
    }

    @Test
    fun anUnreferencedDefinitionHasNoBackLinkTarget() {
        val doc = transformed("Nothing points here.\n\n[^unused]: body\n")
        assertEquals(NO_BLOCK, definitions(doc).single().firstReferenceBlockIndex)
    }

    @Test
    fun aReferenceInsideANestedBlockReportsItsTopLevelBlock() {
        // The index must be the TOP-LEVEL block, not the nesting depth: a marker inside a list item
        // scrolls to the definition, and the definition scrolls back to the whole list block.
        val doc = transformed("Intro.\n\n- item one\n- item[^n] two\n\n[^n]: body\n")
        assertEquals("the list is block 1", 1, definitions(doc).single().firstReferenceBlockIndex)
        assertEquals(2, references(doc).single().definitionBlockIndex)
    }

    @Test
    fun splittingPreservesTheSurroundingLiteralTextExactly() {
        // The split must lose nothing: prose either side of the marker is preserved verbatim, which
        // is what keeps the search cursor's forward walk aligned.
        val doc = transformed("before [^1] after\n\n[^1]: body\n")
        val paragraph = topLevel(doc).first()
        assertEquals("before  after", visibleText(paragraph))
        assertEquals(1, references(doc).size)
    }
}
