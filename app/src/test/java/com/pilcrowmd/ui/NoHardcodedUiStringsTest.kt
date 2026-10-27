// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M-236: user-facing text lives in `res/values/strings.xml`, never as a literal in Kotlin, so the
 * app can be translated. This scans the main sources for the shapes a literal takes on its way to
 * the screen or to TalkBack (Text, contentDescription, dialog and field labels, toasts, chooser
 * titles) and fails on any it finds that is not in [ALLOWED].
 *
 * Deliberate leftovers go in [ALLOWED] as `file:text-fragment` with the reason beside them. A line
 * that is a comment is never flagged.
 */
class NoHardcodedUiStringsTest {

    private val root = File("src/main/java")

    private val named = Regex(
        """\b(text|contentDescription|stateDescription|title|label|placeholder|hintText|""" +
            """onClickLabel|subject|summary|message)\s*=\s*"[^"]*[A-Za-z]""",
    )
    private val call = Regex(
        """\b(Text|Toast\.makeText|setText|createChooser|announceForAccessibility|launch|""" +
            """showMessage)\(.*"[^"]*[A-Za-z]""",
    )

    /** `contentDescription = if (x) "A" else "B"` and the like: a literal after the `=`, not right at it. */
    private val branching = Regex(
        """\b(text|contentDescription|stateDescription|title|label)\s*=\s*(if|when)\b.*"[^"]*[A-Za-z]""",
    )
    private val bareText = Regex("""^\s*(Text|Toast\.makeText)\(\s*$""")
    private val quotedNext = Regex("""^\s*(text\s*=\s*)?"[^"]*[A-Za-z]""")

    @Test
    fun `no user-facing string literal is left in the Kotlin sources`() {
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                // A bare `Text(` is judged by the line under it, which is where the literal sits.
                val nextLine = lines.getOrNull(i + 1).orEmpty()
                val splitCall = bareText.containsMatchIn(raw) && quotedNext.containsMatchIn(nextLine)
                val flagged = listOf(named, call, branching).any { it.containsMatchIn(line) } || splitCall
                val shown = if (splitCall) nextLine else raw
                if (flagged && ALLOWED.none { (f, frag) -> file.path.endsWith(f) && shown.contains(frag) }) {
                    hits += "${file.path.removePrefix("src/main/java/")}:${i + 1}: $line"
                }
            }
        }
        assertTrue(
            "${hits.size} hard-coded user-facing string(s); move them to strings.xml:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    private companion object {
        /** file suffix to line fragment; the reason for each is beside it. */
        val ALLOWED: List<Pair<String, String>> = listOf(
            // Specimen text drawn in the chosen font and in the code font; a fixture, not UI copy.
            "SettingsScreen.kt" to "The quick brown fox",
            "SettingsScreen.kt" to "val sample = 42",
            // The "A" glyph on the smaller and larger text buttons; spoken text is a resource.
            "SettingsScreen.kt" to "text = \"A\"",
            // File names offered to the system file picker ("Copy of x.md", "x.pdf"); they become file
            // names on disk, so they stay as written. Owner call recorded in DECISIONS NEW-127.
            "MainScreen.kt" to "Launcher.launch(\"",
            // The brand name.
            "LicensesScreen.kt" to "text = \"PilcrowMD\"",
            // Subject lines of e-mails to the developer, who filters on them; not shown in the app.
            "ContactLinks.kt" to "Uri.encode(\"PilcrowMD\")",
        )
    }
}
