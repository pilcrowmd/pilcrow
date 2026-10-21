// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import com.pilcrowmd.domain.markdown.AdapterBlocks
import com.pilcrowmd.domain.markdown.CodeBlockPiece
import com.pilcrowmd.domain.usecase.ReadEditLanding.ReaderPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Issue #15 (M-388): a Read to Edit landing on a long document, with the search cut short.
 *
 * Reader blocks have no source spans, so the line a block starts on is found by probing prefixes, and
 * each probe is a parse. A switch cannot wait for ~17 of them on a phone, so the search is seeded from the
 * blocks' sizes and, cut off, returns the nearest line it has. Measured on a 202 KB note of 47 rust
 * pieces, 50 json pieces and a few paragraphs, the old first guess (interpolated by block index) was off by
 * thousands of lines: +3459, +2111 and -801 lines for blocks 24, 81 and 97 after one probe.
 *
 * The fixtures are generated; the expected lines come from how they are generated, not from the code
 * under test.
 */
class ReadEditLandingSearchTest {
    private val useCase = ParseMarkdownHeadingsUseCase()
    private val landing = ReadEditLanding(useCase)

    private fun blocksOf(content: String) = AdapterBlocks.of(useCase.parseDocument(content)!!)

    private fun lineOf(content: String, offset: Int): Int = content.substring(0, offset).count { it == '\n' } + 1

    private val rustRows = 4_700
    private val jsonRows = 5_000

    /** A heading, 47 rust pieces, 50 json pieces, a heading and 20 paragraphs: 119 reader blocks. */
    private val mixed: String = buildString {
        append("# Rust and json\n\n```rust\n")
        for (n in 1..rustRows) append("rs $n // ").append("x".repeat(n % 30)).append('\n')
        append("```\n\n```json\n")
        for (n in 1..jsonRows) append("  \"js").append(n).append("\": ").append(n).append(",\n")
        append("```\n\n## After the code\n\n")
        for (i in 0 until 20) append("Paragraph $i after the code, with some words in it.\n\n")
    }

    private val mixedBlocks = blocksOf(mixed)

    /** The offset of the line half way down code piece [piece] (0-based) of the rust or json block. */
    private fun halfWayDown(piece: Int, rust: Boolean): Int {
        val row = piece * PIECE_ROWS + PIECE_ROWS / 2 + 1
        return if (rust) mixed.indexOf("\nrs $row // ") + 1 else mixed.indexOf("\n  \"js$row\": ") + 1
    }

    /** The mixed fixture's blocks 24, 48, 81 and 97 at the middle of the block, and the offsets that are right. */
    private val cases: List<Pair<Int, Int>> by lazy {
        listOf(
            24 to halfWayDown(24 - 1, rust = true),
            48 to halfWayDown(0, rust = false),
            81 to halfWayDown(81 - 48, rust = false),
            97 to halfWayDown(97 - 48, rust = false),
        )
    }

    @Test
    fun `the mixed fixture has the blocks the cases assume`() {
        assertEquals(119, mixedBlocks.size)
        assertEquals(97, mixedBlocks.count { it is CodeBlockPiece })
        cases.forEach { (_, offset) -> assertTrue(offset > 0) }
    }

    @Test
    fun `unbounded, each case lands on exactly the line half way down its piece`() {
        for ((block, expected) in cases) {
            val offset = landing.offsetForBlock(mixed, false, ReaderPoint(block, 0.5f), mixedBlocks)
            assertEquals("block $block", expected, offset)
        }
    }

    @Test
    fun `cut off after 0, 1, 2 or 4 probes, each case is within 100 lines of the right one`() {
        for (probes in listOf(0, 1, 2, 4)) {
            for ((block, expected) in cases) {
                var left = probes
                val point = ReaderPoint(block, 0.5f)
                val offset = landing.offsetForBlock(mixed, false, point, mixedBlocks) { left-- > 0 }!!
                val off = lineOf(mixed, offset) - lineOf(mixed, expected)
                assertTrue("block $block after $probes probes is $off lines off", abs(off) <= WITHIN_LINES)
            }
        }
    }

    @Test
    fun `unbounded, the search takes a handful of probes, not the 17 of a plain bisection`() {
        for ((block, _) in cases) {
            var probes = 0
            landing.offsetForBlock(mixed, false, ReaderPoint(block, 0.5f), mixedBlocks) { probes++ >= 0 }
            assertTrue("block $block took $probes probes", probes <= MOST_PROBES)
        }
        for (block in listOf(300, 1_234, 1_999)) {
            var probes = 0
            landing.offsetForBlock(prose, false, ReaderPoint(block, 0f), proseBlocks) { probes++ >= 0 }
            assertTrue("prose block $block took $probes probes", probes <= MOST_PROBES)
        }
    }

    @Test
    fun `with no probe at all the guess from the blocks' sizes is within 100 lines`() {
        for ((block, expected) in cases) {
            val offset = landing.estimatedOffsetForBlock(mixed, false, ReaderPoint(block, 0.5f), mixedBlocks)!!
            val off = lineOf(mixed, offset) - lineOf(mixed, expected)
            assertTrue("block $block is $off lines off", abs(off) <= WITHIN_LINES)
        }
    }

    /** 2,000 blocks of prose: headings, paragraphs of one to six lines, lists, some blank-line runs of two. */
    private val prose: String
    private val proseStarts = mutableListOf<Int>() // the offset of each block's first line

    init {
        val text = StringBuilder()
        for (i in 0 until PROSE_BLOCKS) {
            proseStarts += text.length
            when (i % 5) {
                0 -> text.append("## Section $i\n")
                1, 4 -> repeat(1 + (i * 7) % 6) { j ->
                    text.append("Paragraph $i line $j, ").append("words ".repeat((i + j) % 9 + 2)).append('\n')
                }
                2 -> text.append("- item $i a\n- item $i b\n- item $i c\n")
                else -> text.append("A single line paragraph $i.\n")
            }
            text.append(if (i % 11 == 0) "\n\n" else "\n")
        }
        prose = text.toString()
    }

    private val proseBlocks = blocksOf(prose)

    @Test
    fun `the prose fixture is 2000 blocks`() {
        assertEquals(PROSE_BLOCKS, proseBlocks.size)
    }

    @Test
    fun `in a long prose note, unbounded is exact and 2 probes land within 20 lines`() {
        for (block in listOf(300, 777, 1_234, 1_555, 1_999)) {
            val exact = landing.offsetForBlock(prose, false, ReaderPoint(block, 0f), proseBlocks)
            assertEquals("block $block unbounded", proseStarts[block], exact)

            var left = 2
            val near = landing.offsetForBlock(prose, false, ReaderPoint(block, 0f), proseBlocks) { left-- > 0 }!!
            val off = lineOf(prose, near) - lineOf(prose, proseStarts[block])
            assertNotNull(near)
            assertTrue("block $block after 2 probes is $off lines off", abs(off) <= PROSE_WITHIN_LINES)
        }
    }

    private companion object {
        const val PIECE_ROWS = 100
        const val WITHIN_LINES = 100
        const val MOST_PROBES = 8
        const val PROSE_BLOCKS = 2_000
        const val PROSE_WITHIN_LINES = 20
    }
}
