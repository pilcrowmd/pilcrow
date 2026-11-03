// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.editor

import com.pilcrowmd.domain.editor.VirtualLineBreaks.Replay
import com.pilcrowmd.domain.model.EditorSupport.Companion.MAX_EDITOR_LINE_CHARS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8-A64: the editor is given the file with each line over [MAX_EDITOR_LINE_CHARS] cut into display
 * lines; these pin the cut, the way back to the file, and the offset maps. The edits and the undo
 * rule on Sora's real undo manager are `PilcrowCodeEditorBreaksTest`'s.
 */
class VirtualLineBreaksTest {

    private fun line(unit: String, chars: Int, lead: String = "x"): String {
        val sb = StringBuilder(lead)
        while (sb.length < chars) sb.append(unit)
        return sb.toString()
    }

    private fun pieces(cut: VirtualLineBreaks.Cut): List<String> = cut.text.split('\n')

    /** Every cut point of [cut], as offsets into the file text. */
    private fun fileCutPoints(cut: VirtualLineBreaks.Cut): List<Int> = cut.breaks.mapIndexed { i, b -> b - i }

    private fun assertCutsAreGraphemeBoundaries(file: String) {
        val cut = VirtualLineBreaks.cut(file)
        assertTrue("the fixture needs cuts", cut.breaks.isNotEmpty())
        pieces(cut).forEach { assertTrue("a piece of ${it.length} chars", it.length <= MAX_EDITOR_LINE_CHARS) }
        for (at in fileCutPoints(cut)) {
            val before = file[at - 1]
            val after = file[at]
            assertFalse("cut inside a surrogate pair at $at", before.isHighSurrogate() && after.isLowSurrogate())
            assertFalse("cut next to a zero-width joiner at $at", before == ZWJ || after == ZWJ)
            assertFalse("cut before a variation selector at $at", after == VS16)
            val mark = Character.getType(after) == Character.NON_SPACING_MARK.toInt()
            assertFalse("cut before a combining mark at $at", mark)
        }
    }

    @Test fun aLongAsciiLineIsCutIntoPiecesOfAtMostTheLimit() {
        val file = line("abcdefghij", 25_000)
        val cut = VirtualLineBreaks.cut(file)
        assertEquals((file.length - 1) / MAX_EDITOR_LINE_CHARS, cut.breaks.size)
        pieces(cut).forEach { assertTrue(it.length <= MAX_EDITOR_LINE_CHARS) }
        assertEquals(file, VirtualLineBreaks().apply { reset(cut.breaks) }.strip(cut.text))
    }

    @Test fun surrogatePairsAreNeverSplit() = assertCutsAreGraphemeBoundaries(line("😀", 25_000))

    @Test fun zwjSequencesAreNeverSplit() = assertCutsAreGraphemeBoundaries(line("👩‍👩‍👧‍👦", 25_000))

    @Test fun emojiWithAVariationSelectorIsNeverSplit() = assertCutsAreGraphemeBoundaries(line("❤️", 25_000))

    @Test fun combiningSequencesAreNeverSplit() = assertCutsAreGraphemeBoundaries(line("é̂", 25_000))

    @Test fun cjkIsCutAtTheLimit() {
        val cut = VirtualLineBreaks.cut(line("漢字かな", 25_000))
        assertEquals(MAX_EDITOR_LINE_CHARS, pieces(cut).first().length)
    }

    @Test fun aLineAtTheLimitIsNotCutAndOneCharMoreIs() {
        val atLimit = "a\n" + "x".repeat(MAX_EDITOR_LINE_CHARS) + "\nb"
        assertEquals(0, VirtualLineBreaks.cut(atLimit).breaks.size)
        assertEquals(atLimit, VirtualLineBreaks.cut(atLimit).text)
        val over = "a\n" + "x".repeat(MAX_EDITOR_LINE_CHARS + 1) + "\nb"
        assertEquals(1, VirtualLineBreaks.cut(over).breaks.size)
    }

    @Test fun aNormalFileIsGivenAsItIs() {
        val file = (0 until 3_000).joinToString("\n") { "line $it with some words" }
        val cut = VirtualLineBreaks.cut(file)
        assertTrue("the same string, not a copy", cut.text === file)
        assertEquals(0, cut.breaks.size)
    }

    @Test fun strippingTheCutGivesTheFileBackForEveryScript() {
        val files = listOf(
            "ascii" to "# T\n" + line("word ", 7_000) + "\nshort\n" + line("unspaced", 9_001) + "\n",
            "cjk" to line("漢字かな。", 12_345) + "\n" + line("中文", 4_001),
            "emoji" to line("👩‍👩‍👧‍👦 😀❤️", 8_000) + "\nend",
            "crlf" to "a\r\n" + line("crlf words ", 6_000) + "\r\nb\r\n" + line("x", 2_001) + "\r\n",
            "lone cr" to line("a\rb ", 5_000),
            "empty lines" to "\n\n" + line("y", 4_000) + "\n\n",
        )
        for ((name, file) in files) {
            val cut = VirtualLineBreaks.cut(file)
            assertTrue("$name needs cuts", cut.breaks.isNotEmpty())
            val breaks = VirtualLineBreaks().apply { reset(cut.breaks) }
            assertEquals(name, file, breaks.strip(cut.text))
            assertEquals("$name: each break is one newline", file.length + cut.breaks.size, cut.text.length)
            cut.breaks.forEach { assertEquals(name, '\n', cut.text[it]) }
        }
    }

    @Test fun stripOfARangeLeavesOnlyItsOwnBreaksOut() {
        val file = line("abc ", 5_000)
        val cut = VirtualLineBreaks.cut(file)
        val breaks = VirtualLineBreaks().apply { reset(cut.breaks) }
        val from = cut.breaks[0] - 5
        val to = cut.breaks[1] + 7
        assertEquals(file.substring(breaks.toFile(from), breaks.toFile(to)), breaks.strip(cut.text, from, to))
    }

    @Test fun fileOffsetsSurviveTheTripToTheEditorAndBack() {
        val file = line("漢字 ", 9_000) + "\n" + line("ab", 3_000)
        val cut = VirtualLineBreaks.cut(file)
        val breaks = VirtualLineBreaks().apply { reset(cut.breaks) }
        for (f in 0..file.length) {
            val e = breaks.toEditor(f)
            assertEquals("file offset $f", f, breaks.toFile(e))
            assertFalse("file offset $f lands on a break", breaks.isBreak(e))
            if (f < file.length) assertEquals("the char at $f", file[f], cut.text[e])
        }
    }

    @Test fun adjacentBreaksStillMapBothWays() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(3, 4, 5)) } // "abc\n\n\nd"
        assertEquals(listOf(0, 1, 2, 6, 7), (0..4).map { breaks.toEditor(it) })
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 3, 4), (0..7).map { breaks.toFile(it) })
    }

    // Edits, here on offsets alone; on Sora's own content and undo manager in PilcrowCodeEditorBreaksTest.

    @Test fun deletingAVirtualBreakJoinsThePiecesAndLeavesTheFileAlone() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(4)) } // "abcd\nefgh"
        breaks.onDelete(4, "\n")
        assertEquals("abcdefgh", breaks.strip("abcdefgh"))
        assertEquals(0, breaks.count)
    }

    @Test fun aTypedEnterIsFileText() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(4)) }
        breaks.onInsert(2, "\n") // "ab\ncd\nefgh"
        assertEquals("ab\ncdefgh", breaks.strip("ab\ncd\nefgh"))
        assertArrayEquals(intArrayOf(5), breaks.breaks())
    }

    @Test fun anUndoPuttingADeletedBreakBackMakesItVirtualAgain() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(4)) }
        breaks.onDelete(3, "d\ne") // "abcfgh"
        breaks.onInsert(3, "d\ne", Replay.UNDO)
        assertEquals("no newline reaches the file", "abcdefgh", breaks.strip("abcd\nefgh"))
        breaks.onDelete(3, "d\ne", Replay.REDO)
        assertEquals("abcfgh", breaks.strip("abcfgh"))
        breaks.onInsert(3, "d\ne", Replay.UNDO)
        assertArrayEquals("and again after a redo", intArrayOf(4), breaks.breaks())
    }

    @Test fun anUndoPuttingBackARealNewlineKeepsItReal() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(4)) }
        breaks.onDelete(4, "\n") // the virtual one: "abcdefgh"
        breaks.onInsert(4, "\n") // a real Enter in the same place
        breaks.onDelete(4, "\n") // and deleted
        breaks.onInsert(4, "\n", Replay.UNDO)
        assertEquals("the Enter comes back as file text", "abcd\nefgh", breaks.strip("abcd\nefgh"))
    }

    @Test fun anUndoOfMergedBackspacesRestoresEveryBreakInIt() {
        // "ab\ncd\nef" with both newlines virtual; backspace from the end: f, e, \n, d, c, \n, b.
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(2, 5)) }
        var text = "ab\ncd\nef"
        while (text.length > 1) {
            val at = text.length - 1
            breaks.onDelete(at, text.substring(at))
            text = text.substring(0, at)
        }
        // Sora merges the backspaces into one action and puts it back in one insert.
        breaks.onInsert(1, "b\ncd\nef", Replay.UNDO)
        assertArrayEquals(intArrayOf(2, 5), breaks.breaks())
    }

    @Test fun anUnknownReplayKeepsTheTextAsItIsAndStopsRestoringBreaks() {
        val breaks = VirtualLineBreaks().apply { reset(intArrayOf(4)) }
        breaks.onDelete(4, "\n")
        breaks.onInsert(4, "\n", Replay.UNKNOWN)
        assertEquals("abcd\nefgh", breaks.strip("abcd\nefgh"))
    }

    private companion object {
        const val ZWJ = '‍'
        const val VS16 = '️'
    }
}
