// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import com.pilcrowmd.domain.model.EditorSupport
import com.pilcrowmd.repository.ByteFingerprint
import com.pilcrowmd.repository.Draft
import com.pilcrowmd.repository.DraftInfo
import com.pilcrowmd.repository.DraftRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** When a draft is written (milliseconds). */
internal data class DraftTiming(
    /** After the last keystroke. */
    val idle: Long = 1_500,
    /** At most this long between writes while typing never pauses. */
    val maxWait: Long = 10_000,
    /** For a document too large to rewrite every few seconds: after this long without a keystroke, and no cap. */
    val largeIdle: Long = 30_000,
    /** The clock the draft's time and the cap are read from. */
    val now: () -> Long = System::currentTimeMillis,
)

/** A draft the user has not yet decided about, offered when its file is opened. */
data class RecoveryOffer(
    val uri: Uri,
    val displayName: String,
    val savedAt: Long,
    /** The file is not what the edits were typed on top of (changed by another app, say). */
    val diskChanged: Boolean,
)

/** A stored draft whose file can no longer be opened, listed so it can be saved as a copy or discarded. */
data class OrphanDraft(val uri: Uri, val displayName: String, val savedAt: Long)

/**
 * Keeps a copy of unsaved text so a kill, a crash or a reboot does not lose it (Safeguard 1).
 *
 * It watches the open document and holds a draft exactly while the document is dirty: written
 * [DraftTiming.idle] after the last keystroke (and at the latest every [DraftTiming.maxWait]), and
 * at once on [flush] when the app goes to the background. It removes the draft when the document
 * stops being dirty (saved, or edited back to the original) or is thrown away on purpose, and at no
 * other time: a draft is never dropped because something failed.
 *
 * It never writes the file the draft belongs to. Everything runs on the ViewModel's scope; the
 * [DraftRepository] does the I/O off the main thread.
 *
 * **Offers.** When a file is opened, [hold] stops this class writing a draft for it until the user
 * has decided about the one already stored ([release]); otherwise the first keystroke would
 * overwrite the very text the user is about to be asked about.
 *
 * Documents without a file (never saved, M-91) have no draft: a draft is found again by its file.
 */
internal class DraftKeeper(
    private val drafts: DraftRepository,
    private val scope: CoroutineScope,
    private val cpu: CoroutineDispatcher,
    private val slot: DocumentSlot,
    private val isProtected: suspend (DraftInfo) -> Boolean,
    private val timing: DraftTiming = DraftTiming(),
) {
    private var last: Document? = null
    private var pending: Job? = null
    private var pendingSince: Long? = null
    private val held = HashSet<Uri>()

    // Fingerprint of the baseline, computed once per baseline text (the text, not its value, is the key).
    private class Printed(val text: String, val print: String)

    @Volatile
    private var printed: Printed? = null

    // A draft found protected stays protected until it is rewritten: asking again would read the whole
    // file again after every write (and the file is the user's, possibly on a slow provider).
    private val protectedSeen = HashSet<Pair<Uri, Long>>()
    private val protectedOnce: suspend (DraftInfo) -> Boolean = { info ->
        val seen = info.uri to info.savedAt
        seen in protectedSeen || isProtected(info).also { if (it) protectedSeen += seen }
    }

    /** The open document changed (every emission of the slot's document). */
    fun onDocument(doc: Document?) {
        val previous = last
        last = doc
        if (previous?.uri != null && stoppedBeingDirty(previous, doc)) {
            // Saved, edited back to the original, or discarded on purpose: nothing left to protect.
            cancelPending()
            val gone = previous.uri
            scope.launch { drafts.delete(gone) }
        }
        warmBaselinePrint(doc)
        val uri = doc?.uri
        when {
            doc == null || !doc.dirty || uri == null || uri in held -> cancelPending()
            doc.content.length > EditorSupport.EDIT_CEILING_CHARS -> cancelPending()
            else -> schedule(doc)
        }
    }

    /** Write the draft of the dirty document now, surviving the caller's scope: the app is going away. */
    fun flush() {
        val doc = slot.document.value ?: return
        val uri = doc.uri ?: return
        if (!doc.dirty || uri in held || doc.content.length > EditorSupport.EDIT_CEILING_CHARS) return
        cancelPending()
        drafts.writeDetached(draftOf(doc, uri))
    }

    /** Do not write a draft for [uri] until [release]: the user is about to be asked about the stored one. */
    fun hold(uri: Uri) {
        held += uri
        cancelPending()
    }

    /** The user decided: drafts for [uri] are written again, starting with the document as it is now. */
    fun release(uri: Uri) {
        held -= uri
        onDocument(last)
    }

    /**
     * The offer for [uri]'s stored draft, or null if there is none worth offering. [diskText] is what
     * the file held when it was opened (LF form). A draft that equals it is removed on the spot:
     * there is nothing to recover.
     */
    suspend fun offerFor(uri: Uri, displayName: String, diskText: String): RecoveryOffer? {
        val draft = drafts.read(uri) ?: return null
        return withContext(cpu) {
            if (draft.text == diskText) {
                drafts.delete(uri)
                null
            } else {
                val changed = ByteFingerprint.of(diskText) != draft.baseline
                RecoveryOffer(uri, displayName, draft.savedAt, diskChanged = changed)
            }
        }
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
        pendingSince = null
    }

    private fun schedule(doc: Document) {
        val large = doc.content.length > EditorSupport.HIGHLIGHT_CEILING_CHARS
        val since = pendingSince ?: timing.now().also { pendingSince = it }
        val idle = if (large) timing.largeIdle else timing.idle
        // The cap is only for the tiers that can afford a write every few seconds (and no sum with a huge number).
        val wait = if (large) idle else minOf(idle, maxOf(0L, since + timing.maxWait - timing.now()))
        pending?.cancel()
        pending = scope.launch {
            delay(wait)
            pending = null
            pendingSince = null
            val current = last ?: return@launch
            val uri = current.uri ?: return@launch
            if (!current.dirty || uri in held) return@launch
            val draft = withContext(cpu) { draftOf(current, uri) }
            // The document may have been saved or thrown away while the draft was being built: its delete
            // is already issued, and this write would bring the draft back.
            if (last?.id != current.id || last?.dirty != true || uri in held) return@launch
            if (drafts.write(draft).isFailure) {
                android.util.Log.w("DraftKeeper", "could not write the draft")
                return@launch
            }
            drafts.trim(MAX_DRAFTS, keep = uri, isProtected = protectedOnce)
        }
    }

    private fun draftOf(doc: Document, uri: Uri): Draft =
        Draft(uri, doc.content, slot.lineEnding.value, baselinePrint(), timing.now())

    private fun baselinePrint(): String {
        val text = slot.baseline
        printed?.let { if (it.text === text) return it.print }
        return ByteFingerprint.of(text).also { printed = Printed(text, it) }
    }

    /** Work the baseline's fingerprint out off the main thread, so the first write does not wait on it. */
    private fun warmBaselinePrint(doc: Document?) {
        if (doc?.uri == null) return
        val text = slot.baseline
        if (printed?.text === text) return
        scope.launch(cpu) { if (printed?.text !== text) printed = Printed(text, ByteFingerprint.of(text)) }
    }

    companion object {
        /** Drafts kept for this many files, as many as the recent-files list. */
        const val MAX_DRAFTS = 8
    }
}

/** [previous] was dirty and [doc] is not it, or is it but clean. */
private fun stoppedBeingDirty(previous: Document, doc: Document?): Boolean =
    previous.dirty && (doc == null || doc.id != previous.id || !doc.dirty)
