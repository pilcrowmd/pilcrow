// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-221: a file name that does not fit shows its start, `…`, and its last 8 characters.
 *
 * "Fits" is modelled here as a budget of UTF-16 code units, so a cut placed by code units rather
 * than by the characters the reader sees would land inside an emoji and these tests would see it.
 */
class MiddleEllipsizeTest {

    private fun budget(units: Int): (String) -> Boolean = { it.length <= units }

    @Test
    fun aNameThatFitsIsShownWhole() {
        assertEquals(LONG_V2, middleEllipsize(LONG_V2, fits = budget(LONG_V2.length)))
    }

    @Test
    fun aNameOfNineCharactersOrFewerIsNeverCut() {
        // Nine characters: "…" plus the last eight would be no shorter, so the name stays whole.
        assertEquals("abcdefghi", middleEllipsize("abcdefghi", fits = { false }))
        // Ten is the first length that is cut.
        assertEquals("…cdefghij", middleEllipsize("abcdefghij", fits = { false }))
    }

    @Test
    fun theLongestStartThatFitsIsKeptBeforeTheLastEightCharacters() {
        val fits = budget(20)
        val shown = middleEllipsize(LONG_V2, fits = fits)

        assertEquals("quarterly-r…ft-v2.md", shown)
        assertTrue(fits(shown))
        // One more character of the start would not fit, so this is the longest start.
        assertFalse(fits("quarterly-re…ft-v2.md"))
    }

    @Test
    fun namesThatDifferOnlyAtTheEndStayDistinctAndKeepTheirExtension() {
        val v2 = middleEllipsize(LONG_V2, fits = budget(20))
        val v3 = middleEllipsize(LONG_V3, fits = budget(20))

        assertNotEquals(v2, v3)
        assertTrue(v2.endsWith("-v2.md"))
        assertTrue(v3.endsWith("-v3.md"))
    }

    @Test
    fun anEmojiAtTheCutIsNeverSplit() {
        val name = "abc😀😀😀😀defghijklmnop.md"
        // Room for four code units of start: "abc" plus half of the emoji. The cut goes before it.
        val shown = middleEllipsize(name, fits = budget(4 + 1 + 8))

        assertEquals("abc…lmnop.md", shown)
        assertNoLoneSurrogate(shown)
    }

    @Test
    fun anEmojiInTheLastEightCharactersCountsAsOne() {
        // 🎉 plus seven letters is eight characters but nine code units.
        val name = "some-long-file-name-🎉abcdefg"
        val shown = middleEllipsize(name, fits = { false })

        assertEquals("…🎉abcdefg", shown)
        assertNoLoneSurrogate(shown)
    }

    @Test
    fun aZwjFamilyAndAnAccentedLetterStayWhole() {
        val family = "👨‍👩‍👧"
        val accented = "é"
        val tail = family + accented + "-v2.md"
        val name = "ab" + family + "cdefghijklmnop-" + tail

        // The end is eight characters: the family, the accented e, and "-v2.md".
        val fallback = middleEllipsize(name, fits = { false })
        assertEquals(MIDDLE_ELLIPSIS + tail, fallback)

        // Room for six code units of start: "ab" and part of the family. The cut goes before it.
        val shown = middleEllipsize(name, fits = budget(6 + 1 + tail.length))
        assertEquals("ab" + MIDDLE_ELLIPSIS + tail, shown)
    }

    @Test
    fun whenNotEvenTheEndFitsTheEllipsisAndTheLastEightAreReturned() {
        assertEquals("…ft-v2.md", middleEllipsize(LONG_V2, fits = { false }))
    }

    @Test
    fun noBudgetEverProducesALoneSurrogateOrAPartialCharacter() {
        val name = "📄notes-👨‍👩‍👧-trip-été-🇵🇱-😀😀😀-final-🎉v2.md"
        for (units in 0..name.length) {
            val shown = middleEllipsize(name, fits = budget(units))
            assertNoLoneSurrogate(shown)
            if (shown != name) {
                val start = shown.substringBefore(MIDDLE_ELLIPSIS)
                val end = shown.substringAfter(MIDDLE_ELLIPSIS)
                assertTrue("start '$start' is not a start of the name", name.startsWith(start))
                assertTrue("end '$end' is not the end of the name", name.endsWith(end))
                // The start must stop between two characters: the next code unit may not be a
                // low surrogate, a ZWJ, or a combining mark continuing the last character.
                val next = name[start.length]
                assertFalse("cut inside a character at $units", next.isLowSurrogate())
                assertFalse("cut before a ZWJ at $units", next == '‍')
                assertFalse("cut before a combining mark at $units", next == '́')
            }
        }
    }

    private fun assertNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch.isHighSurrogate()) {
                assertTrue("lone high surrogate at $i in '$s'", i + 1 < s.length && s[i + 1].isLowSurrogate())
                i += 2
            } else {
                assertFalse("lone low surrogate at $i in '$s'", ch.isLowSurrogate())
                i++
            }
        }
    }

    private companion object {
        const val LONG_V2 = "quarterly-report-final-review-draft-v2.md"
        const val LONG_V3 = "quarterly-report-final-review-draft-v3.md"
    }
}
