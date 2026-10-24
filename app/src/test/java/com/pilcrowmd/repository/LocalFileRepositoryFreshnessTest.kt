// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * Safeguard 1 (no data loss) for two ways a pending copy used to do harm:
 *
 *  - G5: recovery after a killed save must not overwrite a file another app changed in between.
 *  - G6: a save that fails before touching its target must not delete an older pending copy.
 *
 * Every case runs over a real temp file as the target. A killed save is made with
 * [LocalFileRepository.debugStageWithoutCommit] (slot committed, target truncated); the target is then
 * put into the state under test by writing to the file directly. All calls are `runBlocking` over
 * suspend functions that really complete, and each case first asserts the state it starts from, so an
 * outcome that already held before the call cannot make it pass.
 */
@RunWith(RobolectricTestRunner::class)
class LocalFileRepositoryFreshnessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var walBaseDir: File
    private lateinit var target: File
    private val uri: Uri = Uri.parse("content://test/doc/fresh.md")

    @Before
    fun setup() {
        resolver = mockk(relaxed = false)
        walBaseDir = tempFolder.newFolder("nobackup")
        target = tempFolder.newFile("target.md")
        target.writeText(ORIGINAL)
        stubReadAndTruncatingWrite(uri, target)
    }

    private fun repo() = LocalFileRepository(resolver, walBaseDir)

    /** "r" opens a fresh read-only descriptor over [file]; "wt" a fresh truncating one (a normal SAF target). */
    private fun stubReadAndTruncatingWrite(forUri: Uri, file: File) {
        every { resolver.openFileDescriptor(forUri, "r") } answers {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        every { resolver.openFileDescriptor(forUri, "wt") } answers { SaveFaultRig.openWt(file) }
    }

    /** A save killed after its slot was committed and the target truncated. */
    private fun killedSave(repo: LocalFileRepository, content: String) = runBlocking {
        assertEquals("fixture: the target starts as the original", ORIGINAL, target.readText())
        assertTrue(repo.debugStageWithoutCommit(uri, content).isSuccess)
        assertEquals("fixture: the kill truncated the target", "", target.readText())
        assertEquals("fixture: the slot is stranded", 1, repo.strandedSlots().getOrThrow().size)
    }

    // ---- G5: recovery must not overwrite a file another app changed ----

    @Test
    fun recoveryLeavesAFileAnotherAppChangedAndKeepsTheSlot() = runBlocking {
        val repo = repo()
        killedSave(repo, STAGED)
        target.writeText(OTHER_APP)

        val recovered = repo.recoverPendingSaves().getOrThrow()

        assertEquals("the other app's text is still in the target", OTHER_APP, target.readText())
        assertEquals("nothing was recovered", 0, recovered)
        assertEquals("the slot is still offered to the user", 1, repo.strandedSlots().getOrThrow().size)
        assertEquals(
            "the file reads as the other app's text, not the staged text",
            OTHER_APP,
            repo.readFile(uri).getOrThrow().content,
        )
    }

    @Test
    fun recoveryWritesTheStagedTextIntoATruncatedTarget() = runBlocking {
        val repo = repo()
        killedSave(repo, STAGED)

        val recovered = repo.recoverPendingSaves().getOrThrow()

        assertEquals(1, recovered)
        assertEquals(STAGED, target.readText())
        assertTrue("slot gone", repo.strandedSlots().getOrThrow().isEmpty())
    }

    @Test
    fun recoveryCompletesATargetHoldingOnlyTheFirstHalfOfTheStagedBytes() = runBlocking {
        val repo = repo()
        killedSave(repo, STAGED)
        val bytes = STAGED.toByteArray(Charsets.UTF_8)
        target.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
        assertTrue("fixture: the target is cut short", target.length() in 1 until bytes.size)

        repo.recoverPendingSaves().getOrThrow()

        assertEquals(STAGED, target.readText())
        assertTrue("slot gone", repo.strandedSlots().getOrThrow().isEmpty())
    }

    @Test
    fun recoveryDropsTheSlotWhenTheTargetAlreadyHoldsTheStagedBytes() = runBlocking {
        val repo = repo()
        killedSave(repo, STAGED)
        target.writeText(STAGED)

        repo.recoverPendingSaves().getOrThrow()

        assertEquals(STAGED, target.readText())
        assertTrue("slot gone", repo.strandedSlots().getOrThrow().isEmpty())
    }

    @Test
    fun recoveryWritesTheStagedTextWhenTheTargetWasNeverOpened() = runBlocking {
        val repo = repo()
        // The kill came before the target was opened: the "wt" open lands on a scratch file, so the
        // target keeps its original text.
        val sink = tempFolder.newFile("sink.md")
        every { resolver.openFileDescriptor(uri, "wt") } answers { SaveFaultRig.openWt(sink) }
        assertTrue(repo.debugStageWithoutCommit(uri, STAGED).isSuccess)
        assertEquals("fixture: the target still holds its original text", ORIGINAL, target.readText())
        assertEquals("fixture: the slot is stranded", 1, repo.strandedSlots().getOrThrow().size)
        stubReadAndTruncatingWrite(uri, target)

        val recovered = repo.recoverPendingSaves().getOrThrow()

        assertEquals(1, recovered)
        assertEquals(STAGED, target.readText())
        assertTrue("slot gone", repo.strandedSlots().getOrThrow().isEmpty())
    }

    // ---- G6: a failed save must not delete an older pending copy ----

    @Test
    fun aSaveThatCannotOpenItsTargetKeepsTheOlderPendingCopy() = runBlocking {
        val repo = repo()
        killedSave(repo, OLDER)
        every { resolver.openFileDescriptor(uri, "wt") } throws IOException("provider refused")

        val result = repo.saveFile(uri, NEWER)

        assertTrue("the save failed", result.isFailure)
        val slots = repo.strandedSlots().getOrThrow()
        assertEquals("the older copy is still stranded", 1, slots.size)

        val rescued = tempFolder.newFile("rescued.md")
        val otherUri = Uri.parse("content://test/doc/rescued.md")
        every { resolver.openFileDescriptor(otherUri, "wt") } answers { SaveFaultRig.openWt(rescued) }
        assertTrue(repo.saveStrandedSlotToTarget(slots.single().key, otherUri).isSuccess)

        assertEquals("the rescued copy is the older text", OLDER, rescued.readText())
    }

    @Test
    fun aSaveThatSucceedsLeavesTheNewTextAndNoSlot() = runBlocking {
        val repo = repo()
        killedSave(repo, OLDER)

        val result = repo.saveFile(uri, NEWER)

        assertTrue("the save succeeded", result.isSuccess)
        assertEquals(NEWER, target.readText())
        assertTrue("no slot remains", repo.strandedSlots().getOrThrow().isEmpty())
    }

    // ---- G5 + a later save: a slot kept from another app's change outlives a successful save ----

    @Test
    fun aSlotAnotherAppChangedTheTargetUnderSurvivesALaterSuccessfulSave() = runBlocking {
        val repo = repo()
        killedSave(repo, STAGED)
        target.writeText(OTHER_APP)
        assertEquals("fixture: the slot is still stranded", 1, repo.strandedSlots().getOrThrow().size)

        val result = repo.saveFile(uri, USER_EDIT)

        assertTrue("the save succeeded", result.isSuccess)
        assertEquals("the target holds what the user just saved", USER_EDIT, target.readText())
        val slots = repo.strandedSlots().getOrThrow()
        assertEquals("the slot of the other-app conflict is still pending", 1, slots.size)

        val rescued = tempFolder.newFile("rescued.md")
        val rescuedUri = Uri.parse("content://test/doc/rescued.md")
        every { resolver.openFileDescriptor(rescuedUri, "wt") } answers { SaveFaultRig.openWt(rescued) }
        assertTrue(repo.saveStrandedSlotToTarget(slots.single().key, rescuedUri).isSuccess)
        assertEquals("and it holds the text that was staged, not the user's edit", STAGED, rescued.readText())
    }

    private companion object {
        const val USER_EDIT = "user edit"
        const val ORIGINAL = "The original text of the file."
        const val STAGED = "Staged by Pilcrow: a long enough line to cut in half."
        const val OTHER_APP = "Edited elsewhere, by another app."
        const val OLDER = "OLDER"
        const val NEWER = "NEWER"
    }
}
