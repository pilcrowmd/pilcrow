// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.markdown.ReaderWords
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.model.SearchMatches
import com.pilcrowmd.domain.usecase.legacy.LegacySearchMarkdownUseCase
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.buildPilcrowMarkwon
import com.pilcrowmd.rendering.isReaderFormula
import com.pilcrowmd.rendering.searchableMatchOffsets
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.screenshot.MarkdownSampleProvider
import com.pilcrowmd.testing.readerModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/**
 * S8-A94 (large-documents spec, PR1): search now walks the reader's own blocks instead of parsing the
 * document again. Its results must be the ones the old search gave: the same matches, in the same
 * order, at the same raw positions, adapter positions and per-block ordinals.
 *
 * The old search is [LegacySearchMarkdownUseCase], the class as it was on main (c206b15e), frozen.
 * The new one gets the blocks of the reader model production prepares (`readerModelFor`: the reader's
 * own Markwon parse) and production's formula test and emoji table. The corpus is every golden sample,
 * every test pack in `docs/test-packs`, the first megabyte of the repo's DECISIONS.md, and generated
 * documents of the three S8-A93 shapes plus a non-ASCII one; the queries are each document's commonest
 * words, phrases taken from it, and single characters that Markdown syntax is made of.
 *
 * ONE difference is on purpose, and it is where the old search was wrong: a task-list item's `[ ]` /
 * `[x]` marker. The reader's parse turns it into a drawn checkbox (Markwon's TaskListPlugin), so the
 * marker is not text on screen; the old search's parse kept it as text and counted it, a phantom match
 * that also pushed the item's later ordinals one off the highlighter's. So the old matches that touch a
 * marker are taken out, and the item's ordinals counted again, before the two are compared
 * ([withoutTaskMarkers]); the test of painted text below shows the reader paints none. Anything else fails.
 */
@RunWith(RobolectricTestRunner::class)
class SearchReaderBlocksDifferentialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val emoji = EmojiShortcodes(AssetEmojiTable({ context.assets.open(AssetEmojiTable.ASSET_PATH) }, { true }))
    private val renderer = MarkwonRenderer(context, ReaderWords.ENGLISH, emoji = emoji)
    private val legacy = LegacySearchMarkdownUseCase(ParseMarkdownHeadingsUseCase(), ReaderWords.ENGLISH, emoji)
    private val search = SearchMarkdownUseCase(ReaderWords.ENGLISH, emoji, isFormula = ::isReaderFormula)

    @Test
    fun `search over the reader's blocks finds what the old search found, on the whole corpus`() {
        val problems = mutableListOf<String>()
        var compared = 0L
        var searches = 0
        var markerSearches = 0
        for ((name, markdown) in corpus()) {
            val model = renderer.readerModelFor(markdown)
            for (query in queriesFor(markdown)) {
                val old = legacy.findSearchMatches(markdown, query)
                val new = when (model) {
                    is ReaderModel.Markdown -> search.findSearchMatches(model.content, model.blocks, query)
                    is ReaderModel.Plain -> search.findPlainSearchMatches(model.content, model.chunkLiterals, query)
                }
                searches++
                compared += new.size
                if (old.size != withoutTaskMarkers(markdown, old).size) markerSearches++
                diff(name, query, markdown, old, new)?.let { problems += it }
            }
        }
        println(
            "S8-A94 differential: ${corpus().size} documents, $searches searches, $compared matches compared; " +
                "$markerSearches searches had old matches on a task marker",
        )
        val shown = problems.take(MAX_SHOWN).joinToString("\n")
        assertTrue("${problems.size} search(es) differ:\n$shown", problems.isEmpty())
    }

    /**
     * The reader's tree has nodes the old search's parse never made: a formula and a painted emoji are
     * each ONE node there. These cases are where getting them wrong would show: text either side of a
     * formula must not join into one word, a word repeated inside a formula's source must not pull the
     * next match's position into it, and an emoji is its picture, anchored on its `:name:`.
     */
    @Test
    fun `formulas and emoji in the reader's tree are searched as the old search modelled them`() {
        val problems = mutableListOf<String>()
        for ((index, case) in TRAPS.withIndex()) {
            val (markdown, queries) = case
            val model = renderer.readerModelFor(markdown) as ReaderModel.Markdown
            for (query in queries) {
                val old = legacy.findSearchMatches(markdown, query)
                assertTrue("trap:$index \"$query\" is found by the old search", old.isNotEmpty() || query in NOT_FOUND)
                diff("trap:$index", query, markdown, old, search.findSearchMatches(markdown, model.blocks, query))
                    ?.let { problems += it }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the corpus is the one described`() {
        val names = corpus().map { it.first }
        assertTrue("golden samples are in it", names.count { it.startsWith("sample:") } > 25)
        assertTrue("test packs are in it", names.count { it.startsWith("pack:") } >= 16)
        assertTrue("the 1.9 MB test pack is in it", names.contains("pack:pilcrow-test-big-file.md"))
        assertEquals(4, names.count { it.startsWith("generated:") })
        val repoEntries = if (File("../DECISIONS.md").isFile) 1 else 0
        assertEquals(
            "DECISIONS.md is in it where the checkout has it",
            repoEntries,
            names.count { it.startsWith("repo:") },
        )
    }

    @Test
    fun `the reader paints no task marker, and search counts none`() {
        val markdown = "- [x] Done item\n- [ ] Pending item\n- [ ] Another pending"
        val model = renderer.readerModelFor(markdown) as ReaderModel.Markdown
        val painted = buildPilcrowMarkwon(context, emoji = emoji).render(model.blocks.single())
        for (query in listOf("x", "[", "]", " ] p")) {
            assertEquals(
                "\"$query\" in \"$painted\"",
                searchableMatchOffsets(painted, query).size,
                search.findSearchMatches(markdown, model.blocks, query).size,
            )
        }
        assertEquals("the old search counted the [x] marker", 1, legacy.findSearchMatches(markdown, "x").size)
        assertEquals(0, search.findSearchMatches(markdown, model.blocks, "x").size)
    }

    /** Null when [new] is [old] (less task markers) up to the cap; otherwise where they first part. */
    private fun diff(
        name: String,
        query: String,
        markdown: String,
        old: List<SearchMatch>,
        new: SearchMatches,
    ): String? {
        val kept = withoutTaskMarkers(markdown, old)
        val expected = kept.take(SearchMatches.MAX_MATCHES)
        val cappedRight = new.capped == (kept.size > SearchMatches.MAX_MATCHES)
        if (expected == new && cappedRight) return null
        val at = expected.indices.firstOrNull { it >= new.size || expected[it] != new[it] } ?: new.size
        return "$name / \"$query\": old ${old.size}, new ${new.size} (capped ${new.capped}); " +
            "first difference at $at: " +
            "old ${expected.getOrNull(at)}, new ${new.getOrNull(at)}"
    }

    private fun corpus(): List<Pair<String, String>> = cachedCorpus

    private val cachedCorpus: List<Pair<String, String>> by lazy {
        val samples = MarkdownSampleProvider().values
            .filter { it.renderMode == com.pilcrowmd.domain.model.RenderMode.MARKDOWN }
            .map { "sample:${it.name}" to it.markdown }
        val packs = File("../docs/test-packs").walkTopDown().filter { it.isFile && it.extension == "md" }
            .sortedBy { it.path }
            .map { "pack:${it.name}" to it.readText().replace("\r\n", "\n") }
        // DECISIONS.md is append-only, so its first megabyte is a fixed real-world document; a file
        // edited anywhere (README, CHANGELOG) would make the corpus drift under the test.
        // The public mirror does not ship DECISIONS.md, so there the corpus goes without it.
        val decisions = File("../DECISIONS.md")
        val repo = if (decisions.isFile) {
            listOf("repo:DECISIONS.md" to decisions.readText().take(REPO_CHARS))
        } else {
            emptyList()
        }
        (samples + packs + repo + generated()).toList()
    }

    /** The three S8-A93 shapes (`gen.py`), smaller, and a non-ASCII one: Polish, CJK and emoji. */
    private fun generated(): List<Pair<String, String>> {
        val random = Random(1)
        val latin = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor".split(" ")
        val other = "zażółć gęślą jaźń 東京 文書 検索 🎉 ünïcödé :tada: 数学 \$x^2\$".split(" ")
        fun words(n: Int, from: List<String> = latin) = List(n) { from[random.nextInt(from.size)] }.joinToString(" ")
        fun build(target: Int, piece: (Int) -> String) = buildString {
            var i = 0
            while (length < target) append(piece(i++))
        }
        val paragraphs = build(GENERATED_CHARS) { i ->
            (if (i % 20 == 0) "## Heading $i\n\n" else "") + words(8 + i % 23) + ".\n\n"
        }
        val longLines = build(GENERATED_CHARS) { words(1500 + random.nextInt(1500)) + ".\n\n" }
        val tablesAndCode = build(GENERATED_CHARS) { i ->
            if (i % 2 == 0) {
                val rows = List(40) { j -> "| ${words(2)} | ${words(3)} | ${words(1)} | $j | ${words(2)} |" }
                (listOf("| a | b | c | d | e |", "|---|---|---|---|---|") + rows).joinToString("\n") + "\n\n"
            } else {
                val lines = List(60) { j -> "val x$j = listOf($j, ${j * 2}) // ${words(4)}" }
                "```kotlin\n" + lines.joinToString("\n") + "\n```\n\n"
            }
        }
        val nonAscii = build(GENERATED_CHARS) { i ->
            (if (i % 15 == 0) "# Rozdział $i 章\n\n" else "") + words(6 + i % 17, other + latin) + ".\n\n"
        }
        return listOf(
            "generated:a-paragraphs" to paragraphs,
            "generated:b-long-lines" to longLines,
            "generated:c-tables-code" to tablesAndCode,
            "generated:non-ascii" to nonAscii,
        )
    }

    /** The commonest words, a few phrases taken from the text, and the characters Markdown is made of. */
    private fun queriesFor(markdown: String): List<String> {
        val words = Regex("""[\p{L}\p{N}]{3,}""").findAll(markdown).map { it.value.lowercase() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(COMMON_WORDS).map { it.key }
        val random = Random(markdown.length)
        val phrases = List(PHRASES) {
            if (markdown.length < PHRASE_LENGTH) {
                markdown
            } else {
                val at = random.nextInt(markdown.length - PHRASE_LENGTH)
                markdown.substring(at, at + PHRASE_LENGTH)
            }
        }.filter { it.isNotBlank() && '\n' !in it }
        return (words + phrases + SYNTAX).distinct()
    }

    /**
     * [old] without the matches that touch a task-list marker (`[ ]` or `[x]` and the space after it,
     * at the start of a list item), each block's ordinals counted again without them.
     */
    private fun withoutTaskMarkers(markdown: String, old: List<SearchMatch>): List<SearchMatch> {
        val markers = TASK_MARKER.findAll(markdown).map { it.groups[1]!!.range }.toList()
        if (markers.isEmpty()) return old
        val dropped = HashMap<Int, Int>()
        return old.mapNotNull { match ->
            val range = match.startIndex until match.startIndex + match.content.length
            if (markers.any { it.first <= range.last && range.first <= it.last }) {
                dropped.merge(match.adapterPosition, 1, Int::plus)
                null
            } else {
                match.copy(occurrenceInBlock = match.occurrenceInBlock - (dropped[match.adapterPosition] ?: 0))
            }
        }
    }

    private companion object {
        const val GENERATED_CHARS = 300_000
        const val REPO_CHARS = 1_000_000
        const val COMMON_WORDS = 12
        const val PHRASES = 4
        const val PHRASE_LENGTH = 9
        const val MAX_SHOWN = 40
        val SYNTAX = listOf("e", "a", "1", "$", "[", "]", ":", "|", "x", "`", "*", "=", "#", "-", "<", "&", " ", "th")

        // A query no search may find: the joins a formula must not make, and its placeholder.
        val NOT_FOUND = setOf("foobar", "\uFFFC", "x and", "tada")
        val TRAPS: List<Pair<String, List<String>>> = listOf(
            "foo\$x\$bar and foo\$\$y\$\$bar\n" to listOf("foobar", "foo", "bar", "\uFFFC"),
            "Take \$x and y\$ and y\n" to listOf("and y", "y", "x and"),
            "Then \$a b\$ b\n" to listOf("b", " b"),
            "One \$a\$\$b\$ b and \$c\$ \$d\$ d\n" to listOf("b", "d", " d", "and"),
            "Two \$a\$ x \$b\$ b, \$\$c\$\$ c x \$c\$\n" to listOf("x", "b", "c", " c x"),
            "Before\n\n\$\$\nx and y\n\$\$\n\nand y\n" to listOf("and y", "y"),
            "| \$p q\$ q | r |\n|---|---|\n| q \$q\$ q | \$r\$ r |\n" to listOf("q", "r", " q"),
            "Done :tada: and :tada: again :smile:\n" to listOf("\uD83C\uDF89", "a", "again", "tada"),
            "## Party :tada: time\n\n- item :rocket: go\n" to listOf("\uD83D\uDE80", "go", "time", "o"),
        )
        val TASK_MARKER = Regex("""(?m)^[ \t>]*(?:[-*+]|\d+[.)])[ \t]+(\[[ xX]\][ \t]+)""")
    }
}
