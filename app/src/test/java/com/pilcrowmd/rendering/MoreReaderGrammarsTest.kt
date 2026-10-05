// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import com.pilcrowmd.export.PdfContentLayoutBuilder
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-243 — PowerShell, batch, Julia, Dockerfile, GraphQL, HTTP, CSV/TSV, regex and TOML blocks take
 * their token colours through the real entry, the real Markwon instance and the real grammars, on
 * screen and in the PDF. The languages M-243 held back ship since NEW-35 (`TextMateBundleGrammarsTest`).
 *
 * Each probe names a character only one token covers and reads the innermost [ForegroundColorSpan]
 * over it. Prism4j has no grammar for these fences, so without the fence name's mapping nothing
 * colours its probes.
 */
@RunWith(RobolectricTestRunner::class)
class MoreReaderGrammarsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A clock that never moves, so the 1 s block budget can never decide these tests.
    private val highlighter = TextMateCodeHighlighter(context.assets::open, nanoTime = { 0L })

    /** A fence, its code, and the role each probe's first character must take. */
    private class Probe(val fence: String, val code: String, val roles: Map<String, CodeRole>)

    private val probes = listOf(
        Probe(
            "powershell",
            "function Get-Hi {\n  \$s = \"hello\" # say it\n}",
            mapOf("function" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "bat",
            "set X=\"hello\"\n:: say it\nif exist foo echo hi",
            mapOf("if" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, ":: say" to CodeRole.COMMENT),
        ),
        Probe(
            "julia",
            "function hi()\n    s = \"hello\" # say it\nend",
            mapOf("function" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "dockerfile",
            "FROM alpine\n# say it\nRUN echo \"hello\"",
            mapOf("FROM" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "graphql",
            "query Hi {\n  # say it\n  user(name: \"hello\") { id }\n}",
            mapOf("query" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "http",
            "# say it\nGET https://example.com HTTP/1.1\nAccept: text/plain",
            mapOf("GET" to CodeRole.KEYWORD, "text/plain" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "regex",
            "^\\d+(?#say it)[a-z]\$",
            mapOf("+" to CodeRole.KEYWORD, "[a-z]" to CodeRole.NUMBER, "(?#say" to CodeRole.COMMENT),
        ),
        // CSV columns 2 and 3 take the keyword and function roles from the reader's own column cycle.
        Probe("csv", "one,two,three", mapOf("two" to CodeRole.KEYWORD, "three" to CodeRole.FUNCTION)),
        Probe(
            "toml",
            "# say it\nname = \"hello\"\nflag = true",
            mapOf("true" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
    )

    private fun node(fence: String, code: String) = FencedCodeBlock().apply {
        info = fence
        literal = "$code\n"
    }

    private fun bindInline(scheme: PilcrowColorScheme, node: FencedCodeBlock): Spanned {
        val markwon = buildPilcrowMarkwon(context, scheme)
        val entry = FencedCodeBlockEntry(
            context,
            colorScheme = scheme,
            highlighting = CodeHighlighting(highlighter, scope = null),
        )
        val holder = entry.createHolder(LayoutInflater.from(context), FrameLayout(context))
        entry.bindHolder(markwon, holder, node)
        return holder.codeView.text as Spanned
    }

    private fun bindPdf(node: FencedCodeBlock): Spanned {
        val view = PdfContentLayoutBuilder(context, highlighter)
            .inflateMeasuredBlock(buildPrintMarkwon(context), node, LayoutInflater.from(context), PAGE_WIDTH_PX, 1f)
        return view.findViewById<TextView>(R.id.code_text).text as Spanned
    }

    private fun colourAt(text: Spanned, probe: String): Int? {
        val at = text.indexOf(probe)
        check(at >= 0) { "probe '$probe' not in the rendered text" }
        return text.getSpans(at, at + 1, ForegroundColorSpan::class.java)
            .minByOrNull { text.getSpanEnd(it) - text.getSpanStart(it) }
            ?.foregroundColor
    }

    private fun tokenColours(text: Spanned, scheme: PilcrowColorScheme): Set<Int> =
        text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            .map { it.foregroundColor }.toSet() - scheme.editorText.toArgb()

    private fun assertProbes(scheme: PilcrowColorScheme, render: (FencedCodeBlock) -> Spanned) {
        for (p in probes) {
            val text = render(node(p.fence, p.code))
            for ((probe, role) in p.roles) {
                assertEquals("${p.fence} '$probe'", role.colorIn(scheme.codeSyntax)?.toArgb(), colourAt(text, probe))
            }
        }
    }

    @Test
    fun `the M-243 languages are coloured in Dark`() = assertProbes(DarkColorScheme) { bindInline(DarkColorScheme, it) }

    @Test
    fun `the M-243 languages are coloured in the PDF`() = assertProbes(PrintColorScheme) { bindPdf(it) }

    @Test
    fun `every alias of a language selects its grammar`() {
        val aliases = mapOf(
            TextMateCodeHighlighter.POWERSHELL to listOf("powershell", "ps", "ps1", "pwsh", "posh"),
            TextMateCodeHighlighter.BATCH to listOf("bat", "batch", "cmd", "batchfile"),
            TextMateCodeHighlighter.JULIA to listOf("julia", "jl"),
            TextMateCodeHighlighter.DOCKERFILE to listOf("dockerfile", "docker", "containerfile"),
            TextMateCodeHighlighter.GRAPHQL to listOf("graphql", "gql"),
            TextMateCodeHighlighter.HTTP to listOf("http", "rest"),
            TextMateCodeHighlighter.CSV to listOf("csv"),
            TextMateCodeHighlighter.TSV to listOf("tsv"),
            TextMateCodeHighlighter.REGEX to listOf("regex", "regexp"),
            TextMateCodeHighlighter.TOML to listOf("toml"),
        )
        for ((scope, fences) in aliases) {
            for (fence in fences) assertEquals(fence, scope, TextMateCodeHighlighter.scopeForFence(fence))
        }
    }

    @Test
    fun `no CSV or TSV column takes the comment or error colour, and columns differ`() {
        val syntax = DarkColorScheme.codeSyntax
        val forbidden = setOf(syntax.comment.toArgb(), syntax.error.toArgb())
        for ((fence, separator) in listOf("csv" to ",", "tsv" to "\t")) {
            // Twelve columns: past Rainbow CSV's ten, so column 4 (`comment.rainbow4`) and column 10
            // (`invalid.rainbow10`) are both there.
            val columns = (1..12).map { "col$it" }
            val text = bindInline(DarkColorScheme, node(fence, columns.joinToString(separator)))
            val used = tokenColours(text, DarkColorScheme)
            assertEquals("$fence: no comment or error colour", emptySet<Int>(), used intersect forbidden)
            assertEquals("$fence column 2", syntax.keyword.toArgb(), colourAt(text, "col2"))
            assertEquals("$fence column 4", syntax.builtin!!.toArgb(), colourAt(text, "col4"))
            assertEquals("$fence column 10", syntax.builtin!!.toArgb(), colourAt(text, "col10"))
        }
    }

    @Test
    fun `an embedded language that does not ship stays plain and the block around it is coloured`() {
        // HTTP embeds source.json for a JSON body; it does not ship, so the body is plain.
        val code = "POST https://example.com HTTP/1.1\nContent-Type: application/json\n\n{\"a\": \"hello\"}"
        val text = bindInline(DarkColorScheme, node("http", code))
        assertEquals("request line", DarkColorScheme.codeSyntax.keyword.toArgb(), colourAt(text, "POST"))
        val body = text.indexOf("{\"a\"")
        assertTrue(
            "the JSON body is plain",
            text.getSpans(body, text.length, ForegroundColorSpan::class.java)
                .none { it.foregroundColor != DarkColorScheme.editorText.toArgb() && text.getSpanEnd(it) > body },
        )
    }

    @Test
    fun `Julia's one regex joni cannot compile leaves the rest of the block coloured`() {
        // `(?<=\S\s+)\b(as)\b` has a variable-length look-behind joni rejects: `as` stays plain, and
        // tm4e skips that rule rather than failing the line or the block.
        val text = bindInline(DarkColorScheme, node("julia", "import A as B\nfunction f()\nend"))
        val keyword = DarkColorScheme.codeSyntax.keyword.toArgb()
        assertEquals("import", keyword, colourAt(text, "import"))
        assertEquals("the line after", keyword, colourAt(text, "function"))
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
    }
}
