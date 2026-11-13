// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.repository.DocumentStats
import com.pilcrowmd.repository.StatsAnswer
import com.pilcrowmd.viewmodel.OpenFileChangeCheck.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S8-A90 (S8-A46 before it): the check itself, on virtual time, with a provider the test can hold. */
@RunWith(RobolectricTestRunner::class)
class OpenFileChangeCheckTest {

    private val uri = Uri.parse("content://p/tree/f/document/n")

    /** The provider's values; null = it says the file is gone. */
    @Volatile private var current: DocumentStats? = DocumentStats(1_000L, 4L)

    /** The provider gives no answer at all (offline), whatever [current] is. */
    @Volatile private var offline = false
    private var asked = 0

    /** The next query parks here, and reads [current] only once released. */
    private var gate: CompletableDeferred<Unit>? = null

    private val check = OpenFileChangeCheck(
        answer = {
            asked++
            gate?.also { gate = null }?.await()
            val now = current
            when {
                offline -> StatsAnswer.Unknown
                now == null -> StatsAnswer.Gone
                else -> StatsAnswer.Known(now)
            }
        },
        intervalMs = 100L,
    )

    private fun TestScope.steps(n: Int) {
        testScheduler.advanceTimeBy(100L * n)
        runCurrent()
    }

    @Test
    fun `nothing moves, so it goes on asking with no time limit`() = runTest {
        val watch = async { check.watch(uri) }
        steps(1_000) // far past the old 30 s window
        assertFalse("still watching", watch.isCompleted)
        assertEquals("the baseline and one query a step", 1_001, asked)
        watch.cancel()
    }

    @Test
    fun `a provider that cannot say at the open is not checked`() = runTest {
        current = null
        assertNull(check.watch(uri))
        assertEquals(1, asked)
    }

    @Test
    fun `a change after an hour is still seen`() = runTest {
        val watch = async { check.watch(uri) }
        steps(36_000)
        current = DocumentStats(2_000L, 30L)
        assertEquals(Event.CHANGED, watch.await())
    }

    @Test
    fun `a save that lands while a query runs is never taken for a change`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent() // the baseline
        val held = CompletableDeferred<Unit>().also { gate = it }
        steps(1) // the first check is waiting on the provider
        assertEquals(2, asked)

        check.saving {
            current = DocumentStats(1_000L, 12L) // Drive: the new size at once, the old date
            Result.success(Unit)
        }
        held.complete(Unit) // the held query answers with the saved values
        current = DocumentStats(2_000L, 12L) // later: Drive's own date for the save
        steps(20)

        assertFalse("our save, then its date: no change", watch.isCompleted)
        watch.cancel()
    }

    @Test
    fun `a change after the app's save is still a change`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        check.saving {
            current = DocumentStats(1_000L, 12L)
            Result.success(Unit)
        }
        testScheduler.advanceTimeBy(250L)
        runCurrent() // the save's values are the baseline now
        current = DocumentStats(2_000L, 30L) // another device, another size

        assertEquals(Event.CHANGED, watch.await())
    }

    @Test
    fun `an own save settles once, so a second same-size date move is a change`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        check.saving {
            current = DocumentStats(1_000L, 12L)
            Result.success(Unit)
        }
        testScheduler.advanceTimeBy(150L)
        runCurrent() // the save's values are the baseline now
        current = DocumentStats(2_000L, 12L) // Drive's own date for the save: settled
        steps(1)
        current = DocumentStats(3_000L, 12L) // another device, same size

        assertEquals(Event.CHANGED, watch.await())
    }

    @Test
    fun `a size read that is not the size stated is a change at once`() = runTest {
        assertEquals(Event.CHANGED, check.watch(uri, readSize = 3L))
        assertEquals(1, asked)
    }

    @Test
    fun `a size read that is the size stated is checked as usual`() = runTest {
        val watch = async { check.watch(uri, readSize = 4L) }
        steps(10)
        assertFalse(watch.isCompleted)
        assertEquals(11, asked)
        watch.cancel()
    }

    @Test
    fun `a file the provider says is gone, twice in a row, is gone`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        current = null
        assertEquals(Event.GONE, watch.await())
        assertEquals("the baseline and two gone answers", 3, asked)
    }

    @Test
    fun `a provider that does not answer is never a delete, and is asked again every step`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        offline = true // a cloud provider with no network
        steps(600) // a minute of steps at 100 ms, far past two of them
        assertFalse("no event while it does not answer", watch.isCompleted)
        assertEquals("the baseline and one question a step", 601, asked)

        offline = false
        current = DocumentStats(2_000L, 30L) // back online, with a change made meanwhile
        assertEquals(Event.CHANGED, watch.await())
    }

    @Test
    fun `a row of no date and size 0 for a file that had a date is gone`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        current = DocumentStats(null, 0L) // Downloads' raw: document after a delete (measured)
        assertEquals(Event.GONE, watch.await())
        assertEquals("the baseline and two such rows", 3, asked)
    }

    @Test
    fun `a file that never had a date is not gone at size 0`() = runTest {
        current = DocumentStats(null, 4L)
        val watch = async { check.watch(uri) }
        runCurrent()
        current = DocumentStats(null, 0L) // emptied, by a provider that states no dates at all
        assertEquals(Event.CHANGED, watch.await())
    }

    @Test
    fun `an empty row says nothing either way, so it is asked again`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        current = DocumentStats(null, null)
        steps(20)
        assertFalse(watch.isCompleted)
        watch.cancel()
    }

    @Test
    fun `one gone answer is not a delete`() = runTest {
        val watch = async { check.watch(uri) }
        runCurrent()
        current = null
        steps(1)
        current = DocumentStats(1_000L, 4L)
        steps(10)
        assertFalse(watch.isCompleted)
        watch.cancel()
    }

    @Test
    fun `back from the background it asks at once, against the values from before`() = runTest {
        val first = async { check.watch(uri) }
        steps(3)
        first.cancel() // the app went to the background
        current = DocumentStats(2_000L, 30L) // changed while away
        val askedBefore = asked

        val resumed = async { check.watch(uri, resume = true) }
        runCurrent() // no time passes

        assertTrue("answered before any wait", resumed.isCompleted)
        assertEquals(Event.CHANGED, resumed.await())
        assertEquals("one query, with no wait", askedBefore + 1, asked)
    }

    @Test
    fun `a resume with nothing kept starts afresh from the values now`() = runTest {
        current = DocumentStats(2_000L, 30L)
        assertEquals(Event.CHANGED, check.watch(uri, readSize = 4L))

        val resumed = async { check.watch(uri, resume = true) } // the event ended the last watch
        steps(5)
        assertFalse("the values now are the baseline: no change", resumed.isCompleted)
        current = DocumentStats(3_000L, 31L)
        assertEquals(Event.CHANGED, resumed.await())
    }

    @Test
    fun `a provider that states neither date nor size is not checked`() = runTest {
        current = DocumentStats(null, null)
        assertNull(check.watch(uri))
        assertEquals(1, asked)
    }
}
