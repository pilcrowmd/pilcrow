// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [parseFrontmatterFields] — the pure key/value split that backs the metadata-card
 * rendering. No Android / Markwon deps, so it runs as a plain JVM test.
 */
class FrontmatterFieldsTest {

    @Test
    fun parsesSimpleKeyValueLines() {
        val fields = parseFrontmatterFields("title: PDF Export Stress Test\nauthor: pleree")
        assertEquals(
            listOf(
                FrontmatterField("title", "PDF Export Stress Test"),
                FrontmatterField("author", "pleree"),
            ),
            fields,
        )
    }

    @Test
    fun keepsColonsInsideTheValue() {
        val fields = parseFrontmatterFields("date: 2026: a note")
        assertEquals(listOf(FrontmatterField("date", "2026: a note")), fields)
    }

    @Test
    fun trimsSurroundingWhitespace() {
        val fields = parseFrontmatterFields("  title :   Spaced Out  ")
        assertEquals(listOf(FrontmatterField("title", "Spaced Out")), fields)
    }

    @Test
    fun lineWithoutColonBecomesLabellessRow() {
        val fields = parseFrontmatterFields("just a bare line")
        assertEquals(listOf(FrontmatterField("", "just a bare line")), fields)
    }

    @Test
    fun leadingColonHasNoKey() {
        // ": value" has the colon at index 0 → no key, whole line is the value (graceful).
        val fields = parseFrontmatterFields(": orphan value")
        assertEquals(listOf(FrontmatterField("", ": orphan value")), fields)
    }

    @Test
    fun skipsBlankLines() {
        val fields = parseFrontmatterFields("title: A\n\n   \nauthor: B\n")
        assertEquals(
            listOf(FrontmatterField("title", "A"), FrontmatterField("author", "B")),
            fields,
        )
    }

    @Test
    fun emptyInputYieldsNoFields() {
        assertEquals(emptyList<FrontmatterField>(), parseFrontmatterFields(""))
    }
}
