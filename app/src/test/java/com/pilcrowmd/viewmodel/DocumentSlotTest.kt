// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.storage.ScrollAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The slot's rules on their own, with no coroutines in the way. */
@RunWith(RobolectricTestRunner::class)
class DocumentSlotTest {

    private fun publication(content: String, uri: Uri? = null, transient: Boolean = false) = Publication(
        document = Document(uri = uri, content = content, displayName = "d.md"),
        lineEnding = "LF",
        transient = transient,
        switchToEditor = false,
        renderMode = RenderMode.MARKDOWN,
        plainToggleAvailable = false,
        headings = emptyList(),
        previewScroll = ScrollAnchor(),
        resetEditorScroll = false,
    )

    @Test
    fun `a claim that is abandoned does not invalidate an older one still in flight`() {
        // C3 on M-109: the rule is "newer than the last PUBLISHED claim", not "no newer claim exists".
        val slot = DocumentSlot()
        val older = slot.claim()
        slot.claim() // taken, then the operation gives up without publishing
        assertEquals(PublishOutcome.PUBLISHED, slot.publish(older, publication("a"), ReplacePolicy.IF_CLEAN))
    }

    @Test
    fun `an older claim loses once a newer one has published`() {
        val slot = DocumentSlot()
        val older = slot.claim()
        val newer = slot.claim()
        assertEquals(PublishOutcome.PUBLISHED, slot.publish(newer, publication("new"), ReplacePolicy.DISCARD))
        assertEquals(PublishOutcome.SUPERSEDED, slot.publish(older, publication("old"), ReplacePolicy.DISCARD))
        assertEquals("new", slot.document.value?.content)
        assertFalse(slot.isLatestPublished(older))
        assertTrue(slot.isLatestPublished(newer))
    }

    @Test
    fun `IF_CLEAN refuses to replace a dirty document and DISCARD does not`() {
        val slot = DocumentSlot()
        slot.publish(slot.claim(), publication("a"), ReplacePolicy.IF_CLEAN)
        slot.updateContent(slot.document.value!!.id, "a, edited")
        assertEquals(PublishOutcome.REFUSED_DIRTY, slot.publish(slot.claim(), publication("b"), ReplacePolicy.IF_CLEAN))
        assertEquals(PublishOutcome.REFUSED_DIRTY, slot.publishClose(slot.claim(), ReplacePolicy.IF_CLEAN))
        assertEquals("a, edited", slot.document.value?.content)
        assertEquals(PublishOutcome.PUBLISHED, slot.publishClose(slot.claim(), ReplacePolicy.DISCARD))
        assertNull(slot.document.value)
    }

    @Test
    fun `markSaved clears dirty only when nothing was typed, and only for the document written`() {
        val slot = DocumentSlot()
        slot.publish(slot.claim(), publication("a"), ReplacePolicy.IF_CLEAN)
        val id = slot.document.value!!.id
        slot.updateContent(id, "a, saved")
        slot.updateContent(id, "a, saved, plus one")
        slot.markSaved(id, "a, saved")
        assertTrue("an unwritten keystroke was marked saved", slot.document.value!!.dirty)
        slot.updateContent(id, "a, saved") // type back to what is on disk: the baseline moved, so clean
        assertFalse(slot.document.value!!.dirty)

        slot.updateContent(id, "a, other")
        slot.publish(slot.claim(), publication("b"), ReplacePolicy.DISCARD)
        slot.markSaved(id, "a, other") // a save of A finishing after B replaced it
        slot.updateContent(slot.document.value!!.id, "a, other")
        assertTrue("A's save moved B's baseline", slot.document.value!!.dirty)
    }

    @Test
    fun `an edit made on a document that is no longer open is dropped`() {
        // For up to a frame after B publishes, the editor still shows A and still reports A's edits.
        val slot = DocumentSlot()
        slot.publish(slot.claim(), publication("a"), ReplacePolicy.IF_CLEAN)
        val a = slot.document.value!!.id
        slot.publish(slot.claim(), publication("b"), ReplacePolicy.IF_CLEAN)
        slot.updateContent(a, "a, with a late keystroke")
        assertEquals("A's edit was written into B", "b", slot.document.value?.content)
        assertFalse("A's edit dirtied B", slot.document.value!!.dirty)
    }

    @Test
    fun `adoptIdentity only lands on the document that was written`() {
        val slot = DocumentSlot()
        slot.publish(slot.claim(), publication("a"), ReplacePolicy.IF_CLEAN)
        val written = slot.document.value!!.id
        slot.publish(slot.claim(), publication("b"), ReplacePolicy.IF_CLEAN)
        val target = Uri.parse("content://t/copy.md")
        assertFalse(slot.adoptIdentity(written, "a", target, "copy.md", transient = false))
        assertNull("B took the copy's URI", slot.document.value!!.uri)
    }

    // The closed flag starts false, so each test below first proves it is TRUE (the close took
    // effect) and only then that the door under test put it back.
    private fun DocumentSlot.openTransientAndClose(): DocumentId {
        publish(claim(), publication("a", Uri.parse("content://t/a.md"), transient = true), ReplacePolicy.IF_CLEAN)
        transientNoticeClosed.value = true
        assertTrue("the close did not take effect", transientNoticeClosed.value)
        return document.value!!.id
    }

    @Test
    fun `closing the temporary-file notice sets the flag for the open document`() {
        val slot = DocumentSlot()
        slot.publish(slot.claim(), publication("a", transient = true), ReplacePolicy.IF_CLEAN)
        assertFalse("the notice starts open", slot.transientNoticeClosed.value)
        slot.transientNoticeClosed.value = true
        assertTrue(slot.transientNoticeClosed.value)
    }

    @Test
    fun `publishing another document brings the notice back`() {
        val slot = DocumentSlot()
        slot.openTransientAndClose()
        val next = publication("b", Uri.parse("content://t/b.md"), transient = true)
        slot.publish(slot.claim(), next, ReplacePolicy.IF_CLEAN)
        assertFalse("the next temporary file opened with its notice closed", slot.transientNoticeClosed.value)
    }

    @Test
    fun `closing the document brings the notice back`() {
        val slot = DocumentSlot()
        slot.openTransientAndClose()
        slot.publishClose(slot.claim(), ReplacePolicy.IF_CLEAN)
        assertFalse(slot.transientNoticeClosed.value)
    }

    @Test
    fun `adopting a new file brings the notice back, even when the copy is still temporary`() {
        // transient = true on the adopted file, so the banner would show again: only the reset in
        // adoptIdentity can open it, not the document ceasing to be transient.
        val slot = DocumentSlot()
        val id = slot.openTransientAndClose()
        assertTrue(slot.adoptIdentity(id, "a", Uri.parse("content://t/copy.md"), "copy.md", transient = true))
        assertFalse(slot.transientNoticeClosed.value)
    }

    @Test
    fun `a file published again gets its caret back, a new one starts at 0`() {
        val slot = DocumentSlot()
        val a = Uri.parse("content://t/a.md")
        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)
        slot.editorShownOnDocument = true
        slot.editorCursor.value = 123
        slot.publish(slot.claim(), publication("b", Uri.parse("content://t/b.md")), ReplacePolicy.IF_CLEAN)
        assertEquals(0, slot.editorCursor.value)
        assertFalse(slot.editorCursorFromSession)

        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)

        assertEquals(123, slot.editorCursor.value)
        assertTrue(slot.editorCursorFromSession)
    }

    @Test
    fun `a collector of the document that resumes inside the publish already sees the caret was kept`() {
        // The ViewModel reads editorCursorFromSession when the document changes; an unconfined
        // collector resumes right in the `_document.value = ...` write, so the flag must be set before it.
        val slot = DocumentSlot()
        val a = Uri.parse("content://t/a.md")
        val seen = mutableListOf<Pair<Uri?, Boolean>>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { slot.document.collect { doc -> seen += doc?.uri to slot.editorCursorFromSession } }
        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)
        slot.editorShownOnDocument = true
        slot.editorCursor.value = 123
        slot.publish(slot.claim(), publication("b", Uri.parse("content://t/b.md")), ReplacePolicy.IF_CLEAN)

        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)
        scope.cancel()

        assertEquals("a reopened: the collector saw the kept caret", a to true, seen.last())
    }

    @Test
    fun `a file the editor was never shown on keeps no caret, so reopening it is a new file`() {
        val slot = DocumentSlot()
        val a = Uri.parse("content://t/a.md")
        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)
        slot.publish(slot.claim(), publication("b", Uri.parse("content://t/b.md")), ReplacePolicy.IF_CLEAN)

        slot.publish(slot.claim(), publication("a".repeat(200), a), ReplacePolicy.IF_CLEAN)

        assertFalse("0 here is the default, not a place the user left", slot.editorCursorFromSession)
    }
}
