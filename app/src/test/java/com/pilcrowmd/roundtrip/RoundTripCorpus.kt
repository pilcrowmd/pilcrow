// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.roundtrip

/**
 * One document: the exact bytes placed on disk, and the exact bytes a load-then-save must leave there.
 * [expected] equals [bytes] for everything except the documented mixed-ending normalisation.
 */
internal class CorpusCase(val name: String, val bytes: ByteArray, val expected: ByteArray = bytes)

/**
 * The round-trip corpus. Committed fixtures (two spec texts, six READMEs) live in
 * `src/test/resources/roundtrip/` with their sources and licences in the README there; everything else
 * is generated here, deterministically, so the committed surface stays small.
 */
internal object RoundTripCorpus {

    private const val RESOURCE_ROOT = "roundtrip/"
    private val REAL_WORLD_RESOURCES = listOf(
        "commonmark-spec-0.31.2.txt",
        "gfm-spec-0.29.txt",
        "readmes/bat.md",
        "readmes/fzf.md",
        "readmes/hyperfine.md",
        "readmes/lazygit.md",
        "readmes/pandoc.md",
        "readmes/ripgrep.md",
    )

    // The spec texts fence each example with exactly 32 backticks; `→` stands for a tab. This mirrors
    // the reference extractor (spec_tests.py in both spec repositories).
    private val FENCE = "`".repeat(32)
    private const val EXAMPLE_OPEN = " example"

    val commonMarkExamples: List<CorpusCase> by lazy {
        specExamples("commonmark-spec-0.31.2.txt", "commonmark")
    }

    val gfmExamples: List<CorpusCase> by lazy {
        specExamples("gfm-spec-0.29.txt", "gfm")
    }

    val realWorld: List<CorpusCase> by lazy {
        REAL_WORLD_RESOURCES.map { path ->
            CorpusCase(path.substringAfterLast('/'), resource(path))
        }
    }

    /**
     * Every real-world document re-encoded six ways. The committed files are all LF, UTF-8, no BOM,
     * with a trailing newline, so each variant differs from its source in exactly the named respect.
     */
    val lineEndingVariants: List<CorpusCase> by lazy {
        realWorld.flatMap { doc ->
            val lf = doc.bytes.toString(Charsets.UTF_8)
            val crlf = lf.replace("\n", "\r\n")
            listOf(
                variant(doc, "crlf", crlf.utf8()),
                variant(doc, "lone-cr", lf.replace('\n', '\r').utf8()),
                variant(doc, "bom-lf", BOM + lf.utf8()),
                variant(doc, "bom-crlf", BOM + crlf.utf8()),
                variant(doc, "lf-no-final-newline", lf.trimEnd('\n').utf8()),
                variant(doc, "crlf-no-final-newline", crlf.removeSuffix("\r\n").utf8()),
            )
        } + listOf(
            synthetic("empty", ""),
            synthetic("single-lf", "\n"),
            synthetic("single-crlf", "\r\n"),
            synthetic("single-cr", "\r"),
            synthetic("blank-crlf-lines", "\r\n\r\n\r\n"),
            synthetic("bom-only", "\uFEFF"),
            synthetic("no-newline-at-all", "# Heading with no newline"),
            synthetic(
                "trailing-whitespace-and-tabs",
                "Hard break two spaces  \nTab\tinside\t\n\t\tindented code\n   \n\t\n",
            ),
            synthetic(
                "lone-cr-inside-lf-lines",
                "one\rtwo\nthree\r\rfour\n",
            ),
            synthetic(
                "lone-cr-inside-crlf-lines",
                "one\rtwo\r\nthree\r\n\r\nfour\r\n",
            ),
        )
    }

    val unicode: List<CorpusCase> by lazy { UnicodeSamples.all.map { (name, text) -> synthetic(name, text) } }

    val tables: List<CorpusCase> by lazy {
        listOf(
            synthetic("table-wide-300-columns", table(columns = 300, rows = 40)),
            synthetic("table-huge-4000-rows", table(columns = 12, rows = 4000)),
            synthetic("table-crlf-wide", table(columns = 120, rows = 200).replace("\n", "\r\n")),
            synthetic("table-unicode-cells", unicodeTable()),
            synthetic(
                "table-escaped-pipes-and-alignment",
                "| left | centre | right | none |\n|:-----|:------:|------:|------|\n" +
                    (1..200).joinToString("") { "| a\\|b $it | `x|y` | **$it** | <br> |\n" },
            ),
            synthetic(
                "table-ragged-rows-no-final-newline",
                "| a | b | c |\n| - | - | - |\n| 1 |\n| 1 | 2 | 3 | 4 | 5 |\n|||\n| only",
            ),
        )
    }

    /**
     * ACCEPTED LIMITATION, asserted rather than excluded: a file with mixed endings is written back
     * with every line ending converted to the dominant style (MarkdownViewModel.detectLineEnding KDoc).
     * [expected] is computed by [normaliseToDominant], an independent statement of that contract.
     */
    val mixedEndings: List<CorpusCase> by lazy {
        val ripgrep = realWorld.first { it.name == "ripgrep.md" }.bytes.toString(Charsets.UTF_8)
        val bat = realWorld.first { it.name == "bat.md" }.bytes.toString(Charsets.UTF_8)
        listOf(
            "mixed-lf-dominant-small" to "L1\nL2\r\nL3\nL4\n",
            "mixed-crlf-dominant-small" to "L1\r\nL2\nL3\r\nL4\r\n",
            "mixed-tie-goes-to-lf" to "L1\r\nL2\nL3\r\nL4\n",
            "mixed-with-lone-cr-lf-dominant" to "a\rb\nc\r\nd\ne\n",
            "mixed-with-lone-cr-crlf-dominant" to "a\rb\r\nc\nd\r\ne\r\n",
            "mixed-bom-crlf-dominant" to "\uFEFF# T\r\n\r\nbody\nmore\r\n",
            "mixed-ripgrep-every-7th-crlf" to everyNthLine(ripgrep, 7, "\r\n", "\n"),
            "mixed-bat-every-5th-lf" to everyNthLine(bat, 5, "\n", "\r\n"),
        ).map { (name, text) ->
            CorpusCase("$name.md", text.utf8(), normaliseToDominant(text).utf8())
        }
    }

    /** Every document expected to round-trip, in the order the suite runs them. */
    val passing: List<CorpusCase> by lazy {
        commonMarkExamples + gfmExamples + realWorld + lineEndingVariants + unicode + tables + mixedEndings
    }

    /**
     * The KDoc contract for a mixed file: count CRLF against bare LF, CRLF wins only a strict
     * majority, then every line ending (CRLF or bare LF) becomes the winner. A lone CR is not a line
     * ending and is left alone.
     */
    fun normaliseToDominant(text: String): String {
        var crlf = 0
        var lf = 0
        text.forEachIndexed { i, c -> if (c == '\n') if (i > 0 && text[i - 1] == '\r') crlf++ else lf++ }
        val ending = if (crlf > lf) "\r\n" else "\n"
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    out.append(ending)
                    i += 2
                }
                text[i] == '\n' -> {
                    out.append(ending)
                    i++
                }
                else -> out.append(text[i++])
            }
        }
        return out.toString()
    }

    private fun specExamples(resource: String, prefix: String): List<CorpusCase> {
        val lines = resource(resource).toString(Charsets.UTF_8).split("\n")
        val cases = mutableListOf<CorpusCase>()
        var inMarkdown = false
        var inExample = false
        val markdown = StringBuilder()
        for (line in lines) {
            when {
                !inExample && line.startsWith(FENCE + EXAMPLE_OPEN) -> {
                    inExample = true
                    inMarkdown = true
                    markdown.clear()
                }
                inExample && line == FENCE -> {
                    inExample = false
                    val number = (cases.size + 1).toString().padStart(3, '0')
                    val text = markdown.toString().replace('→', '\t')
                    cases += CorpusCase("$prefix-example-$number.md", text.utf8())
                }
                inMarkdown && line == "." -> inMarkdown = false
                inMarkdown -> markdown.append(line).append('\n')
            }
        }
        return cases
    }

    private fun resource(path: String): ByteArray {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(RESOURCE_ROOT + path)) {
            "missing corpus resource $RESOURCE_ROOT$path"
        }
        return stream.use { it.readBytes() }
    }

    private fun variant(doc: CorpusCase, suffix: String, bytes: ByteArray) =
        CorpusCase("${doc.name.substringBeforeLast('.')}-$suffix.md", bytes)

    private fun synthetic(name: String, text: String) = CorpusCase("$name.md", text.utf8())

    private fun everyNthLine(text: String, n: Int, special: String, usual: String): String =
        text.split("\n").mapIndexed { i, line -> if (i % n == n - 1) line + special else line + usual }
            .joinToString("")

    private fun table(columns: Int, rows: Int): String = buildString {
        append((1..columns).joinToString(" | ", "| ", " |\n") { "Column $it" })
        append((1..columns).joinToString(" | ", "| ", " |\n") { if (it % 3 == 0) ":-:" else "---" })
        for (r in 1..rows) append((1..columns).joinToString(" | ", "| ", " |\n") { c -> "r${r}c$c ${(r * c) % 97}" })
    }

    private fun unicodeTable(): String = buildString {
        append("| Script | Sample | Notes |\n|---|---|---|\n")
        repeat(50) { i ->
            UnicodeSamples.cellSamples.forEach { (script, sample) -> append("| $script $i | $sample | é ñ 中 |\n") }
        }
    }

    private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
