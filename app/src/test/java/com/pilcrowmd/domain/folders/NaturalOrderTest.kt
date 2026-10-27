// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** L5: natural order of names in the notes list. */
class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun `numbers compare by value`() {
        assertEquals(listOf("note 2", "note 10", "note 100"), sorted("note 100", "note 10", "note 2"))
    }

    @Test
    fun `case does not decide the order`() {
        assertEquals(listOf("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"))
    }

    @Test
    fun `leading zeros do not change the value`() {
        assertEquals(listOf("v1", "v002", "v3", "v010"), sorted("v010", "v3", "v002", "v1"))
        // Same value: still a total order, decided by the raw text.
        assertTrue(NaturalOrder.compare("a01", "a1") != 0)
        assertTrue(NaturalOrder.compare("a01b", "a1c") < 0)
    }

    @Test
    fun `a 40-digit run compares by value without overflowing`() {
        val big = "9".repeat(40)
        val bigger = "1" + "0".repeat(40)
        assertEquals(listOf("x 2", "x $big", "x $bigger"), sorted("x $bigger", "x $big", "x 2"))
        assertEquals(
            listOf("1" + "0".repeat(39) + "1", "1" + "0".repeat(38) + "20"),
            sorted("1" + "0".repeat(38) + "20", "1" + "0".repeat(39) + "1"),
        )
    }

    @Test
    fun `an accented name sorts the same in either Unicode form`() {
        val decomposed = "Música" // the NFD form, as some file systems store it
        val composed = "Música"
        // Byte by byte, NFD "u" + combining accent would sort before "ú" ("Mua"); in one form it
        // sorts between "Múa" and "Múz", as the composed name does.
        assertEquals(listOf("Múa", decomposed, "Múz"), sorted("Múz", decomposed, "Múa"))
        assertEquals(listOf("Múa", composed, "Múz"), sorted("Múz", composed, "Múa"))
    }

    @Test
    fun `the order is total and the same however the input is shuffled`() {
        val names = listOf(
            "note 2", "Note 2", "note 02", "note 10", "Note10", "note", "notes", "note 2a", "a", "A", "",
            "1", "01", "001", "10", "Música", "Música", "z9", "z09", "z 9", "-x", "_x", "x.md",
        )
        val expected = names.sortedWith(NaturalOrder)
        val random = Random(42)
        repeat(50) {
            assertEquals(expected, names.shuffled(random).sortedWith(NaturalOrder))
        }
        for (a in names) {
            for (b in names) {
                val ab = NaturalOrder.compare(a, b)
                val ba = NaturalOrder.compare(b, a)
                assertEquals("antisymmetry of '$a' and '$b'", Integer.signum(ab), -Integer.signum(ba))
                if (a != b) assertTrue("only equal strings compare equal: '$a' '$b'", ab != 0)
            }
        }
    }
}
