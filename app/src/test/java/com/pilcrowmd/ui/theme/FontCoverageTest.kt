// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

/**
 * The reading fonts hold the letters of the languages a reader is likely to write in. A letter a
 * font lacks is drawn from a system font instead, in another design, in the middle of a word.
 *
 * Read from each font file's own character map. `Paint.hasGlyph` would not do: it answers for the
 * system's fallback fonts too, so it says yes to a letter the bundled font does not have.
 */
@RunWith(RobolectricTestRunner::class)
class FontCoverageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The letters of [letters] that the font file [font] has no glyph for. */
    private fun missing(font: Int, letters: String): String {
        val covered = codePointsOf(context.resources.openRawResource(font).use { it.readBytes() })
        return letters.filterNot { it.code in covered }
    }

    private fun assertCovers(set: FontSet, letters: String) {
        assertEquals("regular", "", missing(set.readingRegular, letters))
        assertEquals("bold", "", missing(set.readingBold, letters))
    }

    @Test
    fun `the default font has the letters of the app's own Ukrainian translation`() {
        assertCovers(FontSets.CLASSIC, UKRAINIAN)
    }

    @Test
    fun `the default font has Central European, Baltic, Turkish, Greek and Vietnamese letters`() {
        assertCovers(
            FontSets.CLASSIC,
            POLISH + CZECH_SLOVAK + HUNGARIAN + ROMANIAN + TURKISH + BALTIC + GREEK + VIETNAMESE,
        )
    }

    @Test
    fun `every reading font has the Latin letters of Central Europe and Turkey`() {
        FontSets.ALL.forEach { assertCovers(it, POLISH + CZECH_SLOVAK + HUNGARIAN + ROMANIAN + TURKISH) }
    }

    @Test
    fun `the reader says no to a letter a font does not have`() {
        // Guards the test itself: the Modern font has no Cyrillic, and must be seen not to.
        assertEquals("б", missing(FontSets.MODERN.readingRegular, "aб"))
    }

    /** The code points a TrueType file maps to a glyph, from its `cmap` table (formats 4 and 12). */
    private fun codePointsOf(file: ByteArray): Set<Int> {
        val font = ByteBuffer.wrap(file)
        val tables = font.getShort(TABLE_COUNT_AT).toInt() and SHORT
        val cmap = (0 until tables).map { TABLE_DIRECTORY_AT + it * TABLE_RECORD_SIZE }
            .first { font.getInt(it) == CMAP_TAG }
            .let { font.getInt(it + TABLE_OFFSET_AT) }
        val out = HashSet<Int>()
        repeat(font.getShort(cmap + 2).toInt() and SHORT) { i ->
            val subtable = cmap + font.getInt(cmap + 4 + i * ENCODING_RECORD_SIZE + 4)
            when (font.getShort(subtable).toInt() and SHORT) {
                FORMAT_SEGMENTS -> readSegments(font, subtable, out)
                FORMAT_GROUPS -> readGroups(font, subtable, out)
            }
        }
        return out
    }

    /** Format 4: segments of the Basic Multilingual Plane; a code point maps to glyph 0 when it has none. */
    private fun readSegments(font: ByteBuffer, at: Int, out: MutableSet<Int>) {
        val segments = (font.getShort(at + 6).toInt() and SHORT) / 2
        val ends = at + 14
        val starts = ends + segments * 2 + 2
        val deltas = starts + segments * 2
        val rangeOffsets = deltas + segments * 2
        for (s in 0 until segments) {
            val start = font.getShort(starts + s * 2).toInt() and SHORT
            val end = font.getShort(ends + s * 2).toInt() and SHORT
            val delta = font.getShort(deltas + s * 2).toInt()
            val rangeOffset = font.getShort(rangeOffsets + s * 2).toInt() and SHORT
            // Where the segment's glyph ids are listed, when it lists them instead of adding [delta].
            val listed = if (rangeOffset == 0) -1 else rangeOffsets + s * 2 + rangeOffset
            (start..minOf(end, SHORT - 1)).filterTo(out) { code -> glyphOf(font, code, start, delta, listed) != 0 }
        }
    }

    private fun glyphOf(font: ByteBuffer, code: Int, segmentStart: Int, delta: Int, listed: Int): Int {
        if (listed < 0) return (code + delta) and SHORT
        val raw = font.getShort(listed + (code - segmentStart) * 2).toInt() and SHORT
        return if (raw == 0) 0 else (raw + delta) and SHORT
    }

    /** Format 12: groups of consecutive code points, each mapped to consecutive glyphs. */
    private fun readGroups(font: ByteBuffer, at: Int, out: MutableSet<Int>) {
        repeat(font.getInt(at + 12)) { g ->
            val group = at + 16 + g * GROUP_SIZE
            for (code in font.getInt(group)..font.getInt(group + 4)) out.add(code)
        }
    }

    private companion object {
        const val UKRAINIAN = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюяАБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
        const val POLISH = "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ"
        const val CZECH_SLOVAK = "čďěňřšťůžľĺŕČĎĚŇŘŠŤŮŽĽĹŔ"
        const val HUNGARIAN = "őűŐŰ"
        const val ROMANIAN = "ăâîșțĂÂÎȘȚ"
        const val TURKISH = "ğışİŞĞ"
        const val BALTIC = "āčēģīķļņšūžėįų"
        const val GREEK = "αβγδεζηθικλμνξοπρστυφχψω"
        const val VIETNAMESE = "ạảấầẩẫậơưđ"

        const val SHORT = 0xFFFF
        const val TABLE_COUNT_AT = 4
        const val TABLE_DIRECTORY_AT = 12
        const val TABLE_RECORD_SIZE = 16
        const val TABLE_OFFSET_AT = 8
        const val CMAP_TAG = 0x636D6170 // "cmap"
        const val ENCODING_RECORD_SIZE = 8
        const val FORMAT_SEGMENTS = 4
        const val FORMAT_GROUPS = 12
        const val GROUP_SIZE = 12
    }
}
