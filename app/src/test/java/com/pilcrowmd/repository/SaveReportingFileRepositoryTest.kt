// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * M-162b (M4): the save-reporting wrapper changes nothing about a save (Safeguard 1): the inner
 * repository gets the same arguments, its result comes back unchanged, its exception
 * passes through as the same object, and only a successful save is reported.
 */
@RunWith(RobolectricTestRunner::class)
class SaveReportingFileRepositoryTest {

    private val inner: FileRepository = mockk()
    private val repo = SaveReportingFileRepository(inner)
    private val note = Uri.parse("content://docs/tree/A/document/index.md")
    private val content = "# Title\r\nbody\n"

    /** Runs [save] with a listener on the reports, and returns what was reported. */
    private fun reportedDuring(save: suspend () -> Unit): List<Uri> = runBlocking {
        val reported = mutableListOf<Uri>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) { repo.savedDocuments.toList(reported) }
        save()
        yield()
        listener.cancel()
        reported
    }

    @Test
    fun `S8-A52 - a save's start is reported before the inner save runs, even when it fails`() = runBlocking {
        val started = mutableListOf<Uri>()
        val listener = launch(start = CoroutineStart.UNDISPATCHED) { repo.saveStarts.toList(started) }
        var startedBeforeWrite: List<Uri>? = null
        coEvery { inner.saveFile(note, content) } coAnswers {
            yield()
            startedBeforeWrite = started.toList()
            Result.failure(IOException("disk full"))
        }

        repo.saveFile(note, content)
        listener.cancel()

        assertEquals(listOf(note), startedBeforeWrite)
    }

    @Test
    fun `a successful save returns the inner result unchanged and is reported`() {
        val ok = Result.success(Unit)
        coEvery { inner.saveFile(note, content) } returns ok
        var result: Result<Unit>? = null

        val reported = reportedDuring { result = repo.saveFile(note, content) }

        assertEquals(ok, result)
        assertEquals(listOf(note), reported)
        coVerify(exactly = 1) { inner.saveFile(note, content) }
    }

    @Test
    fun `a failed save returns the inner failure unchanged and is not reported`() {
        val failed = Result.failure<Unit>(IOException("disk full"))
        coEvery { inner.saveFile(note, content) } returns failed
        var result: Result<Unit>? = null

        val reported = reportedDuring { result = repo.saveFile(note, content) }

        assertEquals(failed, result)
        assertSame("the same exception object", failed.exceptionOrNull(), result?.exceptionOrNull())
        assertEquals(emptyList<Uri>(), reported)
    }

    @Test
    fun `an exception from the inner save passes through as it is and is not reported`() {
        val boom = IllegalStateException("provider died")
        coEvery { inner.saveFile(note, content) } throws boom

        val reported = reportedDuring {
            try {
                repo.saveFile(note, content)
                fail("the exception must reach the caller")
            } catch (e: IllegalStateException) {
                assertSame(boom, e)
            }
        }

        assertEquals(emptyList<Uri>(), reported)
    }

    @Test
    fun `a stranded slot rescued to a new file is passed on and not reported`() {
        val ok = Result.success(Unit)
        coEvery { inner.saveStrandedSlotToTarget("slot", note) } returns ok
        var result: Result<Unit>? = null

        val reported = reportedDuring { result = repo.saveStrandedSlotToTarget("slot", note) }

        assertEquals(ok, result)
        assertEquals(emptyList<Uri>(), reported)
    }
}
