// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import androidx.test.core.app.ApplicationProvider
import io.github.rosemoe.sora.text.ContentLine
import io.github.rosemoe.sora.text.breaker.WordBreakerProgram
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pins the test JVM's half of `ShadowSoraCharSequenceIterator`: a word-wrap pass in Sora must
 * finish here, as it does on a phone.
 *
 * `MarkdownEditor`'s factory turns word wrap on before the editor has been measured, so the first
 * pass wraps at a negative width and breaks after every character. Each break asks the line
 * `BreakIterator` for the boundary before character 1. On a phone that is ICU, which returns. On
 * the JVM it is the JDK's iterator, which scans backwards until `previous()` returns `DONE` — and
 * Sora's `CharSequenceIterator` never does — so the call never returned and the pass held a thread of
 * Sora's shared, process-wide layout pool for the rest of the run. Two such passes stall the pool;
 * every later editor queues behind them, and on a 2-core runner the 129th queued task is rejected
 * (`RejectedExecutionException` in whichever editor test came next — `StaleEditorWriteTest` on #235).
 */
@RunWith(RobolectricTestRunner::class)
class EditorWrapPassTerminatesTest {

    /** The single call the hang lived in: a break after the first character of a line. */
    @Test
    fun theBreakSearchForABreakAfterTheFirstCharacterReturns() {
        val end = withinSeconds { WordBreakerProgram(ContentLine("# Title")).getOptimizedBreakPoint(0, 1) }
        assertEquals(1, end)
    }

    /** The consequence the suite saw: an unmeasured editor's wrap pass must leave the shared pool idle. */
    @Test
    fun aWrapPassOnAnUnmeasuredEditorLeavesTheLayoutPoolIdle() {
        val editor = CodeEditor(ApplicationProvider.getApplicationContext())
        editor.setText("# Title\n\nText\n")
        assertEquals("the fixture must wrap at a negative width", 0, editor.width)
        editor.setWordwrap(true, true, true)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && !layoutPool.isIdle()) Thread.sleep(POLL_MS)
        assertTrue(
            "Sora's layout pool is still busy after $TIMEOUT_SECONDS s: active=${layoutPool.activeCount}, " +
                "queued=${layoutPool.queue.size}",
            layoutPool.isIdle(),
        )
        editor.release()
    }

    private fun ThreadPoolExecutor.isIdle() = activeCount == 0 && queue.isEmpty()

    /** Sora's static layout executor, which every editor in the process shares. */
    private val layoutPool: ThreadPoolExecutor
        get() = Class.forName("io.github.rosemoe.sora.widget.layout.AbstractLayout")
            .getDeclaredField("executor")
            .apply { isAccessible = true }
            .get(null) as ThreadPoolExecutor

    /** Runs [block] on a daemon thread, so a call that never returns fails the test instead of holding it. */
    private fun <T> withinSeconds(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            return executor.submit(Callable { block() }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val POLL_MS = 20L
    }
}
