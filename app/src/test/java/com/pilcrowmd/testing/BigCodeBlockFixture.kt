// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.testing

/**
 * The fixture for issue #17: the text of a long fenced code block, [count] lines of [width] chars each
 * (the line end included), numbered from 1 and unique per line: `row 7......`. A block is cut once it has
 * more than 2,000 lines or more than 10,000 chars, so 250 lines need a [width] of 41 or more, and 3,000
 * lines are cut at any width. While 100 lines stay under 8,000 chars (a [width] under 80), the pieces are
 * exactly 100 lines each. [mark] adds text to the end of chosen lines, to plant a word in a chosen piece.
 */
fun codeLines(count: Int, width: Int = 13, mark: Map<Int, String> = emptyMap()): String =
    (1..count).joinToString("") { n ->
        "row $n".padEnd(width - 1, '.') + (mark[n]?.let { " $it" } ?: "") + "\n"
    }

/** [codeLines] as a fenced block in [info]. */
fun fencedCode(count: Int, info: String = "rust", width: Int = 13, mark: Map<Int, String> = emptyMap()): String =
    "```$info\n${codeLines(count, width, mark)}```\n"
