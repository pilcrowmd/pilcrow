// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.storage.ScrollAnchor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A claim on the document slot, taken BEFORE an operation first suspends, so that the order of
 * claims is the order in which operations STARTED — not the order in which they happen to finish.
 */
@JvmInline
internal value class Claim(val seq: Long)

/** Whether a replacement may discard unsaved edits. */
internal enum class ReplacePolicy {
    /** Refuse to replace a document that is dirty at the moment of publishing (M-180). */
    IF_CLEAN,

    /** Replace regardless — only after the user has chosen to throw the edits away. */
    DISCARD,
}

internal enum class PublishOutcome {
    PUBLISHED,

    /** An operation that started later has already published; this one lost (M-109). */
    SUPERSEDED,

    /** [ReplacePolicy.IF_CLEAN] and the document on screen has unsaved edits. */
    REFUSED_DIRTY,
}

/**
 * Everything that makes a document current, applied together. The caller cannot publish a
 * document without its trailing state, because there is no way to express that here (M-127).
 */
internal data class Publication(
    val document: Document,
    val lineEnding: String,
    val transient: Boolean,
    /** One-directional (M-90): true switches to the editor, false leaves the mode as it is. */
    val switchToEditor: Boolean,
    val renderMode: RenderMode,
    val plainToggleAvailable: Boolean,
    val headings: List<HeadingNode>,
    val previewScroll: ScrollAnchor,
    val resetEditorScroll: Boolean,
)

/**
 * **The open document and everything derived from it, with exactly two ways to replace it.**
 *
 * Every value here belongs to ONE document. They used to be ten independent fields of the ViewModel,
 * each writable from anywhere in it, and every operation that replaced the document wrote them one
 * by one, around suspension points, without checking that the document it had started from was
 * still the one on screen. That is M-109 (a restore overwrites a new document), M-180 (a load
 * overwrites text typed while it ran), M-152 (a save marks unwritten keystrokes as saved), and the
 * rows listed in `docs/proposals/1-0-11-file-safety.md`. The check was added at call sites four
 * times, and each round of review found more sites, so the ViewModel no longer holds a writable
 * handle to any of it.
 *
 * **The doors:**
 * - [publish] and [publishClose] REPLACE the document. Both are adjudicated: the operation that
 *   STARTED last wins ([claim] ordering), and with [ReplacePolicy.IF_CLEAN] a document with unsaved
 *   edits is never replaced.
 * - [adoptIdentity] gives the document ALREADY in the slot a new file (Save-As), only if it is still
 *   the document that was written (M-147).
 * - [updateContent] and [markSaved] change the document in place and cannot change its identity:
 *   there is no parameter through which one could arrive.
 * - [setRendering] changes the render mode or TOC of one document, never identity.
 * - [mode], [previewScroll], [editorScroll] and [editorCursor] are VIEW state: the user moves them
 *   freely, so they are plain writable flows. They carry no identity; a publish resets them in the
 *   same step as the document it belongs to.
 *
 * **Adjudication is monotonic:** a publish succeeds only if its claim is newer than the last one
 * that published. It does NOT ask whether a newer claim merely exists, so an operation that claims
 * and then gives up without publishing cannot invalidate one still in flight (C3 on M-109).
 *
 * **Thread safety:** every mutation is `@Synchronized`, so check-and-apply is atomic whatever thread
 * calls it. In production every caller is on the main thread (`viewModelScope`).
 */
internal class DocumentSlot {
    private val nextSeq = AtomicLong(0)
    private var lastPublishedSeq = -1L

    // Baseline for dirty detection. Lives with the document it describes, so a second document
    // can never overwrite the first one's baseline (the #141 round-2 finding).
    private var originalContent: String = ""

    private val _document = MutableStateFlow<Document?>(null)
    val document: StateFlow<Document?> = _document.asStateFlow()

    private val _lineEnding = MutableStateFlow("LF")
    val lineEnding: StateFlow<String> = _lineEnding.asStateFlow()

    private val _transient = MutableStateFlow(false)
    val transient: StateFlow<Boolean> = _transient.asStateFlow()

    val mode = MutableStateFlow<ViewMode>(ViewMode.READER)

    private val _renderMode = MutableStateFlow(RenderMode.MARKDOWN)
    val renderMode: StateFlow<RenderMode> = _renderMode.asStateFlow()

    private val _plainToggleAvailable = MutableStateFlow(false)
    val plainToggleAvailable: StateFlow<Boolean> = _plainToggleAvailable.asStateFlow()

    private val _headings = MutableStateFlow<List<HeadingNode>>(emptyList())
    val headings: StateFlow<List<HeadingNode>> = _headings.asStateFlow()

    val previewScroll = MutableStateFlow(ScrollAnchor())
    val editorScroll = MutableStateFlow(0)
    val editorCursor = MutableStateFlow(0)

    // M-126: a failed load earns a persistent message only when it leaves nothing on screen.
    // Retired by every publish, so no door that makes a document current can forget it.
    private val _loadFailedWithNoDocument = MutableStateFlow(false)
    val loadFailedWithNoDocument: StateFlow<Boolean> = _loadFailedWithNoDocument.asStateFlow()

    /** Take a claim. Call it before the operation first suspends, and after its early returns. */
    fun claim(): Claim = Claim(nextSeq.getAndIncrement())

    /** True while nothing has published since [claim] did — the guard for persisting its identity. */
    @Synchronized
    fun isLatestPublished(claim: Claim): Boolean = claim.seq == lastPublishedSeq

    /** Replace the document with [publication], applied in one step, if [claim] may. */
    @Synchronized
    fun publish(claim: Claim, publication: Publication, policy: ReplacePolicy): PublishOutcome {
        val outcome = adjudicate(claim, policy)
        if (outcome != PublishOutcome.PUBLISHED) return outcome
        _lineEnding.value = publication.lineEnding
        originalContent = publication.document.content
        // Mode BEFORE the document (M-111): the screen then composes the editor for the new
        // document and never parses it into a reader it would throw away.
        if (publication.switchToEditor) mode.value = ViewMode.EDITOR
        _document.value = publication.document
        _transient.value = publication.transient
        editorCursor.value = 0
        _plainToggleAvailable.value = publication.plainToggleAvailable
        _renderMode.value = publication.renderMode
        _headings.value = publication.headings
        previewScroll.value = publication.previewScroll
        if (publication.resetEditorScroll) editorScroll.value = 0
        _loadFailedWithNoDocument.value = false
        return outcome
    }

    /** Close the document (nothing on screen), if [claim] may. */
    @Synchronized
    fun publishClose(claim: Claim, policy: ReplacePolicy): PublishOutcome {
        val outcome = adjudicate(claim, policy)
        if (outcome != PublishOutcome.PUBLISHED) return outcome
        _document.value = null
        _transient.value = false
        mode.value = ViewMode.READER
        previewScroll.value = ScrollAnchor()
        editorScroll.value = 0
        return outcome
    }

    private fun adjudicate(claim: Claim, policy: ReplacePolicy): PublishOutcome = when {
        claim.seq <= lastPublishedSeq -> PublishOutcome.SUPERSEDED
        policy == ReplacePolicy.IF_CLEAN && _document.value?.dirty == true -> PublishOutcome.REFUSED_DIRTY
        else -> {
            lastPublishedSeq = claim.seq
            PublishOutcome.PUBLISHED
        }
    }

    /**
     * Give the document already in the slot the file it was just written to (Save-As), keeping its
     * live content. Only if it is still document [expected] — compared by [DocumentId], because two
     * unsaved documents both have `uri == null` (M-147).
     */
    @Synchronized
    fun adoptIdentity(
        expected: DocumentId,
        writtenContent: String,
        uri: android.net.Uri,
        displayName: String,
        transient: Boolean,
    ): Boolean {
        val current = _document.value
        if (current == null || current.id != expected) return false
        // Edits typed during the write stay, and keep the document dirty (Safeguard 2). The file it
        // now has was written as UTF-8, so a document read from a non-UTF-8 file may save in place
        // again (NEW-12) — to the copy; the original was never touched.
        _document.value = current.copy(
            uri = uri,
            displayName = displayName,
            dirty = current.content != writtenContent,
            notUtf8 = false,
        )
        originalContent = writtenContent
        _transient.value = transient
        return true
    }

    /**
     * The user typed into document [expected]. Dirty is measured against the baseline, so
     * type-then-undo is clean again. No-op if another document is on screen by now: for up to a frame
     * after a load publishes, the editor still shows the previous document and still reports its
     * edits, and writing that text onto the new document would save it into the wrong file.
     */
    @Synchronized
    fun updateContent(expected: DocumentId, newContent: String) {
        val current = _document.value
        if (current == null || current.id != expected) return
        _document.value = current.copy(content = newContent, dirty = newContent != originalContent)
    }

    /**
     * [savedContent] reached disk for document [expected]. The baseline moves to it, and `dirty`
     * clears ONLY if nothing was typed during the write — otherwise those keystrokes would be marked
     * saved and never written (M-152). No-op if another document is on screen by now.
     */
    @Synchronized
    fun markSaved(expected: DocumentId, savedContent: String) {
        val current = _document.value
        if (current == null || current.id != expected) return
        originalContent = savedContent
        if (current.content == savedContent) _document.value = current.copy(dirty = false)
    }

    /**
     * A load failed ([failed] = true): remember whether it left the user with nothing on screen.
     * A new attempt ([failed] = false) retires the last failure's message. M-126.
     */
    @Synchronized
    fun markLoadFailure(failed: Boolean) {
        _loadFailedWithNoDocument.value = failed && _document.value == null
    }

    /**
     * Render mode, plain toggle and/or TOC for document [expected] only — a no-op if another document
     * is current by now. A null argument leaves that value alone.
     */
    @Synchronized
    fun setRendering(
        expected: DocumentId,
        renderMode: RenderMode? = null,
        plainToggleAvailable: Boolean? = null,
        headings: List<HeadingNode>? = null,
    ) {
        if (_document.value?.id != expected) return
        plainToggleAvailable?.let { _plainToggleAvailable.value = it }
        renderMode?.let { _renderMode.value = it }
        headings?.let { _headings.value = it }
    }
}
