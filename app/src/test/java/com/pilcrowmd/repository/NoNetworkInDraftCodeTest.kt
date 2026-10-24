// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A draft never leaves the phone. The draft store and the keeper that feeds
 * it must not reach for any network API. Reads the SOURCE of those files and fails on a use of one,
 * so a later edit that adds a network call is caught here and not by a reader of the diff.
 *
 * Comments are skipped, so a sentence that says "no network" does not trip it.
 */
class NoNetworkInDraftCodeTest {

    private val files = listOf(
        "repository/DraftRepository.kt",
        "repository/LocalDraftRepository.kt",
        "repository/DraftFile.kt",
        "viewmodel/DraftKeeper.kt",
    )

    private val banned = listOf(
        Regex("""\bjava\.net\b"""),
        Regex("""\bokhttp3?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bHttpURLConnection\b"""),
        Regex("""\bHttpsURLConnection\b"""),
        Regex("""\bandroid\.net\.http\b"""),
        Regex("""\bjavax\.net\b"""),
        Regex("""\bURL\s*\("""),
    )

    /** Gradle runs unit tests with the project directory (`app/`) as the working directory. */
    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/pilcrowmd"),
            File("app/src/main/java/com/pilcrowmd"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("main sources not found from ${File(".").absoluteFile}; tried $candidates")
    }

    /** The non-comment lines of [source], numbered from 1. Block comments keep their line count. */
    private fun codeLines(source: List<String>): List<Pair<Int, String>> {
        val withoutBlocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            .replace(source.joinToString("\n")) { "\n".repeat(it.value.count { c -> c == '\n' }) }
        return withoutBlocks.lines().mapIndexedNotNull { i, raw ->
            raw.substringBefore("//").takeIf { it.isNotBlank() }?.let { (i + 1) to it }
        }
    }

    @Test
    fun `the draft code uses no network API`() {
        val root = sourceRoot()
        val offences = files.flatMap { name ->
            val file = File(root, name)
            assertTrue("$name exists under $root", file.isFile)
            codeLines(file.readLines()).flatMap { (number, line) ->
                banned.filter { it.containsMatchIn(line) }.map { "$name:$number uses ${it.pattern}: ${line.trim()}" }
            }
        }
        assertTrue("network use in draft code:\n${offences.joinToString("\n")}", offences.isEmpty())
    }

    @Test
    fun `the scan sees a network import when there is one`() {
        // The check above is only worth having if it can fail: run its own line scan over text that
        // does import java.net.URL, without touching a source file.
        val sample = listOf("package x", "// java.net in a comment", "import java.net.URL", "val u = URL(\"x\")")
        val hits = codeLines(sample).filter { (_, line) -> banned.any { it.containsMatchIn(line) } }
        assertTrue("expected the import and the call, got $hits", hits.map { it.first } == listOf(3, 4))
    }
}
