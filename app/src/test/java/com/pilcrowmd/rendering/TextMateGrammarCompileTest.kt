// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.oniguruma.impl.joni.JoniOnigRegExp
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.Registry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every regex in every shipped TextMate grammar compiles under joni, the engine Sora's tm4e runs on the
 * device and under Robolectric (NEW-27), except the patterns named in `KNOWN_REJECTED` (NEW-28).
 *
 * A pattern joni rejects does not fail loudly: Sora's `JoniOnigSearcher` logs the error and compiles
 * `^$` in its place, so the rule silently never matches and the colours are merely wrong. The first
 * test therefore compiles each pattern itself, through the same `JoniOnigRegExp` tm4e uses, and the
 * others pin what the three rewritten patterns do.
 */
@RunWith(RobolectricTestRunner::class)
class TextMateGrammarCompileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val highlighter = TextMateCodeHighlighter(context.assets::open, nanoTime = { 0L })

    @Test
    fun `every pattern of every shipped grammar compiles under joni`() {
        val failures = ArrayList<String>()
        val knownRejected = HashMap<String, MutableSet<String>>()
        for (asset in TextMateCodeHighlighter.GRAMMAR_ASSETS.values + EDITOR_MARKDOWN_GRAMMAR) {
            val patterns = ArrayList<Pair<String, String>>()
            collectPatterns(JSONObject(readAsset(asset)), patterns)
            // A floor per grammar, so a grammar cut short (or a collector that misses a key) fails.
            val floor = SMALL_GRAMMAR_FLOORS[asset] ?: MIN_PATTERNS
            assertTrue("$asset has ${patterns.size} patterns, floor $floor", patterns.size >= floor)
            for ((key, pattern) in patterns) {
                // tm4e substitutes an end/while back-reference with the begin's captured text before
                // compiling, so a stand-in literal compiles what the device compiles.
                val compiled = if (key == "end" || key == "while") pattern.replace(BACK_REFERENCE, "x") else pattern
                runCatching { JoniOnigRegExp(compiled) }.onFailure {
                    if (pattern in KNOWN_REJECTED[asset].orEmpty()) {
                        knownRejected.getOrPut(asset) { HashSet() } += pattern
                    } else {
                        failures += "$asset $key: ${it.cause?.message ?: it.message} :: $pattern"
                    }
                }
            }
        }
        assertEquals("patterns joni rejects", emptyList<String>(), failures)
        // A named exception that starts compiling, or stops being shipped, must be removed from the list.
        assertEquals("the known rejections still occur", KNOWN_REJECTED, knownRejected)
    }

    @Test
    fun `TypeScript declarations after a using declaration keep their colours`() {
        // The `using` declaration's end pattern was the one joni rejected; with `^$` in its place the
        // declaration never ended before a blank line, so every line below it read as variable names.
        val code = listOf(
            "using res = open()",
            "function f() {}",
            "class C {}",
            "interface I {}",
            "enum E {}",
            "import { a } from \"b\"",
        ).joinToString("\n")
        val runs = highlighter.tokenize(TextMateCodeHighlighter.TYPESCRIPT, code)!!
        val expected = listOf(
            Triple("function f() {}", "function", CodeRole.KEYWORD),
            Triple("function f() {}", "f(", CodeRole.FUNCTION),
            Triple("class C {}", "class", CodeRole.KEYWORD),
            Triple("class C {}", "C", CodeRole.FUNCTION),
            Triple("interface I {}", "interface", CodeRole.KEYWORD),
            Triple("interface I {}", "I", CodeRole.FUNCTION),
            Triple("enum E {}", "enum", CodeRole.KEYWORD),
            Triple("enum E {}", "E", CodeRole.FUNCTION),
            Triple("import { a } from \"b\"", "import", CodeRole.KEYWORD),
            Triple("import { a } from \"b\"", "\"b\"", CodeRole.STRING),
        )
        for ((line, word, role) in expected) {
            assertEquals("'$word' in '$line'", role, roleAt(runs, code, line, word))
        }
    }

    @Test
    fun `Ruby block parameters open after a brace or do`() {
        // The parameter list's begin pattern was the one joni rejected; `|` then read as an operator.
        val code = "[1].each { |x| x }\nfoo do |a| end\nbar {    |y| y }"
        val runs = highlighter.tokenize(TextMateCodeHighlighter.RUBY, code)!!
        for ((line, name) in listOf("[1].each { |x| x }" to "x", "foo do |a| end" to "a", "bar {    |y| y }" to "y")) {
            assertEquals("'|' in '$line'", null, roleAt(runs, code, line, "|"))
            assertEquals("'$name' in '$line'", CodeRole.VARIABLE, roleAt(runs, code, line, "$name|"))
        }
    }

    @Test
    fun `the editor's Markdown grammar recognises strikethrough`() {
        val registry = Registry(
            object : IRegistryOptions {
                override fun getGrammarSource(scopeName: String): IGrammarSource? {
                    if (scopeName != MARKDOWN_SCOPE) return null
                    val json = readAsset(EDITOR_MARKDOWN_GRAMMAR)
                    return IGrammarSource.fromString(IGrammarSource.ContentType.JSON, json)
                }
            },
        )
        val grammar = registry.loadGrammar(MARKDOWN_SCOPE)!!
        fun scopesAt(line: String, probe: String): List<String> {
            val at = line.indexOf(probe)
            val tokens = grammar.tokenizeLine(line, null as IStateStack?, null).tokens
            return tokens.first { at >= it.startIndex && at < it.endIndex }.scopes
        }
        assertTrue("~~del~~", STRIKE in scopesAt("a ~~del~~ b", "del"))
        assertTrue("opening ~~", "punctuation.definition.strikethrough.markdown" in scopesAt("a ~~del~~ b", "~~"))
        // The rewritten part: a closing run after `_` must not be followed by a word character.
        assertTrue("~~a_~~ b", STRIKE in scopesAt("~~a_~~ b", "a_"))
        assertTrue("~~a_~~b", STRIKE !in scopesAt("~~a_~~b", "a_"))
    }

    /** The role of the run covering the first character of [word] within [line] of [code], or null. */
    private fun roleAt(runs: List<CodeRun>, code: String, line: String, word: String): CodeRole? {
        val lineStart = code.indexOf(line)
        check(lineStart >= 0) { "line '$line' not in the code" }
        val at = lineStart + line.indexOf(word).also { check(it >= 0) { "'$word' not in '$line'" } }
        return runs.firstOrNull { at >= it.start && at < it.end }?.role
    }

    private fun readAsset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Every `match`, `begin`, `end` and `while` regex in [node], however deeply nested. */
    private fun collectPatterns(node: Any?, into: MutableList<Pair<String, String>>) {
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                val value = node.get(key)
                if (key in REGEX_KEYS && value is String) into += key to value else collectPatterns(value, into)
            }
            is JSONArray -> for (i in 0 until node.length()) collectPatterns(node.get(i), into)
        }
    }

    private companion object {
        /** The editor's grammar (`Editor.kt`); it is not in the reader's registry but runs on the same engine. */
        const val EDITOR_MARKDOWN_GRAMMAR = "textmate/markdown/markdown.tmLanguage.json"
        const val MARKDOWN_SCOPE = "text.html.markdown"
        const val STRIKE = "markup.strikethrough.markdown"
        const val MIN_PATTERNS = 50

        /** Small upstream grammars, with lower floors (measured: 1, 1, 9, 24, 42, 44, 15 and 23 patterns). */
        val SMALL_GRAMMAR_FLOORS = mapOf(
            "textmate/csv/csv.tmLanguage.json" to 1,
            "textmate/csv/tsv.tmLanguage.json" to 1,
            "textmate/docker/docker.tmLanguage.json" to 5,
            "textmate/http/http.tmLanguage.json" to 20,
            "textmate/regex/MagicRegExp.tmLanguage.json" to 35,
            "textmate/toml/toml.tmLanguage.json" to 35,
            "textmate/ini/ini.tmLanguage.json" to 12,
            "textmate/jsonc/JSONC.tmLanguage.json" to 20,
        )

        /**
         * Patterns joni rejects in grammars that ship unmodified (NEW-28); each rule never matches on the
         * device. Julia's `as` keyword needs a variable-length look-behind, so `as` stays plain.
         */
        val KNOWN_REJECTED: Map<String, Set<String>> = mapOf(
            "textmate/julia/julia.tmLanguage.json" to setOf("""(?<=\S\s+)\b(as)\b(?=\s+\S)"""),
        )
        val REGEX_KEYS = setOf("match", "begin", "end", "while")
        val BACK_REFERENCE = Regex("""\\(\d+)""")
    }
}
