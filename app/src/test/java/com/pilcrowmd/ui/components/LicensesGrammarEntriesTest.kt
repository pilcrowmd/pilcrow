// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.rendering.TextMateCodeHighlighter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * M-136, M-243 — every grammar the reader loads has an entry in Settings → Licences whose text, as the
 * detail view shows it, carries each upstream's own copyright line and, in full, the text of every
 * licence the grammar is under (MIT, Apache-2.0, MPL-2.0, the TextMate Bundle License).
 *
 * Which licences a grammar is under is decided here, per asset ([LICENCES]), not read from the entry's
 * label, so relabelling an entry cannot shrink what it must show. Each licence text is compared whole,
 * whitespace-normalised, against a reference copy: the app's own `MIT.txt` and `Apache-2.0.txt`, and
 * from the repository, `licenses/MPL-2.0.txt` (oovm/vscode-toml's `License.md` at `e9edfdb`) and
 * `licenses/TextMate-Bundle-License.txt` (the TextMate bundles' README text, NEW-35). Every notice
 * section that starts a licence must carry all of it, so a section cut short fails even when another
 * section carries the same licence whole.
 *
 * The grammars come from the reader's registry, not a list kept here, so a grammar added there
 * without a notice fails this test. Each grammar's upstream is read from the grammar file itself, so
 * a notice that keeps VS Code's section but loses the upstream's own fails too. A grammar whose file
 * records no upstream commit has its upstream named in [UNVERSIONED], and one that is in neither fails.
 */
@RunWith(RobolectricTestRunner::class)
class LicensesGrammarEntriesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `each reader grammar shows its copyright lines and its licence texts`() {
        val grammars = TextMateCodeHighlighter.GRAMMAR_ASSETS.values
        assertTrue("the registry is not empty", grammars.isNotEmpty())
        val entries = loadDependencies(context).map { it to licenseTextFor(context, it) }

        for (asset in grammars) {
            // Each notice file names the asset it covers; the generic MIT text names none.
            val matching = entries.filter { (_, text) -> text.contains("Shipped as app/src/main/assets/$asset,") }
            assertEquals("$asset has exactly one Licences entry", 1, matching.size)
            val (entry, text) = matching.single()
            val required = LICENCES[asset] ?: setOf(MIT)
            assertEquals("$asset: the entry's label", required, entry.license.split(" AND ").toSet())
            for (licence in required) assertWhole("$asset: the whole $licence text", text, licence)
            for (line in REQUIRED_LINES[asset].orEmpty()) {
                assertTrue("$asset: '$line'", text.lines().any { it.trim() == line })
            }

            // The notice is "header, then ===== name ===== body" per upstream; every body needs its own line.
            val sections = text.split(BANNER).drop(1).chunked(2)
                .associate { it.first().trim() to it.getOrElse(1) { "" } }
            assertTrue("$asset has upstream sections", sections.isNotEmpty())
            for ((name, body) in sections) {
                assertTrue("$asset: $name has a copyright line", COPYRIGHT.containsMatchIn(body))
                LICENCE_STARTS.filterValues { body.contains(it) }.keys
                    .forEach { assertWhole("$asset: $name's $it text is whole", body, it) }
            }

            for (upstream in upstreamsOf(asset)) {
                val own = sections[upstream]
                assertTrue("$asset: the notice has a section for its upstream $upstream", own != null)
                assertTrue("$asset: $upstream's section has a copyright line", COPYRIGHT.containsMatchIn(own!!))
            }
        }
    }

    /** Each licence's reference text, whitespace-normalised. */
    private val fullText: Map<String, String> by lazy {
        val mit = context.assets.open("licenses/MIT.txt").bufferedReader().use { it.readText() }
        val apache = context.assets.open("licenses/Apache-2.0.txt").bufferedReader().use { it.readText() }
        mapOf(
            // From the permission paragraph: MIT.txt's copyright line is a placeholder.
            MIT to normalised(mit.substring(mit.indexOf(MIT_START))),
            // The terms: the appendix and the URL scheme on the title page vary between copies.
            APACHE to normalised(
                apache.substring(apache.indexOf(APACHE_START), apache.indexOf(APACHE_END) + APACHE_END.length),
            ),
            // The repository's copies, not assets: the app ships these only inside the grammar notices.
            MPL to normalised(repositoryFile("licenses/MPL-2.0.txt")),
            TEXTMATE to normalised(repositoryFile("licenses/TextMate-Bundle-License.txt")),
        )
    }

    private fun repositoryFile(path: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, path) }
        .first { it.isFile }
        .readText()

    private fun normalised(text: String) = text.trim().replace(WHITESPACE, " ")

    private fun assertWhole(message: String, text: String, licence: String) =
        assertTrue(message, normalised(text).contains(fullText.getValue(licence)))

    /**
     * `org/repo` from the grammar's `version` field, `https://github.com/<org>/<repo>/commit/<sha>`, or
     * from [UNVERSIONED] for a grammar whose file records none; plus any [ALSO_DERIVED_FROM].
     */
    private fun upstreamsOf(asset: String): List<String> {
        val json = context.assets.open(asset).bufferedReader().use { it.readText() }
        val version = JSONObject(json).optString("version")
        val upstream = UPSTREAM.matchEntire(version)?.groupValues?.get(1) ?: UNVERSIONED[asset]
        check(upstream != null) { "$asset: version '$version' names no upstream, and UNVERSIONED has none" }
        return listOf(upstream) + ALSO_DERIVED_FROM[asset].orEmpty()
    }

    private companion object {
        const val MIT = "MIT"
        const val APACHE = "Apache-2.0"
        const val MPL = "MPL-2.0"
        const val TEXTMATE = "TextMate Bundle License"
        const val MIT_START = "Permission is hereby granted"
        const val APACHE_START = "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"
        const val APACHE_END = "END OF TERMS AND CONDITIONS"
        const val DOCKER = "textmate/docker/docker.tmLanguage.json"
        const val TOML = "textmate/toml/toml.tmLanguage.json"
        const val R = "textmate/r/r.tmLanguage.json"
        const val PHP = "textmate/php/php.tmLanguage.json"
        const val INI = "textmate/ini/ini.tmLanguage.json"
        const val LUA = "textmate/lua/lua.tmLanguage.json"
        const val PERL = "textmate/perl/perl.tmLanguage.json"
        const val JSONC = "textmate/jsonc/JSONC.tmLanguage.json"

        val UPSTREAM = Regex("https://github\\.com/([^/]+/[^/]+)/commit/[0-9a-f]+")

        /** Grammars whose `version` is absent or not a commit URL: the repository each was taken from. */
        val UNVERSIONED = mapOf(
            "textmate/graphql/graphql.json" to "graphql/graphiql",
            "textmate/http/http.tmLanguage.json" to "Huachao/vscode-restclient",
            "textmate/csv/csv.tmLanguage.json" to "mechatroner/vscode_rainbow_csv",
            "textmate/csv/tsv.tmLanguage.json" to "mechatroner/vscode_rainbow_csv",
            TOML to "tamasfe/taplo",
        )

        /** Projects a grammar was based on, beyond its upstream, whose notice must also appear. */
        val ALSO_DERIVED_FROM = mapOf(
            "textmate/julia/julia.tmLanguage.json" to listOf("JuliaLang/Julia.tmbundle"),
            TOML to listOf("oovm/vscode-toml"),
            R to listOf("REditorSupport/vscode-R", "randy3k/R-Box", "textmate/r.tmbundle"),
            PHP to listOf("textmate/php.tmbundle"),
            LUA to listOf("textmate/lua.tmbundle"),
            JSONC to listOf("textmate/json.tmbundle"),
        )

        /** The licences a grammar is under, where that is not MIT alone. */
        val LICENCES = mapOf(
            DOCKER to setOf(MIT, APACHE),
            TOML to setOf(MIT, MPL),
            R to setOf(MIT, TEXTMATE),
            PHP to setOf(MIT, TEXTMATE),
            INI to setOf(MIT, TEXTMATE),
            LUA to setOf(MIT, TEXTMATE),
            PERL to setOf(MIT, TEXTMATE),
            JSONC to setOf(MIT, TEXTMATE),
        )

        /** The line each licence's text opens with: a section holding it must hold the whole text. */
        val LICENCE_STARTS = mapOf(
            MIT to MIT_START,
            APACHE to APACHE_START,
            MPL to "Mozilla Public License Version 2.0",
            TEXTMATE to "Permission to copy, use, modify, sell and distribute this",
        )

        /** Lines a notice must carry beyond its licence texts: Docker's NOTICE file. */
        val REQUIRED_LINES = mapOf(
            DOCKER to listOf(
                "Docker",
                "Copyright 2012-2017 Docker, Inc.",
                "This product includes software developed at Docker, Inc. (https://www.docker.com).",
            ),
        )

        val WHITESPACE = Regex("\\s+")

        /**
         * The notices' section banner: exactly 78 `=`. The MPL-2.0 text underlines its own title with a
         * shorter run, which must not start a section.
         */
        val BANNER = Regex("^={78}$", RegexOption.MULTILINE)

        /**
         * A real holder, with or without `(c)` (Docker's NOTICE has none), not MIT.txt's
         * `<year> <copyright holders>` placeholder or Apache's bracketed `[yyyy]` one. A holder may end
         * with an e-mail address in angle brackets, as R-Box's does; the placeholder has no `@`.
         */
        val COPYRIGHT = Regex(
            "^Copyright (?:\\(c\\) )?[^<\\[\\n]+(?:<[^<>@\\s]+@[^<>\\s]+>)?$",
            RegexOption.MULTILINE,
        )
    }
}
