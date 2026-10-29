// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.repository.DocumentStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S8-A46: the check itself, on virtual time, with a provider whose answer the test can hold. */
@RunWith(RobolectricTestRunner::class)
class OpenFileChangeCheckTest {

    private val uri = Uri.parse("content://p/tree/f/document/n")

    @Volatile private var current: DocumentStats? = DocumentStats(1_000L, 4L)
    private var asked = 0

    /** The next query parks here, and reads [current] only once released. */
    private var gate: CompletableDeferred<Unit>? = null

    private val check = OpenFileChangeCheck(
        stats = {
            asked++
            gate?.also { gate = null }?.await()
            current
        },
        intervalMs = 100L,
        windowMs = 1_000L,
    )

    @Test
    fun `the window ends with no change after one query a step`() = runTest {
        assertFalse(check.changed(uri))
        assertEquals("the baseline and ten checks", 11, asked)
    }

    @Test
    fun `a provider that cannot say at the start is not checked`() = runTest {
        current = null
        assertFalse(check.changed(uri))
        assertEquals(1, asked)
    }

    @Test
    fun `a save that lands while a query runs is never taken for a change`() = runTest {
        val result = async { check.changed(uri) }
        runCurrent() // the baseline
        val held = CompletableDeferred<Unit>().also { gate = it }
        testScheduler.advanceTimeBy(100L)
        runCurrent() // the first check is waiting on the provider
        assertEquals(2, asked)

        check.saving {
            current = DocumentStats(1_000L, 12L) // Drive: the new size at once, the old date
            Result.success(Unit)
        }
        held.complete(Unit) // the held query answers with the saved values
        current = DocumentStats(2_000L, 12L) // later: Drive's own date for the save

        assertFalse("our save, then its date: no change", result.await())
    }

    @Test
    fun `a change after the app's save is still a change`() = runTest {
        val result = async { check.changed(uri) }
        runCurrent()
        check.saving {
            current = DocumentStats(1_000L, 12L)
            Result.success(Unit)
        }
        testScheduler.advanceTimeBy(250L)
        runCurrent() // the save's values are the baseline now
        current = DocumentStats(2_000L, 30L) // another device, another size

        assertTrue(result.await())
    }

    @Test
    fun `an own save settles once, so a second same-size date move is a change`() = runTest {
        val result = async { check.changed(uri) }
        runCurrent()
        check.saving {
            current = DocumentStats(1_000L, 12L)
            Result.success(Unit)
        }
        testScheduler.advanceTimeBy(150L)
        runCurrent() // the save's values are the baseline now
        current = DocumentStats(2_000L, 12L) // Drive's own date for the save: settled
        testScheduler.advanceTimeBy(100L)
        runCurrent()
        current = DocumentStats(3_000L, 12L) // another device, same size

        assertTrue(result.await())
    }

    @Test
    fun `a size read that is not the size stated is a change at once`() = runTest {
        assertTrue(check.changed(uri, readSize = 3L))
        assertEquals(1, asked)
    }

    @Test
    fun `a size read that is the size stated is checked as usual`() = runTest {
        assertFalse(check.changed(uri, readSize = 4L))
        assertEquals(11, asked)
    }
}
