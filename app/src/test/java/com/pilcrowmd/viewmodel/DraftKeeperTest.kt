// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.model.EditorSupport
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.repository.ByteFingerprint
import com.pilcrowmd.repository.Draft
import com.pilcrowmd.repository.DraftInfo
import com.pilcrowmd.repository.DraftRepository
import com.pilcrowmd.storage.ScrollAnchor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

/**
 * When the keeper writes and removes drafts, and which stored drafts it offers.
 * Virtual time: every `delay` in the keeper runs on the test scheduler, and the
 * draft's own time stamp reads the same clock, so "1500 ms" means exactly that.
 *
 * Each test states, in its first lines, the one property it exists for, and its fixture is built so
 * that nothing cheaper can answer first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DraftKeeperTest {

    private val file = Uri.parse("content://test/notes.md")
    private val other = Uri.parse("content://test/other.md")

    /** A repository in memory that remembers every call, in the order it was made. */
    private class FakeDrafts : DraftRepository {
        val stored = LinkedHashMap<Uri, Draft>()
        val writes = mutableListOf<Draft>()
        val detached = mutableListOf<Draft>()
        val deletes = mutableListOf<Uri>()
        val trims = mutableListOf<Pair<Int, Uri>>()
        var failWrites = false

        /** What [trim] asks `isProtected` about, as the real store asks about the drafts it would drop. */
        var trimCandidates = emptyList<DraftInfo>()

        override suspend fun write(draft: Draft): Result<Unit> {
            writes += draft
            if (failWrites) return Result.failure(java.io.IOException("disk full (test)"))
            stored[draft.uri] = draft
            return Result.success(Unit)
        }

        override fun writeDetached(draft: Draft) {
            detached += draft
            stored[draft.uri] = draft
        }

        override suspend fun read(uri: Uri): Draft? = stored[uri]

        override suspend fun delete(uri: Uri) {
            deletes += uri
            stored.remove(uri)
        }

        override suspend fun trim(limit: Int, keep: Uri, isProtected: suspend (DraftInfo) -> Boolean) {
            trims += limit to keep
            trimCandidates.forEach { isProtected(it) }
        }

        override suspend fun list(): List<DraftInfo> =
            stored.values.map { DraftInfo(it.uri, it.baseline, it.savedAt, it.text.length) }
    }

    /** Keeps what is dispatched to it until the test runs it (a second test scheduler is refused). */
    private class HandRunDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val queued: Int get() = queue.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runQueued() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    /** A keeper wired to a real [DocumentSlot] the way the ViewModel wires it, on the test's clock. */
    private class Rig(
        private val test: TestScope,
        val drafts: FakeDrafts = FakeDrafts(),
        cpu: CoroutineDispatcher = UnconfinedTestDispatcher(test.testScheduler),
        isProtected: suspend (DraftInfo) -> Boolean = { false },
    ) {
        val slot = DocumentSlot()
        val keeper = DraftKeeper(
            drafts = drafts,
            scope = test.backgroundScope,
            cpu = cpu,
            slot = slot,
            isProtected = isProtected,
            timing = DraftTiming(now = { test.testScheduler.currentTime }),
        )

        init {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                slot.document.collect { keeper.onDocument(it) }
            }
        }

        val now: Long get() = test.testScheduler.currentTime

        fun open(uri: Uri?, text: String, lineEnding: String = "LF") {
            val document = Document(uri = uri, content = text, displayName = "notes.md")
            val publication = Publication(
                document = document,
                lineEnding = lineEnding,
                transient = false,
                switchToEditor = true,
                renderMode = RenderMode.MARKDOWN,
                plainToggleAvailable = false,
                headings = emptyList(),
                previewScroll = ScrollAnchor(),
                resetEditorScroll = false,
            )
            val outcome = slot.publish(slot.claim(), publication, ReplacePolicy.DISCARD)
            check(outcome == PublishOutcome.PUBLISHED)
            settle()
        }

        val id: DocumentId get() = slot.document.value!!.id

        fun type(text: String) {
            slot.updateContent(id, text)
            settle()
        }

        fun save(writtenText: String) {
            slot.markSaved(id, writtenText)
            settle()
        }

        /** Let [ms] pass and run everything that falls due, including what is due exactly at the end. */
        fun elapse(ms: Long) {
            test.testScheduler.advanceTimeBy(ms)
            settle()
        }

        fun settle() = test.testScheduler.runCurrent()
    }

    // ── when a draft is written ────────────────────────────────────────────────────────────────

    @Test
    fun `nothing is written before 1500 ms of quiet and one draft after`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("original\nplus a line\n")

        rig.elapse(1_499)
        assertTrue("1499 ms is not yet quiet enough", rig.drafts.writes.isEmpty())

        rig.elapse(1)
        assertEquals(1, rig.drafts.writes.size)
        val draft = rig.drafts.writes.single()
        assertEquals("original\nplus a line\n", draft.text)
        assertEquals(file, draft.uri)
        assertEquals("LF", draft.lineEnding)
        assertEquals("the baseline is what the file held", ByteFingerprint.of("original\n"), draft.baseline)
        assertEquals("stamped with the clock", 1_500L, draft.savedAt)
        assertEquals("the store is trimmed after a write, keeping this file", listOf(8 to file), rig.drafts.trims)

        rig.elapse(60_000)
        assertEquals("quiet means one write, not one per interval", 1, rig.drafts.writes.size)
    }

    @Test
    fun `a keystroke starts the wait again`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("first")
        rig.elapse(1_000)
        rig.type("second") // 1000 ms after the first: the timer starts over

        rig.elapse(1_000) // t = 2000, which is 2000 ms after the first keystroke but 1000 after the second
        assertTrue("the second keystroke pushed the write back", rig.drafts.writes.isEmpty())

        rig.elapse(500) // t = 2500 = 1500 after the second
        assertEquals(listOf("second"), rig.drafts.writes.map { it.text })
    }

    @Test
    fun `typing without a pause still writes every ten seconds`() = runTest {
        // A keystroke every 1000 ms never leaves 1500 ms of quiet, so only the cap can write.
        val rig = Rig(this)
        rig.open(file, "original\n")
        val saves = mutableListOf<Pair<Long, String>>()

        for (i in 0 until 25) {
            rig.type("typed $i")
            rig.elapse(1_000)
            if (rig.now == 9_000L) assertTrue("nothing before the cap", rig.drafts.writes.isEmpty())
            rig.drafts.writes.drop(saves.size).forEach { saves += it.savedAt to it.text }
        }

        // The keystroke at t=9000 is the last before the cap lands at t=10000; the next window runs
        // from the keystroke at t=10000 to the cap at 20000.
        assertEquals(listOf(10_000L to "typed 9", 20_000L to "typed 19"), saves)

        rig.elapse(500) // 1500 after the last keystroke (t=24000)
        assertEquals("typed 24", rig.drafts.writes.last().text)
    }

    @Test
    fun `a document that is clean never writes`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")

        rig.elapse(120_000)

        assertTrue(rig.drafts.writes.isEmpty())
        assertTrue(rig.drafts.detached.isEmpty())
        assertTrue("and there is nothing to remove either", rig.drafts.deletes.isEmpty())
    }

    @Test
    fun `a document without a file writes nothing`() = runTest {
        val rig = Rig(this)
        rig.open(null, "")
        rig.type("never saved")

        rig.elapse(120_000)
        rig.keeperFlush()

        assertTrue(rig.drafts.writes.isEmpty())
        assertTrue(rig.drafts.detached.isEmpty())
    }

    @Test
    fun `a failed write trims and removes nothing, and the next one goes through`() = runTest {
        val rig = Rig(this)
        rig.drafts.failWrites = true
        rig.open(file, "original\n")
        rig.type("first")
        rig.elapse(1_500)
        assertEquals(1, rig.drafts.writes.size)
        assertTrue("a write that failed trims nothing", rig.drafts.trims.isEmpty())
        assertTrue("and removes nothing", rig.drafts.deletes.isEmpty())

        rig.drafts.failWrites = false
        rig.type("second")
        rig.elapse(1_500)

        assertEquals("second", rig.drafts.stored[file]?.text)
    }

    // ── the size tiers (T11) ───────────────────────────────────────────────────────────────────

    @Test
    fun `a document over the edit ceiling writes nothing`() = runTest {
        val over = "x".repeat(EditorSupport.EDIT_CEILING_CHARS + 1)
        val rig = Rig(this)
        rig.open(file, "small")
        rig.type(over)

        rig.elapse(300_000)
        rig.keeperFlush()

        assertTrue("no timed write", rig.drafts.writes.isEmpty())
        assertTrue("no write on going to the background", rig.drafts.detached.isEmpty())
    }

    @Test
    fun `a document at the edit ceiling is still kept`() = runTest {
        // The boundary: the ceiling itself is editable, one more character is not.
        val rig = Rig(this)
        rig.open(file, "small")
        rig.type("x".repeat(EditorSupport.EDIT_CEILING_CHARS))

        rig.elapse(30_000)

        assertEquals(1, rig.drafts.writes.size)
    }

    @Test
    fun `a large document waits thirty seconds of quiet and has no ten second cap`() = runTest {
        val big = "x".repeat(EditorSupport.HIGHLIGHT_CEILING_CHARS + 1)
        val rig = Rig(this)
        rig.open(file, big)

        // A keystroke every 5 s for a minute: a normal document would have written at 10 s and 20 s.
        for (i in 0 until 12) {
            rig.type("$big$i")
            rig.elapse(5_000)
        }
        assertTrue("typing never paused for 30 s, so nothing was written", rig.drafts.writes.isEmpty())

        rig.elapse(24_999) // 29,999 ms after the last keystroke
        assertTrue(rig.drafts.writes.isEmpty())
        rig.elapse(1)
        assertEquals(listOf("${big}11"), rig.drafts.writes.map { it.text })
    }

    @Test
    fun `a draft found protected is asked about once across two writes`() = runTest {
        // Both writes trim, and the trim asks about the same stored draft (other, saved at 4_242) each time;
        // it was protected the first time, so the keeper must not ask again.
        val asked = mutableListOf<DraftInfo>()
        val rig = Rig(
            this,
            isProtected = { info ->
                asked += info
                true
            },
        )
        rig.drafts.trimCandidates = listOf(DraftInfo(other, ByteFingerprint.of("x"), 4_242L, 10))
        rig.open(file, "original\n")

        rig.type("first")
        rig.elapse(1_500)
        rig.type("second")
        rig.elapse(1_500)

        assertEquals("fixture: two writes, two trims", 2, rig.drafts.trims.size)
        assertEquals("asked about the protected draft once", 1, asked.size)
    }

    // ── when a draft is removed (T10) ──────────────────────────────────────────────────────────

    @Test
    fun `saving removes the draft`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("edited\n")
        rig.elapse(1_500)
        assertNotNull("a draft exists to be removed", rig.drafts.stored[file])

        rig.save("edited\n")

        assertEquals(listOf(file), rig.drafts.deletes)
        assertNull(rig.drafts.stored[file])
    }

    @Test
    fun `saving cancels a write that has not happened yet`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("edited\n")
        rig.elapse(1_000) // the write is 500 ms away

        rig.save("edited\n")
        rig.elapse(60_000)

        assertTrue("the write that was waiting did not land after the save", rig.drafts.writes.isEmpty())
        assertEquals(listOf(file), rig.drafts.deletes)
    }

    @Test
    fun `a save that did not include the latest keystrokes keeps the draft`() = runTest {
        // M-152: text typed while the write ran is still unsaved, so the document is still dirty.
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("a")
        rig.type("ab")

        rig.save("a") // what reached the disk
        rig.elapse(1_500)

        assertTrue("still dirty, so nothing is removed", rig.drafts.deletes.isEmpty())
        assertEquals("ab", rig.drafts.stored[file]?.text)
    }

    @Test
    fun `typing back to the original text removes the draft`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("original\nplus a line\n")
        rig.elapse(1_500)
        assertNotNull(rig.drafts.stored[file])

        rig.type(rig.slot.baseline) // undo all the way back

        assertEquals(listOf(file), rig.drafts.deletes)
        assertNull(rig.drafts.stored[file])
    }

    @Test
    fun `typing back to the original cancels a write that has not happened yet`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("original\nplus a line\n")
        rig.elapse(1_000)

        rig.type(rig.slot.baseline)
        rig.elapse(60_000)

        assertTrue(rig.drafts.writes.isEmpty())
    }

    @Test
    fun `throwing the edits away on purpose removes the draft`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("edited\n")
        rig.elapse(1_500)
        assertNotNull(rig.drafts.stored[file])

        assertEquals(PublishOutcome.PUBLISHED, rig.slot.publishClose(rig.slot.claim(), ReplacePolicy.DISCARD))
        rig.settle()

        assertEquals(listOf(file), rig.drafts.deletes)
        assertNull(rig.drafts.stored[file])
    }

    @Test
    fun `replacing the document with another on purpose removes the first one's draft only`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.type("edited\n")
        rig.elapse(1_500)

        rig.open(other, "another file\n") // DISCARD publish: the edits to the first are thrown away

        assertEquals(listOf(file), rig.drafts.deletes)
        assertTrue("the second file has no draft", other !in rig.drafts.stored)
    }

    @Test
    fun `a document saved while its draft is being built gets no draft written after the delete`() = runTest {
        // The cpu dispatcher only runs when this test says so, so the keeper can be
        // held between "the draft is being built" and "the draft is written". The document is dirty and
        // not held, the debounce has fired and nothing cancels the job (it already cleared `pending`),
        // so only the keeper's re-check after the hop can keep the stale write out.
        val cpu = HandRunDispatcher()
        val rig = Rig(this, cpu = cpu)
        rig.open(file, "original\n")
        rig.type("edited\n")

        rig.elapse(1_500) // the debounce fires; the keeper is now waiting for the cpu dispatcher
        assertTrue("fixture: the draft is being built, not yet written", rig.drafts.writes.isEmpty())
        rig.save("edited\n") // the document is saved meanwhile: the draft's delete is issued
        assertEquals("fixture: the delete was issued", listOf(file), rig.drafts.deletes)
        assertTrue("fixture: still nothing written", rig.drafts.writes.isEmpty())

        assertTrue("fixture: the keeper is waiting to build the draft", cpu.queued > 0)
        cpu.runQueued() // the draft is built
        rig.settle() // and the keeper carries on

        assertTrue("the stale draft was not written after the delete", rig.drafts.writes.isEmpty())
        assertTrue("and no detached write either", rig.drafts.detached.isEmpty())
        assertNull(rig.drafts.stored[file])
    }

    // ── going to the background ────────────────────────────────────────────────────────────────

    @Test
    fun `flush writes at once and survives the caller`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n", lineEnding = "CRLF")
        rig.type("edited\n")
        assertTrue("no time has passed", rig.now == 0L && rig.drafts.writes.isEmpty())

        rig.keeperFlush()

        val draft = rig.drafts.detached.single()
        assertEquals("edited\n", draft.text)
        assertEquals("CRLF", draft.lineEnding)
        assertEquals(ByteFingerprint.of("original\n"), draft.baseline)
        rig.elapse(60_000)
        assertTrue("the timed write was cancelled by the flush, not repeated", rig.drafts.writes.isEmpty())
    }

    @Test
    fun `flush of a clean document writes nothing`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")

        rig.keeperFlush()

        assertTrue(rig.drafts.detached.isEmpty())
    }

    // ── holding while the user decides ─────────────────────────────────────────────────────────

    @Test
    fun `a held file is not written until released, and then with the current text`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.keeper.hold(file)

        rig.type("first")
        rig.elapse(60_000)
        rig.type("second")
        rig.elapse(60_000)
        rig.keeperFlush()
        assertTrue("held: no timed write", rig.drafts.writes.isEmpty())
        assertTrue("held: no write on going to the background either", rig.drafts.detached.isEmpty())

        rig.keeper.release(file)
        rig.settle()
        assertTrue("release schedules, it does not write on the spot", rig.drafts.writes.isEmpty())
        rig.elapse(1_500)

        assertEquals(listOf("second"), rig.drafts.writes.map { it.text })
    }

    @Test
    fun `releasing a file that is clean writes nothing`() = runTest {
        val rig = Rig(this)
        rig.open(file, "original\n")
        rig.keeper.hold(file)

        rig.keeper.release(file)
        rig.elapse(60_000)

        assertTrue(rig.drafts.writes.isEmpty())
    }

    // ── which stored drafts are offered (T5a) ──────────────────────────────────────────────────

    private fun stored(rig: Rig, text: String, baseline: String) {
        rig.drafts.stored[file] = Draft(file, text, lineEnding = "LF", baseline = baseline, savedAt = 4_242L)
    }

    @Test
    fun `a draft equal to the file is removed and not offered`() = runTest {
        val rig = Rig(this)
        // The baseline is deliberately NOT the fingerprint of the disk text, so that only the
        // draft-equals-disk rule can be what answers.
        stored(rig, text = "the same text", baseline = ByteFingerprint.of("something older"))

        val offer = rig.keeper.offerFor(file, "notes.md", diskText = "the same text")

        assertNull(offer)
        assertEquals(listOf(file), rig.drafts.deletes)
        assertNull(rig.drafts.stored[file])
    }

    @Test
    fun `a draft typed on the file as it is now is offered plainly`() = runTest {
        val rig = Rig(this)
        stored(rig, text = "typed edits", baseline = ByteFingerprint.of("disk text"))

        val offer = rig.keeper.offerFor(file, "notes.md", diskText = "disk text")

        assertNotNull(offer)
        assertEquals(false, offer!!.diskChanged)
        assertEquals(file, offer.uri)
        assertEquals("notes.md", offer.displayName)
        assertEquals(4_242L, offer.savedAt)
        assertTrue("an offered draft is never removed", rig.drafts.deletes.isEmpty())
    }

    @Test
    fun `a draft typed on a file that has since changed is offered with the warning`() = runTest {
        // Draft text, disk text and baseline are three different strings and nothing else differs between
        // this and the plain case above: only the fingerprint comparison can say the file changed.
        val rig = Rig(this)
        stored(rig, text = "typed edits", baseline = ByteFingerprint.of("original text"))

        val offer = rig.keeper.offerFor(file, "notes.md", diskText = "someone else's edit")

        assertNotNull(offer)
        assertEquals(true, offer!!.diskChanged)
        assertTrue("a draft whose file changed is the user's to discard", rig.drafts.deletes.isEmpty())
        assertNotNull(rig.drafts.stored[file])
    }

    @Test
    fun `no stored draft means no offer`() = runTest {
        val rig = Rig(this)

        assertNull(rig.keeper.offerFor(file, "notes.md", diskText = "disk text"))
        assertTrue(rig.drafts.deletes.isEmpty())
    }

    private fun Rig.keeperFlush() {
        keeper.flush()
        settle()
    }
}
