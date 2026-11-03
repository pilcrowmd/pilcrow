// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S8-A68 — `Mermaid-Bundled-Notices.txt` names every library packed inside the bundled
 * `mermaid.min.js`, at the exact version the bundle's source map names, and keeps the bundle's own
 * preserved legal comments. `mermaid-bundled-packages.txt` is made by `docs/tools/mermaid-notices.py`
 * from Mermaid's source map; regenerate it with the notices file on every Mermaid bump.
 */
class MermaidBundledNoticesTest {

    private val notices = File("src/main/assets/licenses/Mermaid-Bundled-Notices.txt").readText()

    @Test
    fun `every package the bundle packs is listed at its exact version`() {
        val listed = notices.lines().mapNotNull { HEADING.matchEntire(it)?.destructured }
            .map { (name, version) -> "$name $version" }.toSet()
        val wanted = javaClass.getResource("/mermaid-bundled-packages.txt")!!.readText()
            .lines().filter { it.isNotBlank() }
        assertTrue("the checked-in list has the 74 packages of the source map", wanted.size >= 74)
        assertTrue("path-browserify 1.0.1 is on the list", "path-browserify 1.0.1" in wanted)
        val missing = wanted.filterNot { it in listed }
        assertTrue("not in the notices: $missing", missing.isEmpty())
    }

    @Test
    fun `the notices preserved inside the bundle are there verbatim`() {
        val section = notices.substringAfter("Notices preserved inside mermaid.min.js", "")
        assertTrue("the section exists", section.isNotEmpty())
        for (line in PRESERVED) assertTrue("preserved: $line", section.contains(line))
    }

    private companion object {
        val HEADING = Regex("""(\S+) (\d\S*) \(.+\)""")
        val PRESERVED = listOf(
            "@license DOMPurify 3.4.12 | (c) Cure53 and other contributors",
            "Copyright OpenJS Foundation and other contributors <https://openjsf.org/>",
            "Embeddable Minimum Strictly-Compliant Promises/A+ 1.1.1 Thenable",
            "Event object based on jQuery events, MIT license",
            "Bezier curve function generator. Copyright Gaetan Renaudeau.",
            "Runge-Kutta spring physics function generator. Adapted from Framer.js, copyright Koen Bok.",
        )
    }
}
