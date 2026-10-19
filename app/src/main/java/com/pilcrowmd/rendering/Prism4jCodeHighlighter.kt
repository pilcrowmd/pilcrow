// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

/**
 * Prism4j token type → [CodeRole], the one table both Prism4j paths read: [PilcrowTheme], when
 * Markwon colours a block at bind, and [Prism4jCodeHighlighter], when it is coloured off Main. A
 * type with no role of its own is [CodeRole.TEXT]. Tokens added for M-132/M-133 map ONLY to the added,
 * nullable roles, never to one of the original six, so a scheme that leaves those roles null colours
 * exactly the tokens it did before.
 */
internal object Prism4jRoles {
    private val ROLES: Map<String, CodeRole> = buildMap {
        fun role(role: CodeRole, vararg tokens: String) = tokens.forEach { put(it, role) }
        role(CodeRole.KEYWORD, "keyword", "boolean", "operator")
        role(CodeRole.STRING, "string", "char")
        role(CodeRole.NUMBER, "number", "constant")
        role(CodeRole.COMMENT, "comment")
        role(CodeRole.ERROR, "error", "invalid")
        role(CodeRole.FUNCTION, "function", "class-name", "attr-name", "tag")
        role(CodeRole.HEADING, "title")
        role(CodeRole.EMPHASIS, "bold", "italic")
        role(CodeRole.LINK, "url", "url-reference")
        role(CodeRole.MARKER, "list", "hr", "blockquote", "coord")
        role(CodeRole.LITERAL, "code", "regex", "symbol", "commit_sha1")
        role(CodeRole.VARIABLE, "variable", "property", "entity")
        role(CodeRole.BUILTIN, "builtin", "namespace", "selector", "atrule", "command")
        role(CodeRole.INSERTED, "inserted")
        role(CodeRole.DELETED, "deleted")
    }

    fun roleFor(tokenType: String): CodeRole = ROLES[tokenType] ?: CodeRole.TEXT
}

/**
 * Prism4j colours as role runs, for a Prism4j-language block that Markwon does not colour at bind:
 * a piece of a long block, or in the PDF a block over [MAX_HIGHLIGHT_CHARS] (issue #17). The scope
 * name is the fence's info string, looked up as Markwon looks it up; one with no grammar gives no runs.
 *
 * The runs come out in the order the reader's TextView ends up holding Markwon's spans: a token's
 * run before the runs of the tokens inside it, so where they overlap the inner one is drawn, as at
 * bind (a plain `${x}` inside a Kotlin string, `@import` inside a css at-rule). A [CodeRole.TEXT] token
 * inside a coloured one is kept, since it draws the text colour over it; one outside any coloured
 * token draws nothing a plain block does not, so it is left out. `Prism4jFidelityTest` compares every
 * character with Markwon's own render. Building and drawing the spans is left to the reader, a piece at
 * a time. Tokenising is not cheap on a phone: Prism4j's matching grows faster than the text, so one pass
 * over 388 KB of json took 9.6 to 11.0 s on the S24+ (0.3 s on the JVM), which is why json is windowed.
 *
 * Json is tokenised in windows of about [WINDOW_CHARS] chars (issue #17), and [tokenizeProgressively] hands
 * each window's runs over as soon as it is done. Windows keep the matching close to linear in the text.
 * Only json, and the choice is made on the grammar the fence resolves to (`json`, `jsonp`), because json is the one
 * grammar here whose tokens end at a line end. A window starts just after a `\n`. In Prism4j's json grammar
 * (checked in `Prism_json`) every token is one line: a string is `"(?:\\.|[^\\"\r\n])*"`, which cannot
 * hold a line end, and a number, a boolean, `null`, punctuation and `:` are single-line too; `\b` and `\B`
 * see the same on both sides of a cut, a `\n` before them or the start of the text. The one reach across a
 * line end is the lookahead `(?=\s*:)` / `(?!\s*:)` that tells a `property` from a `string`: `\s*` runs
 * over blank lines. So a window is never cut where `\s*:` matches at the cut, the same pattern, so the
 * same idea of whitespace on the running platform as the grammar's; the cut moves to the next line end.
 * A json block with no line end past the first [WINDOW_CHARS] (minified, on one line) is one window, and
 * paints only when it is all done. Windowed runs are then identical to the single pass's, character for
 * character (`Prism4jJsonWindowTest`). Every other grammar (strings and comments in kotlin, java, python, js
 * … span lines) is one pass over the whole block, as before. If Prism4j throws in a window, the windows
 * before it keep their runs, which are cached as the block's runs: the block is coloured up to there.
 *
 * Its own [Prism4j] and grammar locator, so a job here never shares a grammar cache with the Markwon
 * instances rendering on Main; one block is tokenised at a time, as in [TextMateCodeHighlighter]. The
 * cache holds the whole block's runs under the one key, whether it was tokenised in windows or in one.
 */
class Prism4jCodeHighlighter(grammarLocator: GrammarLocator = AliasGrammarLocator()) : CodeHighlighter {
    private val prism4j = Prism4j(grammarLocator)
    private val cacheLock = Any()
    private val tokenizeLock = Any()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tokenizeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cache = RunCache()

    override fun cachedRuns(scopeName: String, code: String): List<CodeRun>? = synchronized(cacheLock) {
        cache.get(scopeName, code)
    }

    override suspend fun tokenizeAsync(scopeName: String, code: String): List<CodeRun>? =
        withContext(tokenizeDispatcher) {
            val context = coroutineContext
            tokenize(scopeName, code) { context.isActive }
        }

    override suspend fun tokenizeProgressively(
        scopeName: String,
        code: String,
        onReady: suspend (runsSoFar: List<CodeRun>, coveredChars: Int) -> Unit,
    ): List<CodeRun>? = withContext(tokenizeDispatcher) {
        val context = coroutineContext
        cachedRuns(scopeName, code)?.let {
            onReady(it, code.length)
            return@withContext it
        }
        // The list is still growing when the next window starts: the caller gets a copy.
        tokenizeWindows(scopeName, code, { context.isActive }) { runs, covered -> onReady(runs.toList(), covered) }
    }

    override fun tokenize(scopeName: String, code: String, isActive: () -> Boolean): List<CodeRun>? =
        synchronized(tokenizeLock) {
            cachedRuns(scopeName, code)?.let { return it }
            tokenizeWindows(scopeName, code, isActive) { _, _ -> }
        }

    /**
     * Tokenise [code] a window at a time (json) or whole (anything else), calling [onWindow] with all the
     * runs so far and the chars they cover after each, and cache the result. Prism4j cannot stop mid-window,
     * so cancellation is read before each window and after the last. A window is tokenised under
     * [tokenizeLock]; [onWindow] runs outside it. Null if cancelled; an exception from Prism4j in a window ends
     * the loop there, and the runs of the windows before it are the block's.
     */
    private inline fun tokenizeWindows(
        scopeName: String,
        code: String,
        isActive: () -> Boolean,
        onWindow: (runs: List<CodeRun>, covered: Int) -> Unit,
    ): List<CodeRun>? {
        if (!isActive()) return null
        val runs = ArrayList<CodeRun>()
        val grammar = synchronized(tokenizeLock) { runCatching { prism4j.grammar(scopeName) }.getOrNull() }
        var from = 0
        while (grammar != null && from < code.length) {
            if (!isActive()) return null
            val to = if (grammar.name() == JSON) windowEnd(code, from) else code.length
            // Into a list of its own, so a window that throws leaves none of its runs behind.
            val window = ArrayList<CodeRun>()
            val tokenised = synchronized(tokenizeLock) {
                runCatching { runsOf(prism4j.tokenize(code.substring(from, to), grammar), from, window) }.isSuccess
            }
            if (!tokenised) break
            window.forEach { add(runs, it) }
            onWindow(runs, to)
            from = to
        }
        if (!isActive()) return null
        return runs.also { synchronized(cacheLock) { cache.put(scopeName, code, it) } }
    }

    /**
     * Where the window that starts at [from] ends: just after the first line end at or past
     * [WINDOW_CHARS] chars on, but not where the first non-blank char after it is a `:` (see the class
     * KDoc); [code]'s end if there is no such line end.
     */
    private fun windowEnd(code: String, from: Int): Int {
        var newline = code.indexOf('\n', from + WINDOW_CHARS - 1)
        while (newline >= 0 && newline + 1 < code.length) {
            if (!colonFollows(code, newline + 1)) return newline + 1
            newline = code.indexOf('\n', newline + 1)
        }
        return code.length
    }

    /** Whether `\s*:` matches [code] at [from]: the json grammar's lookahead would see a `:` from there. */
    private fun colonFollows(code: String, from: Int): Boolean =
        COLON_AFTER_BLANKS.matcher(code).region(from, code.length).lookingAt()

    /** The runs of [nodes], which tokenise the text that starts at [base], appended to [runs]. */
    private fun runsOf(nodes: List<Prism4j.Node>, base: Int, runs: MutableList<CodeRun>) {
        var position = base
        fun visit(children: List<Prism4j.Node>, insideColoured: Boolean) {
            for (node in children) {
                if (node is Prism4j.Syntax) {
                    val role = Prism4jRoles.roleFor(node.type())
                    val coloured = role != CodeRole.TEXT
                    val end = position + node.textLength()
                    if (end > position && (coloured || insideColoured)) add(runs, CodeRun(position, end, role))
                    visit(node.children(), insideColoured || coloured)
                } else {
                    position += node.textLength()
                }
            }
        }
        visit(nodes, insideColoured = false)
    }

    /** Touching runs of one role become one, so the block carries fewer spans. */
    private fun add(runs: MutableList<CodeRun>, run: CodeRun) {
        val last = runs.lastOrNull()
        if (last != null && last.role == run.role && last.end == run.start) {
            runs[runs.size - 1] = last.copy(end = run.end)
        } else {
            runs.add(run)
        }
    }

    private companion object {
        const val JSON = "json"

        /** A json window is about this many chars: past it, the next line end ends the window. */
        const val WINDOW_CHARS = 20_000

        /** What the json grammar's `property` lookahead looks for, on this platform's regex engine. */
        val COLON_AFTER_BLANKS: Pattern = Pattern.compile("\\s*:")
    }
}
