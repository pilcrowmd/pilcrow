// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SearchMarkdownUseCase.
 * Verifies behavior parity with the ViewModel's original findSearchMatches method.
 * Particularly tests case-insensitivity and the per-block occurrence ordinal.
 */
class SearchMarkdownUseCaseTest {
    private val parseHeadingsUseCase = ParseMarkdownHeadingsUseCase()
    private val useCase = SearchMarkdownUseCase(parseHeadingsUseCase)

    @Test
    fun testFindSearchMatchesWithSingleMatch() {
        val content = "Hello world"
        val query = "world"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(1, matches.size)
        assertEquals("world", matches[0].content)
        assertEquals(6, matches[0].startIndex)
        assertEquals(0, matches[0].occurrenceInBlock)
    }

    @Test
    fun testFindSearchMatchesWithMultipleMatches() {
        val content = "apple is great and apple pie"
        val query = "apple"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(2, matches.size)
        // First match
        assertEquals(0, matches[0].startIndex)
        // Both should be found
        assertEquals("apple", matches[0].content)
        assertEquals("apple", matches[1].content)
    }

    @Test
    fun testFindSearchMatchesCaseInsensitive() {
        val content = "Hello WORLD hello"
        val query = "HELLO"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(2, matches.size)
        assertEquals("Hello", matches[0].content)
        assertEquals("hello", matches[1].content)
    }

    @Test
    fun testFindSearchMatchesEmptyQuery() {
        val content = "Some content"
        val query = ""
        val matches = useCase.findSearchMatches(content, query)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun testFindSearchMatchesNoMatches() {
        val content = "The quick brown fox"
        val query = "zebra"
        val matches = useCase.findSearchMatches(content, query)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun testFindSearchMatchesEmptyContent() {
        val content = ""
        val query = "search"
        val matches = useCase.findSearchMatches(content, query)

        assertTrue(matches.isEmpty())
    }

    @Test
    fun testFindSearchMatchesWithOverlappingMatches() {
        val content = "aaa"
        val query = "aa"
        val matches = useCase.findSearchMatches(content, query)

        // Should find overlapping matches
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].startIndex)
        assertEquals(1, matches[1].startIndex)
    }

    @Test
    fun testFindSearchMatchesPreservesContent() {
        val content = "The quick brown fox jumps over the lazy dog"
        val query = "the"
        val matches = useCase.findSearchMatches(content, query)

        // Should find "The" and "the" (case-insensitive)
        assertTrue(matches.size >= 2)
        // Verify that content field matches the actual matched substring
        assertEquals(content.substring(matches[0].startIndex, matches[0].startIndex + 3), matches[0].content)
    }

    @Test
    fun testFindSearchMatchesWithPunctuation() {
        val content = "Hello, world! Hello?"
        val query = "hello"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(2, matches.size)
        assertEquals("Hello", matches[0].content)
        assertEquals("Hello", matches[1].content)
    }

    @Test
    fun testFindSearchMatchesWithLargeContent() {
        // Build a larger content string with multiple paragraphs
        val sb = StringBuilder()
        for (i in 0..20) {
            sb.append("test content line $i\n\n")
        }
        val content = sb.toString()
        val query = "test"
        val matches = useCase.findSearchMatches(content, query)

        // Should find "test" in every line
        assertTrue(matches.size > 10)
    }

    @Test
    fun testFindSearchMatchesSpecialCharacterEscaping() {
        val content = "Email: user@example.com"
        val query = "@"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(1, matches.size)
        assertEquals("@", matches[0].content)
        assertTrue(matches[0].startIndex > 10) // @ is at position 12
    }

    @Test
    fun testFindSearchMatchesOccurrenceOrderingCritical() {
        // Test that occurrence ordinals are assigned correctly
        val content = "apple and apple"
        val query = "apple"
        val matches = useCase.findSearchMatches(content, query)

        assertEquals(2, matches.size)
        // Both in same block - should have different occurrence numbers
        assertTrue(matches[0].occurrenceInBlock < matches[1].occurrenceInBlock)
    }

    /**
     * REGRESSION: a match after a GFM table must resolve to the correct
     * RecyclerView block, not overshoot. The old text-literal-length block model put match offsets
     * (raw source) and block starts (text-only) in different coordinate systems, so a match after a
     * markup-dense table mapped to a far-too-high adapter position → scroll-to-match jumped to the
     * bottom of the document with the real match off-screen.
     *
     * Top-level blocks (markwon adapter / parity parser):
     *   0 = intro paragraph, 1 = table, 2 = "Tail native.", 3..6 = filler paragraphs.
     * "native" occurs in block 0 (intro), block 1 (a table cell), block 2 (tail) → adapterPosition
     * must be exactly [0, 1, 2].
     */
    @Test
    fun testFindSearchMatchesAfterTableMapsToCorrectBlock() {
        val content = buildString {
            append("Intro paragraph mentions native once.\n\n")
            append("| Alpha Column | Beta Column  | Gamma Column |\n")
            append("| ------------ | ------------ | ------------ |\n")
            append("| native here  | padding xxxx | padding yyyy |\n")
            append("| row two aaaa | padding xxxx | padding yyyy |\n")
            append("| row three bb | padding xxxx | padding yyyy |\n")
            append("| row four ccc | padding xxxx | padding yyyy |\n")
            append("| row five ddd | padding xxxx | padding yyyy |\n")
            append("| row six eeee | padding xxxx | padding yyyy |\n\n")
            append("Tail native.\n\n")
            append("Filler one.\n\nFiller two.\n\nFiller three.\n\nFiller four.")
        }
        val matches = useCase.findSearchMatches(content, "native")

        assertEquals(3, matches.size)
        assertEquals("intro match → block 0", 0, matches[0].adapterPosition)
        assertEquals("table-cell match → block 1", 1, matches[1].adapterPosition)
        assertEquals("post-table match → block 2 (not an overshoot past it)", 2, matches[2].adapterPosition)
    }

    /**
     * REGRESSION: consecutive code blocks must map matches to the
     * correct block. Fenced/indented code blocks store their content in `.literal`, not as child
     * `Text` nodes, so the original `firstTextLiteral` (Text-only visitor) returned null for them →
     * `locateBlockStart` fell back to the unchanged cursor → consecutive code blocks collapsed to
     * DUPLICATE offsets in `blockStarts`. With the binary search resolving ties to the largest index,
     * a match inside the FIRST of two consecutive code blocks mis-mapped to the SECOND.
     *
     * Top-level blocks: 0 = intro paragraph, 1 = first code block (contains the match), 2 = second
     * code block, 3 = closing paragraph. The single "targetzz" lives only in block 1 → adapterPosition
     * must be exactly 1 (was 2 before the fix).
     */
    @Test
    fun testFindSearchMatchesInFirstOfConsecutiveCodeBlocksMapsToCorrectBlock() {
        val content = buildString {
            append("Intro paragraph here.\n\n")
            append("```\n")
            append("val codeAlpha = targetzz\n")
            append("```\n\n")
            append("```\n")
            append("val codeBravo = plain\n")
            append("```\n\n")
            append("Closing paragraph here.\n")
        }
        val matches = useCase.findSearchMatches(content, "targetzz")

        assertEquals(1, matches.size)
        assertEquals("match in the first code block → block 1, not the second", 1, matches[0].adapterPosition)
    }

    /**
     * DEFECT 1: a hit inside a link DESTINATION is not rendered as visible text — the reader
     * shows only the link label. Counting it inflates the result count and creates a phantom match the
     * highlighter/scroll can never reach. Only the visible label occurrence must count.
     *
     * "Visit [Free trial](https://free.example.org/free) now." — raw has "free" 3× (label + host + path);
     * rendered prose shows "Visit Free trial now." → exactly 1 visible "Free" (the label, at raw offset 7).
     */
    @Test
    fun testLinkDestinationOccurrencesAreNotCounted() {
        val content = "Visit [Free trial](https://free.example.org/free) now."
        val matches = useCase.findSearchMatches(content, "free")

        assertEquals(1, matches.size)
        assertEquals("startIndex points at the visible label, not the URL", 7, matches[0].startIndex)
        assertEquals(0, matches[0].occurrenceInBlock)
        assertEquals("Free", matches[0].content)
    }

    /**
     * DEFECT 1/2: the highlighter scans the whole concatenated TextView, so a query straddling
     * two inline nodes of different styling (`**fre**e` → rendered "free") IS painted. The use case must
     * count it too, or per-block occurrence ordinals diverge from the highlighter. So the search space
     * is the block's ASSEMBLED visible text, not individual literals.
     */
    @Test
    fun testCrossNodeStyledMatchIsCounted() {
        val content = "This is **fre**e text."
        val matches = useCase.findSearchMatches(content, "free")

        assertEquals(1, matches.size)
        assertEquals("free", matches[0].content)
        assertEquals(0, matches[0].occurrenceInBlock)
    }

    /**
     * DEFECT 1: image ALT text is not rendered into the TextView (Markwon draws an ImageSpan),
     * so a hit in the alt must NOT count — it would be a phantom. Only the visible prose hit counts.
     *
     * "![free banner](pic.png) and free text." → rendered shows "[img] and free text" → 1 visible "free".
     */
    @Test
    fun testImageAltTextIsNotCounted() {
        val content = "![free banner](pic.png) and free text."
        val matches = useCase.findSearchMatches(content, "free")

        assertEquals(1, matches.size)
        assertEquals("free", matches[0].content)
    }

    /**
     * DEFECT 1/2: a soft line break renders as a SPACE in the TextView. A multi-word query
     * spanning the break ("hello world" over "hello\nworld") is found by the highlighter, so the
     * assembled search text must insert that space too — otherwise the domain misses the match and all
     * later ordinals in the block diverge.
     */
    @Test
    fun testSoftBreakRendersAsSpaceForMultiWordQuery() {
        val content = "hello\nworld"
        val matches = useCase.findSearchMatches(content, "hello world")

        assertEquals(1, matches.size)
        assertEquals(0, matches[0].startIndex)
    }

    /**
     * DEFECT 2/3: table matches must thread their per-block occurrence ordinal ACROSS cells in
     * row-major order (the exact order TableBlockEntry paints + the scroll resolver walks), so the
     * focused colour and the scroll anchor land on the right cell when several cells match.
     *
     * Single top-level block (the table, index 0) with "reaction" in two body cells → two matches, same
     * adapterPosition, occurrenceInBlock 0 then 1.
     */
    @Test
    fun testTableMatchesThreadOccurrenceAcrossCells() {
        val content = buildString {
            append("| Step | Note          |\n")
            append("| ---- | ------------- |\n")
            append("| one  | reaction here |\n")
            append("| two  | a reaction up |\n")
        }
        val matches = useCase.findSearchMatches(content, "reaction")

        assertEquals(2, matches.size)
        assertEquals(0, matches[0].adapterPosition)
        assertEquals(0, matches[1].adapterPosition)
        assertEquals(0, matches[0].occurrenceInBlock)
        assertEquals(1, matches[1].occurrenceInBlock)
    }

    /**
     * Raw-offset robustness: a decoded HTML entity (`&amp;` → "&")
     * makes the assembled visible text differ from raw source. The reconstructed offsets must stay sane
     * and monotonic — the bounded forward-locate must never snap a literal to a distant identical string
     * and corrupt later offsets. (Offsets through a contracted entity are approximate by design; the
     * guarantee tested here is in-bounds + non-decreasing, never a runaway jump or a crash.)
     */
    @Test
    fun testRawOffsetsStaySaneThroughDecodedEntities() {
        val content = "Tom &amp; Jerry &amp; Jerry again."
        val matches = useCase.findSearchMatches(content, "Jerry")

        assertEquals(2, matches.size)
        assertEquals("Jerry", matches[0].content)
        assertTrue("startIndex within source bounds", matches[0].startIndex in 0..content.length)
        assertTrue("startIndex within source bounds", matches[1].startIndex in 0..content.length)
        assertTrue("offsets stay monotonic", matches[1].startIndex > matches[0].startIndex)
    }

    // --- math is not searchable text: it renders as a formula image ---

    @Test
    fun testInlineMathContentIsNotSearchable() {
        // prose "x" counts once; the "x" inside `$x$` (rendered as a formula) does not.
        val matches = useCase.findSearchMatches("the x value is \$x\$ here", "x")
        assertEquals(1, matches.size)
        assertEquals("the prose x, not the math one", 4, matches[0].startIndex)
    }

    @Test
    fun testDisplayMathContentIsNotSearchable() {
        // prose "y" in "yields" counts; the "y" inside `$$y$$` does not.
        val matches = useCase.findSearchMatches("yields \$\$y\$\$ done", "y")
        assertEquals(1, matches.size)
        assertEquals(0, matches[0].startIndex)
    }

    @Test
    fun testCurrencyStaysSearchable() {
        // `$5` is not math, so it remains literal, searchable text.
        val matches = useCase.findSearchMatches("pay \$5 now", "5")
        assertEquals(1, matches.size)
    }

    @Test
    fun testEscapedDollarMathStaysSearchable() {
        // `\$x\$` is escaped (literal `$x$` in the preview), so its "x" is still searchable.
        val matches = useCase.findSearchMatches("lit \\\$x\\\$ end", "x")
        assertEquals(1, matches.size)
    }

    @Test
    fun testCodeBlockDollarsStaySearchable() {
        // `$x$` inside a fenced code block renders literally (not math) → searchable.
        val matches = useCase.findSearchMatches("```\n\$x\$\n```", "x")
        assertEquals(1, matches.size)
    }

    @Test
    fun testMathDoesNotMergeAdjacentWords() {
        // "foo$x$bar": the excluded `$x$` must not merge "foo"+"bar" into a phantom "foobar"
        // (a U+FFFC placeholder keeps them apart). The real words stay searchable.
        assertEquals(0, useCase.findSearchMatches("foo\$x\$bar", "foobar").size)
        assertEquals(1, useCase.findSearchMatches("foo\$x\$bar", "foo").size)
        assertEquals(1, useCase.findSearchMatches("foo\$x\$bar", "bar").size)
    }
}
