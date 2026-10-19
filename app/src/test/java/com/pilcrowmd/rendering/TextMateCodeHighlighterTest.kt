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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M-136 — the TextMate highlighter on its own: fence names, embeds that do not ship, the run cache,
 * and that a block is coloured whole. Robolectric runs the same joni regex engine as the device.
 *
 * Issue #17 took the limits off: no line limit, no line-length limit and no time limit. The time limit
 * is gone by design, so there is no clock to move and no test of one; what still stops a tokenise is the
 * caller's cancel check, read between lines (`a cancelled tokenise ...`).
 */
@RunWith(RobolectricTestRunner::class)
class TextMateCodeHighlighterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun highlighter(openAsset: (String) -> java.io.InputStream = context.assets::open) =
        TextMateCodeHighlighter(openAsset)

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
    fun `a block of 2,100 lines is coloured on its last line`() {
        val lines = 2_100
        val code = (1..lines).joinToString("\n") { "echo \"line $it\"" }
        val runs = highlighter().tokenize(TextMateCodeHighlighter.SHELL, code)!!
        assertEquals("first line", CodeRole.FUNCTION, roleAt(runs, code, "echo \"line 1\""))
        assertEquals("a line past the old limit", CodeRole.FUNCTION, roleAt(runs, code, "echo \"line 2001\""))
        assertEquals("last line", CodeRole.FUNCTION, roleAt(runs, code, "echo \"line $lines\""))
    }

    @Test
    fun `a line over 5,000 chars is coloured, and so are the lines after it`() {
        val long = "echo \"" + "x".repeat(5_001) + "\""
        val code = "echo one\n$long\necho three"
        val runs = highlighter().tokenize(TextMateCodeHighlighter.SHELL, code)!!
        val longStart = code.indexOf(long)
        assertEquals("the long line's command", CodeRole.FUNCTION, roleAt(runs, code, long))
        assertEquals("the long line's string", CodeRole.STRING, roleAt(runs, code, "xxxx", longStart))
        assertEquals("the line after it", CodeRole.FUNCTION, roleAt(runs, code, "echo three"))
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
     * The first block is held inside the lock by the asset read that loads the grammar (the first
     * thing tokenising does). Three more are then requested. A thread parked on the lock shows as
     * BLOCKED inside `TextMateCodeHighlighter.tokenize`; with the fix none ever starts, because the
     * three wait on a one-at-a-time dispatcher. Without it, three Default workers park within
     * milliseconds, so the test fails at once. Proving an absence needs a bounded wait: this one can
     * only pass slowly, never fail falsely.
     */
    @Test
    fun `blocks queued behind a tokenise wait without holding a thread`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val h = highlighter { path ->
            if (first.getAndSet(false)) {
                entered.countDown()
                release.await(GATE_TIMEOUT_S, TimeUnit.SECONDS)
            }
            context.assets.open(path)
        }
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

    @Test
    fun `the cancel check between lines still stops a block part way`() {
        val h = highlighter()
        val code = (1..50).joinToString("\n") { "echo \"line $it\"" }
        var checks = 0
        assertNull(h.tokenize(TextMateCodeHighlighter.SHELL, code) { checks++ < 10 })
        assertTrue("it read the check once per line, not once", checks > 5 && checks < 50)
        assertNull("nothing half-done is cached", h.cachedRuns(TextMateCodeHighlighter.SHELL, code))
    }

    private companion object {
        const val GATE_TIMEOUT_S = 30L
        const val QUEUE_WAIT_MS = 1_000L
        const val POLL_MS = 10L
        val HIGHLIGHTER_CLASS: String = TextMateCodeHighlighter::class.java.name
    }
}
