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
import com.pilcrowmd.ui.theme.LightCodeSyntax
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.PilcrowColorScheme
import com.pilcrowmd.ui.theme.PrintColorScheme
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NEW-35 — R, PHP, INI, Lua, Perl and JSONC blocks take their token colours through the real entry,
 * the real Markwon instance and the real grammars, on screen and in the PDF.
 *
 * Each probe names a character only one token covers and reads the innermost [ForegroundColorSpan]
 * over it. Prism4j has no grammar for these fences (and `jsonc` is no longer a Prism4j alias), so
 * without the fence name's mapping nothing colours its probes.
 */
@RunWith(RobolectricTestRunner::class)
class TextMateBundleGrammarsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // A clock that never moves, so the 1 s block budget can never decide these tests.
    private val highlighter = TextMateCodeHighlighter(context.assets::open, nanoTime = { 0L })

    /** A fence, its code, and the role each probe's first character must take. */
    private class Probe(val fence: String, val code: String, val roles: Map<String, CodeRole>)

    private val probes = listOf(
        Probe(
            "r",
            "square <- function(x) {\n  # say it\n  y <- \"hello\"\n  if (TRUE) x^2 else 0\n}",
            mapOf("function" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "php",
            "<?php\nfunction hi(\$name) {\n    // say it\n    return \"hello \" . \$name;\n}",
            mapOf("return" to CodeRole.KEYWORD, "\"hello" to CodeRole.STRING, "// say" to CodeRole.COMMENT),
        ),
        Probe(
            "ini",
            "; say it\n[server]\nname = \"hello\"",
            mapOf(
                "name" to CodeRole.KEYWORD,
                "\"hello\"" to CodeRole.STRING,
                "; say" to CodeRole.COMMENT,
                "server" to CodeRole.BUILTIN,
            ),
        ),
        Probe(
            "lua",
            "-- say it\nlocal function hi(name)\n  return \"hello\" .. name\nend",
            mapOf("local" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "-- say" to CodeRole.COMMENT),
        ),
        Probe(
            "perl",
            "# say it\nmy \$name = \"hello\";\nsub hi { return 1; }",
            mapOf("my" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "# say" to CodeRole.COMMENT),
        ),
        Probe(
            "jsonc",
            "{\n  // say it\n  \"name\": \"hello\",\n  \"on\": true\n}",
            mapOf("true" to CodeRole.KEYWORD, "\"hello\"" to CodeRole.STRING, "// say" to CodeRole.COMMENT),
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

    private fun assertProbes(scheme: PilcrowColorScheme, render: (FencedCodeBlock) -> Spanned) {
        for (p in probes) {
            val text = render(node(p.fence, p.code))
            for ((probe, role) in p.roles) {
                assertEquals("${p.fence} '$probe'", role.colorIn(scheme.codeSyntax)?.toArgb(), colourAt(text, probe))
            }
        }
    }

    @Test
    fun `R, PHP, INI, Lua, Perl and JSONC are coloured in Dark`() =
        assertProbes(DarkColorScheme) { bindInline(DarkColorScheme, it) }

    @Test
    fun `R, PHP, INI, Lua, Perl and JSONC are coloured in the PDF`() = assertProbes(PrintColorScheme) { bindPdf(it) }

    @Test
    fun `a sigil variable is coloured as a variable in PHP and Perl, in Dark and in Light`() {
        val samples = mapOf(
            "php" to "<?php\nfunction hi(\$name) {\n    return \$name;\n}",
            "perl" to "my \$name = 1;\nprint \$name;",
        )
        for ((label, scheme) in mapOf("Dark" to DarkColorScheme, "Light" to LightColorScheme)) {
            // Both themes define a variable colour, so a plain `$name` cannot pass for a coloured one.
            val variable = requireNotNull(CodeRole.VARIABLE.colorIn(scheme.codeSyntax)) { "$label: no variable colour" }
            for ((fence, code) in samples) {
                val text = bindInline(scheme, node(fence, code))
                assertEquals("$fence '\$name' in $label", variable.toArgb(), colourAt(text, "\$name"))
            }
        }
    }

    @Test
    fun `the PDF colours a PHP variable and an INI section header with Light's tokens (NEW-43)`() {
        // Pinned against LightCodeSyntax itself, not PrintColorScheme.codeSyntax, so the ruling is the
        // oracle: a PDF scheme that left variable or builtin null would print both runs plain.
        val php = bindPdf(node("php", "<?php\nfunction hi(\$name) {\n    return \$name;\n}"))
        val ini = bindPdf(node("ini", "[server]\nname = \"hello\""))
        val expected = mapOf(
            "php '\$name'" to Pair(requireNotNull(LightCodeSyntax.variable).toArgb(), colourAt(php, "\$name")),
            "ini 'server'" to Pair(requireNotNull(LightCodeSyntax.builtin).toArgb(), colourAt(ini, "server")),
        )
        // Both probes are read before either is judged, so a failure names every one that is wrong.
        val wrong = expected.filterValues { (want, got) -> want != got }
            .map { (probe, colours) -> "$probe: want ${colours.first.toUInt().toString(16)}, got ${colours.second}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `every alias of a language selects its grammar`() {
        val aliases = mapOf(
            TextMateCodeHighlighter.R to listOf("r", "rscript"),
            TextMateCodeHighlighter.PHP to listOf("php"),
            TextMateCodeHighlighter.INI to listOf("ini", "cfg", "dosini"),
            TextMateCodeHighlighter.LUA to listOf("lua"),
            TextMateCodeHighlighter.PERL to listOf("perl", "pl"),
            TextMateCodeHighlighter.JSONC to listOf("jsonc"),
        )
        for ((scope, fences) in aliases) {
            for (fence in fences) assertEquals(fence, scope, TextMateCodeHighlighter.scopeForFence(fence))
        }
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1000
    }
}
