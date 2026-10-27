// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

import java.text.Normalizer
import java.util.Locale

/**
 * L5: the order of names in the notes list. `note 2` comes before `note 10`.
 *
 * Names are compared in one Unicode form (NFC, so `Música` sorts the same however it is stored)
 * and without case. A run of ASCII digits compares by its value: leading zeros are stripped, then
 * the shorter run is the smaller number, then the digits are compared one by one. The run is never
 * parsed into a number, so a 40-digit run is fine. Names that are equal by all of that are ordered
 * by their raw text, so the order is total and the same on every run.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val byName = compareFolded(foldName(a), foldName(b))
        return if (byName != 0) byName else a.compareTo(b)
    }

    private fun compareFolded(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val byValue = compareDigitRuns(a.substring(i, endA), b.substring(j, endB))
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                // A digit against any other character: digits are one block of code points, so this
                // gives the same answer whatever the number is, and the order stays transitive.
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun compareDigitRuns(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }

    private fun digitRunEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

/** One Unicode form, no case: how two names are compared for sorting and for "same name" (P15). */
internal fun foldName(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
