// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.util.Log
import androidx.compose.ui.graphics.Color
import com.pilcrowmd.ui.theme.CodeSyntaxColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.grammar.IToken
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.Registry
import java.io.InputStream
import java.time.Duration
import kotlin.coroutines.coroutineContext

/** A code-token role: which [CodeSyntaxColors] entry colours it. A null colour leaves the run plain. */
enum class CodeRole(val colorIn: (CodeSyntaxColors) -> Color?) {
    KEYWORD(CodeSyntaxColors::keyword),
    STRING(CodeSyntaxColors::string),
    NUMBER(CodeSyntaxColors::number),
    COMMENT(CodeSyntaxColors::comment),
    ERROR(CodeSyntaxColors::error),
    FUNCTION(CodeSyntaxColors::function),
    LITERAL(CodeSyntaxColors::literal),
    VARIABLE(CodeSyntaxColors::variable),
    BUILTIN(CodeSyntaxColors::builtin),
}

/** One coloured stretch of a code block's literal: `[start, end)` in the literal's own offsets. */
data class CodeRun(val start: Int, val end: Int, val role: CodeRole)

/**
 * Colours code blocks in the languages Prism4j has no grammar for (M-136). The reader calls
 * [cachedRuns] on Main at bind time and [tokenizeAsync] off it; the PDF, which has no scope, calls
 * [tokenize] inline. The interface is the test seam: a fake can make [tokenizeAsync] suspend.
 */
interface CodeHighlighter {
    /** Runs already computed for exactly this [code], or null. Cheap: safe on Main. */
    fun cachedRuns(scopeName: String, code: String): List<CodeRun>?

    /** Tokenise [code] within the budget and cache the result. Blocking. Null if it was cancelled. */
    fun tokenize(scopeName: String, code: String, isActive: () -> Boolean = { true }): List<CodeRun>?

    /** [tokenize] on [Dispatchers.Default], stopping between lines once the caller is cancelled. */
    suspend fun tokenizeAsync(scopeName: String, code: String): List<CodeRun>? = withContext(Dispatchers.Default) {
        val context = coroutineContext
        tokenize(scopeName, code) { context.isActive }
    }
}

/** The reader's highlighter plus the scope its jobs run in. A null [scope] tokenises inline (the PDF). */
class CodeHighlighting(val highlighter: CodeHighlighter, val scope: CoroutineScope?)

/**
 * TextMate (tm4e) highlighting for the languages Prism4j has no grammar for, from the unmodified
 * grammars in `assets/textmate/<lang>/` ([GRAMMAR_ASSETS]): bash, TypeScript, Rust and Ruby (M-136);
 * PowerShell, batch, Julia, Dockerfile, GraphQL, HTTP, CSV/TSV, regex and TOML (M-243); R, PHP, INI,
 * Lua, Perl and JSONC (NEW-35). The other 25 languages stay on Prism4j.
 *
 * The [Registry] is this class's own, not Sora's `GrammarRegistry` singleton, and serves only the
 * shipped scopes: any other scope a grammar embeds (Ruby's heredoc SQL, HTTP's JSON body, Julia's
 * R and Python strings) resolves to null, and tm4e leaves that text unstyled rather than failing.
 *
 * Budget, per block: the first [MAX_LINES] lines, a line longer than [MAX_LINE_CHARS] stays plain, and
 * tokenising stops once [BLOCK_BUDGET_MS] has passed. Past any of them the rest of the block is plain.
 * Runs are cached by (scope, text), so a rebind or an adapter swap (theme, pinch) does not tokenise
 * again; the runs carry roles, not colours, so one entry serves every theme.
 *
 * @param openAsset opens an asset path; the app passes `context.assets::open`.
 * @param nanoTime the clock the block budget reads; a test can move it.
 */
class TextMateCodeHighlighter(
    private val openAsset: (String) -> InputStream,
    private val nanoTime: () -> Long = System::nanoTime,
) : CodeHighlighter {

    // Two locks: Main reads the cache at bind time and must never wait behind a block being tokenised.
    private val cacheLock = Any()
    private val tokenizeLock = Any()

    // The reader's jobs run one at a time on this view of Default, so a block queued behind another
    // waits as a suspended coroutine, not as a Default worker parked on tokenizeLock. The lock stays
    // because the PDF calls tokenize inline from IO and could otherwise race the reader's job.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val tokenizeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val grammars = HashMap<String, IGrammar?>()
    private val cache = RunCache()

    private val registry: Registry by lazy {
        Registry(
            object : IRegistryOptions {
                override fun getGrammarSource(scopeName: String): IGrammarSource? {
                    val path = GRAMMAR_ASSETS[scopeName] ?: return null
                    val json = openAsset(path).use { it.readBytes().toString(Charsets.UTF_8) }
                    return IGrammarSource.fromString(IGrammarSource.ContentType.JSON, json)
                }
            },
        )
    }

    override fun cachedRuns(scopeName: String, code: String): List<CodeRun>? = synchronized(cacheLock) {
        cache.get(scopeName, code)
    }

    override suspend fun tokenizeAsync(scopeName: String, code: String): List<CodeRun>? =
        withContext(tokenizeDispatcher) {
            val context = coroutineContext
            tokenize(scopeName, code) { context.isActive }
        }

    // tm4e grammars are not documented as thread-safe, so one block is tokenised at a time.
    override fun tokenize(scopeName: String, code: String, isActive: () -> Boolean): List<CodeRun>? =
        synchronized(tokenizeLock) {
            cachedRuns(scopeName, code)?.let { return it }
            val grammar = grammarFor(scopeName) ?: return emptyList()
            val runs = runCatching { tokenizeBlock(grammar, code, isActive) }
                .onFailure { Log.w(TAG, "tokenise failed for $scopeName: ${it.message}") }
                .getOrDefault(emptyList())
            runs?.also { synchronized(cacheLock) { cache.put(scopeName, code, it) } }
        }

    /** The grammar for [scopeName], loaded once; null (and remembered as null) if it cannot load. */
    private fun grammarFor(scopeName: String): IGrammar? {
        if (scopeName !in GRAMMAR_ASSETS) return null
        return grammars.getOrPut(scopeName) {
            runCatching { registry.loadGrammar(scopeName) }
                .onFailure { Log.w(TAG, "grammar $scopeName failed to load: ${it.message}") }
                .getOrNull()
        }
    }

    private fun tokenizeBlock(grammar: IGrammar, code: String, isActive: () -> Boolean): List<CodeRun>? {
        val runs = RunBuilder()
        val start = nanoTime()
        var state: IStateStack? = null
        var lineStart = 0
        var lineIndex = 0
        var stopped = false
        while (!stopped && lineStart <= code.length && lineIndex < MAX_LINES) {
            if (!isActive()) return null
            val left = BLOCK_BUDGET_MS - (nanoTime() - start) / NANOS_PER_MS
            val newline = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
            val line = code.substring(lineStart, newline).removeSuffix("\r")
            when {
                left <= 0 -> stopped = true
                // An over-long line stays plain; the lines after it keep the state from the line before.
                line.length > MAX_LINE_CHARS -> Unit
                else -> {
                    val result = grammar.tokenizeLine(line, state, Duration.ofMillis(left))
                    runs.addLine(lineStart, line.length, result.tokens)
                    // Stopped mid-line: that line keeps its partial tokens and the rest of the block is plain.
                    stopped = result.isStoppedEarly
                    state = result.ruleStack
                }
            }
            lineStart = newline + 1
            lineIndex++
        }
        return runs.build()
    }

    /** Merges touching runs of the same role, so a block carries one span per coloured stretch. */
    private class RunBuilder {
        private val runs = ArrayList<CodeRun>()

        /** Add one line's tokens; the line starts at [lineStart] in the block and is [length] long. */
        fun addLine(lineStart: Int, length: Int, tokens: Array<IToken>) = tokens.forEach { token ->
            val role = roleFor(token.scopes)
            if (role != null) add(lineStart + token.startIndex, lineStart + minOf(token.endIndex, length), role)
        }

        fun add(start: Int, end: Int, role: CodeRole) {
            if (end <= start) return
            val last = runs.lastOrNull()
            if (last != null && last.role == role && last.end == start) {
                runs[runs.size - 1] = last.copy(end = end)
            } else {
                runs.add(CodeRun(start, end, role))
            }
        }

        fun build(): List<CodeRun> = runs
    }

    /**
     * LRU of computed runs, bounded by [MAX_ENTRIES] and [MAX_CACHED_CHARS] of cached text. The key is
     * (scope, text hash); an entry counts only if its whole text equals the block's, so a hash
     * collision can never apply another block's runs.
     */
    private class RunCache {
        private class Entry(val code: String, val runs: List<CodeRun>)

        private val entries = LinkedHashMap<Pair<String, Int>, Entry>(MAX_ENTRIES, LOAD_FACTOR, true)
        private var chars = 0L

        fun get(scopeName: String, code: String): List<CodeRun>? =
            entries[scopeName to code.hashCode()]?.takeIf { it.code.length == code.length && it.code == code }?.runs

        fun put(scopeName: String, code: String, runs: List<CodeRun>) {
            if (code.length > MAX_CACHED_CHARS) return
            entries.put(scopeName to code.hashCode(), Entry(code, runs))?.let { chars -= it.code.length }
            chars += code.length
            val eldest = entries.entries.iterator()
            while ((entries.size > MAX_ENTRIES || chars > MAX_CACHED_CHARS) && eldest.hasNext()) {
                chars -= eldest.next().value.code.length
                eldest.remove()
            }
        }
    }

    companion object {
        private const val TAG = "TextMateCodeHighlighter"

        /** Lines coloured per block; the rest stays plain. */
        const val MAX_LINES = 2_000

        /** A line longer than this stays plain. */
        const val MAX_LINE_CHARS = 5_000

        /** Time allowed per block before the rest stays plain. */
        const val BLOCK_BUDGET_MS = 1_000L

        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_ENTRIES = 64
        private const val MAX_CACHED_CHARS = 1_000_000L
        private const val LOAD_FACTOR = 0.75f

        const val SHELL = "source.shell"
        const val TYPESCRIPT = "source.ts"
        const val RUST = "source.rust"
        const val RUBY = "source.ruby"
        const val POWERSHELL = "source.powershell"
        const val BATCH = "source.batchfile"
        const val JULIA = "source.julia"
        const val DOCKERFILE = "source.dockerfile"
        const val GRAPHQL = "source.graphql"
        const val HTTP = "source.http"
        const val CSV = "text.csv"
        const val TSV = "text.tsv"
        const val REGEX = "source.regexp.python"
        const val TOML = "source.toml"
        const val R = "source.r"
        const val PHP = "source.php"
        const val INI = "source.ini"
        const val LUA = "source.lua"
        const val PERL = "source.perl"
        const val JSONC = "source.json.comments"

        /** The shipped grammars, scope → asset path. The Licences screen must carry a notice for each. */
        internal val GRAMMAR_ASSETS = mapOf(
            SHELL to "textmate/shellscript/shell-unix-bash.tmLanguage.json",
            TYPESCRIPT to "textmate/typescript/TypeScript.tmLanguage.json",
            RUST to "textmate/rust/rust.tmLanguage.json",
            RUBY to "textmate/ruby/ruby.tmLanguage.json",
            POWERSHELL to "textmate/powershell/powershell.tmLanguage.json",
            BATCH to "textmate/bat/batchfile.tmLanguage.json",
            JULIA to "textmate/julia/julia.tmLanguage.json",
            DOCKERFILE to "textmate/docker/docker.tmLanguage.json",
            GRAPHQL to "textmate/graphql/graphql.json",
            HTTP to "textmate/http/http.tmLanguage.json",
            CSV to "textmate/csv/csv.tmLanguage.json",
            TSV to "textmate/csv/tsv.tmLanguage.json",
            REGEX to "textmate/regex/MagicRegExp.tmLanguage.json",
            TOML to "textmate/toml/toml.tmLanguage.json",
            R to "textmate/r/r.tmLanguage.json",
            PHP to "textmate/php/php.tmLanguage.json",
            INI to "textmate/ini/ini.tmLanguage.json",
            LUA to "textmate/lua/lua.tmLanguage.json",
            PERL to "textmate/perl/perl.tmLanguage.json",
            JSONC to "textmate/jsonc/JSONC.tmLanguage.json",
        )

        /** Fence names. `console` and `tsx` are deliberately absent: they stay plain. */
        private val FENCE_SCOPES = mapOf(
            "sh" to SHELL,
            "bash" to SHELL,
            "shell" to SHELL,
            "zsh" to SHELL,
            "ts" to TYPESCRIPT,
            "typescript" to TYPESCRIPT,
            "rs" to RUST,
            "rust" to RUST,
            "rb" to RUBY,
            "ruby" to RUBY,
            "powershell" to POWERSHELL,
            "ps" to POWERSHELL,
            "ps1" to POWERSHELL,
            "pwsh" to POWERSHELL,
            "posh" to POWERSHELL,
            "bat" to BATCH,
            "batch" to BATCH,
            "cmd" to BATCH,
            "batchfile" to BATCH,
            "julia" to JULIA,
            "jl" to JULIA,
            "dockerfile" to DOCKERFILE,
            "docker" to DOCKERFILE,
            "containerfile" to DOCKERFILE,
            "graphql" to GRAPHQL,
            "gql" to GRAPHQL,
            "http" to HTTP,
            "rest" to HTTP,
            "csv" to CSV,
            "tsv" to TSV,
            "regex" to REGEX,
            "regexp" to REGEX,
            "toml" to TOML,
            "r" to R,
            "rscript" to R,
            "php" to PHP,
            "ini" to INI,
            "cfg" to INI,
            "dosini" to INI,
            "lua" to LUA,
            "perl" to PERL,
            "pl" to PERL,
            "jsonc" to JSONC,
        )

        /** The grammar scope for a fence's info string ([fenceLanguage]), or null to leave it to Prism4j. */
        fun scopeForFence(info: String?): String? = FENCE_SCOPES[fenceLanguage(info)]

        /**
         * TextMate scope prefix → role; null means "plain, stop looking". A token's scopes are read from
         * the most specific outwards; for each, the longest matching prefix decides, and a scope with no
         * matching prefix (`meta.…`, `punctuation.…`) defers to the one around it, so a comment's `#` or
         * a string's quotes take the comment or string colour. Mirrors Prism4jTheme's token roles.
         */
        private val SCOPE_ROLES: Map<String, CodeRole?> = mapOf(
            "comment" to CodeRole.COMMENT,
            "string" to CodeRole.STRING,
            "string.regexp" to CodeRole.LITERAL,
            "constant.numeric" to CodeRole.NUMBER,
            "constant.language" to CodeRole.KEYWORD,
            "constant.character.escape" to CodeRole.STRING,
            "constant.other.symbol" to CodeRole.LITERAL,
            "constant.language.symbol" to CodeRole.LITERAL,
            "constant" to CodeRole.NUMBER,
            "keyword" to CodeRole.KEYWORD,
            "storage" to CodeRole.KEYWORD,
            "variable.language" to CodeRole.KEYWORD,
            "entity.name.function" to CodeRole.FUNCTION,
            "support.function" to CodeRole.FUNCTION,
            "entity.name.command" to CodeRole.FUNCTION,
            "entity.name.type" to CodeRole.FUNCTION,
            "entity.name.class" to CodeRole.FUNCTION,
            "entity.other.inherited-class" to CodeRole.FUNCTION,
            "entity.name.tag" to CodeRole.FUNCTION,
            "entity.other.attribute-name" to CodeRole.FUNCTION,
            "support.class" to CodeRole.FUNCTION,
            "support.type" to CodeRole.BUILTIN,
            "entity.name.namespace" to CodeRole.BUILTIN,
            "entity.name.module" to CodeRole.BUILTIN,
            // An INI `[section]` header, coloured as TOML's `[table]` name is (`support.type`, NEW-35).
            "entity.name.section" to CodeRole.BUILTIN,
            "support.variable" to CodeRole.BUILTIN,
            "variable" to CodeRole.VARIABLE,
            // Plain identifiers stay plain, as Prism4j leaves them in JavaScript; sigil variables
            // (`$x`, `@x`) keep the variable colour through the entries above and below.
            "variable.other.readwrite" to null,
            "variable.other.object" to null,
            "variable.other.property" to null,
            "variable.other.constant" to null,
            "variable.other.rust" to null,
            "variable.ruby" to null,
            "variable.other.readwrite.instance" to CodeRole.VARIABLE,
            "variable.other.readwrite.class" to CodeRole.VARIABLE,
            "variable.other.readwrite.global" to CodeRole.VARIABLE,
            "invalid" to CodeRole.ERROR,
        )

        /**
         * Rainbow CSV names its columns `rainbow1`, `keyword.rainbow2`, … `comment.rainbow4`, …
         * `invalid.rainbow10`, borrowing other scopes only to get ten colours. Read through [SCOPE_ROLES],
         * column 4 would look like a comment and column 10 like an error, so a column takes its colour
         * from this cycle instead: plain first, as Rainbow CSV leaves it, then five roles that are
         * neither a comment nor an error (M-243).
         */
        private val CSV_COLUMN_ROLES: List<CodeRole?> = listOf(
            null,
            CodeRole.KEYWORD,
            CodeRole.FUNCTION,
            CodeRole.BUILTIN,
            CodeRole.STRING,
            CodeRole.NUMBER,
        )

        private val CSV_COLUMN = Regex("(?:^|\\.)rainbow(\\d{1,4})$")

        /** The role of a token with these scopes (outermost first, as tm4e lists them), or null for plain. */
        fun roleFor(scopes: List<String>): CodeRole? {
            for (i in scopes.indices.reversed()) {
                CSV_COLUMN.find(scopes[i])?.let { return csvColumnRole(it.groupValues[1].toInt()) }
                val match = longestPrefix(scopes[i]) ?: continue
                return SCOPE_ROLES[match]
            }
            return null
        }

        private fun csvColumnRole(column: Int): CodeRole? = CSV_COLUMN_ROLES[(column - 1).mod(CSV_COLUMN_ROLES.size)]

        private fun longestPrefix(scope: String): String? {
            var candidate: String? = scope
            while (candidate != null) {
                if (SCOPE_ROLES.containsKey(candidate)) return candidate
                candidate = candidate.substringBeforeLast('.', "").ifEmpty { null }
            }
            return null
        }
    }
}
