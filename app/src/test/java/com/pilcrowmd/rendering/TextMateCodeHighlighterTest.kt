// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M-136 — the TextMate highlighter on its own: fence names, the per-block budget, embeds that do not
 * ship, and the run cache. Robolectric runs the same joni regex engine as the device.
 */
@RunWith(RobolectricTestRunner::class)
class TextMateCodeHighlighterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The block's time budget reads [nanoTime]. It defaults to a clock that never moves, so the time
     * budget can never be what decides a test about the line or line-length limit, however slow the
     * machine (CI hit the real 1 s budget before line 2,000). Only the time-budget test moves it.
     */
    private fun highlighter(nanoTime: () -> Long = { 0L }) = TextMateCodeHighlighter(context.assets::open, nanoTime)

    /** The role of the run covering the first character of [probe] in [code], or null if none does. */
    private fun roleAt(runs: List<CodeRun>, code: String, probe: String, from: Int = 0): CodeRole? {
        val at = code.indexOf(probe, from)
        check(at >= 0) { "probe '$probe' not in the code" }
        return runs.firstOrNull { at >= it.start && at < it.end }?.role
    }

    @Test
    fun `every chosen fence name selects its grammar`() {
        val expected = mapOf(
            "sh" to TextMateCodeHighlighter.SHELL,
            "bash" to TextMateCodeHighlighter.SHELL,
            "shell" to TextMateCodeHighlighter.SHELL,
            "zsh" to TextMateCodeHighlighter.SHELL,
            "ts" to TextMateCodeHighlighter.TYPESCRIPT,
            "typescript" to TextMateCodeHighlighter.TYPESCRIPT,
            "rs" to TextMateCodeHighlighter.RUST,
            "rust" to TextMateCodeHighlighter.RUST,
            "rb" to TextMateCodeHighlighter.RUBY,
            "ruby" to TextMateCodeHighlighter.RUBY,
        )
        for ((fence, scope) in expected) {
            assertEquals(fence, scope, TextMateCodeHighlighter.scopeForFence(fence))
        }
        val withAttributes = TextMateCodeHighlighter.scopeForFence(" BASH title=x")
        assertEquals("case and trailing attributes", TextMateCodeHighlighter.SHELL, withAttributes)
    }

    @Test
    fun `console, tsx and the Prism4j languages are not taken`() {
        for (fence in listOf("console", "tsx", "javascript", "kotlin", "yaml", "mermaid", "", null)) {
            assertNull("$fence", TextMateCodeHighlighter.scopeForFence(fence))
        }
    }

    @Test
    fun `a block past the line limit colours its head and leaves its tail plain`() {
        val lines = TextMateCodeHighlighter.MAX_LINES + 100
        val code = (1..lines).joinToString("\n") { "echo \"line $it\"" }
        val runs = highlighter().tokenize(TextMateCodeHighlighter.SHELL, code)!!
        val lastLine = TextMateCodeHighlighter.MAX_LINES
        assertEquals("last line inside the limit", CodeRole.FUNCTION, roleAt(runs, code, "echo \"line $lastLine\""))
        val firstPlain = code.indexOf("echo \"line ${lastLine + 1}\"")
        assertTrue("nothing past the limit is coloured", runs.none { it.end > firstPlain })
    }

    @Test
    fun `a line over the character limit stays plain and the next line is coloured`() {
        val long = "echo \"" + "x".repeat(TextMateCodeHighlighter.MAX_LINE_CHARS) + "\""
        val code = "echo one\n$long\necho three"
        val runs = highlighter().tokenize(TextMateCodeHighlighter.SHELL, code)!!
        val longStart = code.indexOf(long)
        val longEnd = longStart + long.length
        assertTrue("the long line has no colour", runs.none { it.start < longEnd && it.end > longStart })
        assertEquals("the line after it", CodeRole.FUNCTION, roleAt(runs, code, "echo three"))
    }

    @Test
    fun `once the time budget is spent the rest of the block stays plain`() {
        // A clock that jumps past the budget after the first line: only line 1 is coloured. It counts
        // reads, not real time, so machine speed cannot move it; 3 short lines keep the line limits out.
        var calls = 0L
        val clock = { if (calls++ < 2) 0L else (TextMateCodeHighlighter.BLOCK_BUDGET_MS + 1) * 1_000_000L }
        val code = "echo one\necho two\necho three"
        val runs = highlighter(clock).tokenize(TextMateCodeHighlighter.SHELL, code)!!
        assertEquals(CodeRole.FUNCTION, roleAt(runs, code, "echo one"))
        assertTrue("lines after the budget are plain", runs.none { it.end > code.indexOf("echo two") })
    }

    @Test
    fun `a Ruby heredoc in a language that does not ship stays plain and does not throw`() {
        val code = "x = <<~SQL\n  SELECT name FROM users\nSQL\ny = <<~SHELL\n  echo hi\nSHELL\nputs \"done\""
        val runs = highlighter().tokenize(TextMateCodeHighlighter.RUBY, code)!!
        val sql = code.indexOf("SELECT")
        assertTrue("the SQL body is plain", runs.none { it.start <= sql && it.end > sql })
        // The embedded shell resolves to the shipped bash grammar.
        assertEquals("shell heredoc", CodeRole.FUNCTION, roleAt(runs, code, "echo hi"))
        assertEquals("ruby after both", CodeRole.STRING, roleAt(runs, code, "\"done\""))
    }

    @Test
    fun `a cached result is served only for the exact same text`() {
        val h = highlighter()
        // "Aa" and "BB" have the same String.hashCode, so these two blocks share a cache key.
        val first = "echo Aa"
        val second = "echo BB"
        check(first.hashCode() == second.hashCode() && first.length == second.length)
        val runs = h.tokenize(TextMateCodeHighlighter.SHELL, first)
        assertEquals(runs, h.cachedRuns(TextMateCodeHighlighter.SHELL, first))
        assertNull("a colliding block is a miss", h.cachedRuns(TextMateCodeHighlighter.SHELL, second))
    }

    @Test
    fun `the cache forgets its least recently used block past 64 entries`() {
        val h = highlighter()
        val blocks = (0..64).map { "echo $it" }
        blocks.forEach { h.tokenize(TextMateCodeHighlighter.SHELL, it) }
        assertNull("the oldest is evicted", h.cachedRuns(TextMateCodeHighlighter.SHELL, blocks.first()))
        assertNotNull("the newest is kept", h.cachedRuns(TextMateCodeHighlighter.SHELL, blocks.last()))
    }

    /**
     * While one block is being tokenised, the blocks queued behind it must wait as suspended
     * coroutines, not as pool threads parked on the tokenise lock.
     *
     * The first block is held inside the lock by its clock (the first thing tokenising reads). Three
     * more are then requested. A thread parked on the lock shows as BLOCKED inside
     * `TextMateCodeHighlighter.tokenize`; with the fix none ever starts, because the three wait on a
     * one-at-a-time dispatcher. Without it, three Default workers park within milliseconds, so the
     * test fails at once. Proving an absence needs a bounded wait: this one can only pass slowly,
     * never fail falsely.
     */
    @Test
    fun `blocks queued behind a tokenise wait without holding a thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        val clock = {
            if (first) {
                first = false
                entered.countDown()
                release.await(GATE_TIMEOUT_S, TimeUnit.SECONDS)
            }
            0L
        }
        val h = highlighter(clock)
        val scope = CoroutineScope(Job())
        try {
            val jobs = (0..3).map { i -> scope.async { h.tokenizeAsync(TextMateCodeHighlighter.SHELL, "echo $i") } }
            check(entered.await(GATE_TIMEOUT_S, TimeUnit.SECONDS)) { "the first block never started" }
            val parked = parkedOnTokenize(QUEUE_WAIT_MS)
            release.countDown()
            assertEquals("threads parked on the tokenise lock", emptyList<String>(), parked)
            val results = runBlocking { jobs.awaitAll() }
            assertTrue("every block is coloured once released", results.all { !it.isNullOrEmpty() })
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    /** Names of threads BLOCKED inside [TextMateCodeHighlighter.tokenize], polled for up to [waitMs]. */
    private fun parkedOnTokenize(waitMs: Long): List<String> {
        val deadline = System.nanoTime() + waitMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val parked = Thread.getAllStackTraces().filter { (thread, stack) ->
                thread.state == Thread.State.BLOCKED &&
                    stack.any { it.className == HIGHLIGHTER_CLASS && it.methodName == "tokenize" }
            }.keys.map { it.name }
            if (parked.isNotEmpty()) return parked
            Thread.sleep(POLL_MS)
        }
        return emptyList()
    }

    @Test
    fun `a cancelled tokenise returns null and caches nothing`() {
        val h = highlighter()
        assertNull(h.tokenize(TextMateCodeHighlighter.SHELL, "echo hi") { false })
        assertNull(h.cachedRuns(TextMateCodeHighlighter.SHELL, "echo hi"))
    }

    private companion object {
        const val GATE_TIMEOUT_S = 30L
        const val QUEUE_WAIT_MS = 1_000L
        const val POLL_MS = 10L
        val HIGHLIGHTER_CLASS: String = TextMateCodeHighlighter::class.java.name
    }
}
