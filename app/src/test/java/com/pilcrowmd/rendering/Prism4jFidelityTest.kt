// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.ui.theme.DarkColorScheme
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #17: a Prism4j block that Markwon does not colour at bind (a piece of a long block, or a block
 * over the cap in the PDF) is coloured off the TextView by [Prism4jCodeHighlighter]. It must look exactly
 * as it does when Markwon colours it at bind, or a long block would change colour at the 10,000 char line.
 *
 * Every sample is under the cap, so Markwon colours it: for each character, the colour Markwon's own render
 * gives it (the last [ForegroundColorSpan] over it, as the view draws it, or none) is compared with the
 * colour the highlighter's runs give it when painted the way the reader paints them (a role's colour; a
 * plain token takes the code text colour). "No span" and "the code text colour" are the same colour. A
 * diff's line tint is compared the same way, character by character.
 */
@RunWith(RobolectricTestRunner::class)
class Prism4jFidelityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val markwon = buildPilcrowMarkwon(context, DarkColorScheme)
    private val syntax = DarkColorScheme.codeSyntax
    private val textColor = DarkColorScheme.editorText.toArgb()

    private fun node(info: String, code: String) = FencedCodeBlock().apply {
        this.info = info
        literal = code
    }

    /** The colour of each char of [code] as Markwon renders it; the code text colour is [NONE]. */
    private fun markwonColours(info: String, code: String): Pair<IntArray, BooleanArray> {
        val text = markwon.render(node(info, code)) as Spanned
        val at = text.indexOf(code.trimEnd('\n'))
        check(at >= 0) { "$info: the rendered text does not hold the code" }
        val colours = IntArray(code.length) { NONE }
        val tinted = BooleanArray(code.length)
        for (i in code.trimEnd('\n').indices) {
            val last = text.getSpans(at + i, at + i + 1, ForegroundColorSpan::class.java).lastOrNull()
            colours[i] = last?.foregroundColor.normalised()
            tinted[i] = text.getSpans(at + i, at + i + 1, DiffLineBackgroundSpan::class.java).isNotEmpty()
        }
        return colours to tinted
    }

    /** The colour of each char of [code] when the runs are painted in order, as the reader paints them. */
    private fun runColours(info: String, code: String): Pair<IntArray, BooleanArray> {
        val runs = Prism4jCodeHighlighter().tokenize(info, code)
        checkNotNull(runs) { "$info: tokenise answered null" }
        val colours = IntArray(code.length) { NONE }
        val tinted = BooleanArray(code.length)
        for (run in runs) {
            val colour = run.role.colorIn(syntax)?.toArgb() ?: textColor.takeIf { run.role == CodeRole.TEXT }
            val tint = run.role.lineBackgroundIn(syntax)
            for (i in run.start until run.end) {
                if (colour != null) colours[i] = colour.normalised()
                if (tint != null) tinted[i] = true
            }
        }
        return colours to tinted
    }

    private fun Int?.normalised(): Int = if (this == null || this == textColor) NONE else this

    private fun assertSameAsMarkwon(info: String, code: String) {
        check(code.length < MAX_HIGHLIGHT_CHARS) {
            "$info: the sample must be under the cap, or Markwon leaves it plain"
        }
        val (expected, expectedTint) = markwonColours(info, code)
        val (actual, actualTint) = runColours(info, code)
        assertTrue("$info: Markwon colours something, or this proves nothing", expected.any { it != NONE })

        val differing = expected.indices.filter { expected[it] != actual[it] }
        assertEquals(
            "$info: chars coloured differently from Markwon's, " +
                differing.take(MAX_REPORTED).joinToString { i ->
                    "'${code[i]}'@$i (…${code.substring(
                        maxOf(0, i - CONTEXT),
                        minOf(code.length, i + CONTEXT),
                    ).replace("\n", "⏎")}…) " +
                        "markwon=${expected[i].hex()} runs=${actual[i].hex()}"
                },
            emptyList<Int>(),
            differing,
        )
        val tintDiffering = expectedTint.indices.filter { expectedTint[it] != actualTint[it] }
        assertEquals(
            "$info: chars with a different line tint, from ${tintDiffering.firstOrNull()}",
            emptyList<Int>(),
            tintDiffering,
        )
    }

    private fun Int.hex() = if (this == NONE) "none" else "#%08X".format(this)

    @Test
    fun `json is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "json",
        """
        {
          "name": "pilcrow", "version": 1.5e3, "private": true, "nothing": null,
          "list": [1, -2, "three", { "deep": [false, 0.25] }],
          "text": "a \"quoted\" word and a \\ backslash"
        }
        """.trimIndent() + "\n",
    )

    @Test
    fun `kotlin is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "kotlin",
        """
        package demo

        import kotlinx.coroutines.flow.Flow

        /** Docs for the class. */
        @Suppress("UNUSED")
        class Reader<T : Any>(private val name: String, val count: Int = 3) : Base(), Marker {
            // a line comment
            fun <R> map(transform: (T) -> R): List<R> {
                val text = "n = ${'$'}count, ${'$'}{name.length} chars \n done"
                val raw = ${"\"\"\""}raw ${'$'}name${"\"\"\""}
                var total = 0x1F + 2L + 3.5f
                for (i in 0 until count) { if (i % 2 == 0 && total != 7) total += i else continue }
                return when (count) { 1 -> emptyList(); else -> listOf() }
            }
            companion object { const val LIMIT = 10_000 }
        }
        """.trimIndent() + "\n",
    )

    @Test
    fun `java is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "java",
        """
        package demo;

        import java.util.List;

        /** Javadoc. */
        @FunctionalInterface
        public class Reader<T extends Comparable<T>> implements Runnable {
            private static final int LIMIT = 10_000;
            /* a block comment */
            public <R> List<R> map(final T value, char c, double d) throws Exception {
                String s = "text \t with \"escape\"";
                long big = 0xFFL + 1_000L;
                if (value == null || c == 'x') { throw new IllegalStateException(s); }
                return null;
            }
        }
        """.trimIndent() + "\n",
    )

    @Test
    fun `python is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "python",
        """
        import os
        from typing import List

        @decorator(1, key="v")
        class Reader(Base):
            ${"\"\"\""}Docstring with 'quotes'.${"\"\"\""}

            def run(self, items: List[int], *args, **kwargs) -> bool:
                # a comment
                text = f"n = {len(items)} and {'x'!r}"
                raw = r'\d+\.\d*' + "tab\t" + b'bytes'
                total = 0x1F + 3.5e2 + 1_000j
                for i, item in enumerate(items):
                    if item is not None and i in range(3) or not total:
                        yield lambda x: x ** 2
                return True
        """.trimIndent() + "\n",
    )

    @Test
    fun `javascript is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "javascript",
        """
        import { readFile } from 'fs';

        /** Docs. */
        export default class Reader extends Base {
          static #count = 0;
          // a line comment
          async run(items, { a = 1, b } = {}) {
            const text = `n = ${'$'}{items.length} and ${'$'}{a}`;
            const pattern = /ab+c\/[a-z]*/gi;
            let total = 0xFF + 1.5e3 + 10n;
            for (const item of items) { if (item === undefined || !b) continue; total += item ?? 0; }
            return new Promise((resolve) => setTimeout(() => resolve(total), 100));
          }
        }
        """.trimIndent() + "\n",
    )

    @Test
    fun `css is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "css",
        """
        @import url("theme.css");
        @media (max-width: 600px) and (min-width: 20em) {
          .reader > p:first-child, #main a[href^="http"]::after {
            color: #ff8800 !important;
            margin: 0 auto 1.5em -2px;
            background: rgba(0, 0, 0, .5) url('x.png') no-repeat;
            font-family: "Source Serif 4", serif;
          }
        }
        /* a comment */
        :root { --accent: #abc; width: calc(100% - var(--accent)); }
        """.trimIndent() + "\n",
    )

    @Test
    fun `markdown is coloured as Markwon colours it`() = assertSameAsMarkwon(
        "markdown",
        """
        # A heading with `code`

        Some **bold** and *italic* and ~~struck~~ text with a [link](https://example.com "title")
        and a ![picture](a.png) and <https://auto.link>.

        > A quote
        > > nested

        - item one
        - item two
          1. numbered
        - [x] done

        ---

        ```kotlin
        val x = 1
        ```

        Setext heading
        ==============

        [ref]: https://example.com
        """.trimIndent() + "\n",
    )

    @Test
    fun `diff is coloured as Markwon colours it, line tints included`() = assertSameAsMarkwon(
        "diff",
        """
        diff --git a/Reader.kt b/Reader.kt
        index 83db48f..bf2a3c1 100644
        --- a/Reader.kt
        +++ b/Reader.kt
        @@ -10,7 +10,8 @@ class Reader {
             val kept = 1
        -    val removed = "old"
        +    val added = "new"
        +    val another = 2
             val alsoKept = 3
        @@ -40,3 +41,3 @@
        -}
        +}
        """.trimIndent() + "\n",
    )

    /**
     * The smallest cases of where nested tokens differ. Markwon sets a token's span BEFORE the spans of the
     * tokens inside it, so an inner token is drawn over its outer one, a plain inner token (punctuation,
     * an interpolation) in the code text colour; and a plain token inside a coloured one is a span too.
     */
    @Test
    fun `a plain token inside a coloured one is drawn plain, in a css at-rule`() =
        assertSameAsMarkwon("css", "@import url(\"a.css\");\n")

    @Test
    fun `a plain token inside a coloured one is drawn plain, in a kotlin string template`() =
        assertSameAsMarkwon("kotlin", "val s = \"a \$x b\"\n")

    @Test
    fun `a coloured token inside a plain one is drawn coloured, in a javascript template literal`() =
        assertSameAsMarkwon("javascript", "const t = `a \${b} c`;\n")

    @Test
    fun `a plain token inside a coloured one is drawn plain, in markdown emphasis and headings`() =
        assertSameAsMarkwon("markdown", "# Head\n\nSome **bold** text\n")

    @Test
    fun `the diff sample really has line tints, or comparing them proves nothing`() {
        val code = "-gone\n+added\n kept\n"
        val (_, tinted) = markwonColours("diff", code)
        assertTrue(tinted.any { it })
        assertTrue(!tinted[code.indexOf("kept")])
    }

    private companion object {
        const val NONE = 0
        const val MAX_REPORTED = 5
        const val CONTEXT = 8
    }
}
