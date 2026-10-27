// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.domain.folders.LinkHistory
import com.pilcrowmd.domain.folders.NoteLinkPaths
import com.pilcrowmd.domain.folders.OpenFileLine
import com.pilcrowmd.repository.NoteFolderContext
import com.pilcrowmd.repository.NoteLinkRepository
import com.pilcrowmd.repository.ResolvedLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What the screen does with a followed link. One-shot. */
sealed interface NoteLinkEvent {
    /** Open [uri] through the reader's normal open (an unsaved note is asked about first, K11). */
    data class Open(val uri: Uri, val heading: String?) : NoteLinkEvent

    /** K8: hand the file to the system. */
    data class OpenExternal(val uri: Uri, val mimeType: String?) : NoteLinkEvent

    /** K7: "Note not found: [name]". */
    data class NotFound(val name: String) : NoteLinkEvent

    /** Not a note link after all: today's behaviour, which for a relative path is nothing. */
    data object Fallback : NoteLinkEvent
}

/**
 * The K13/K14 bare links of the note [uri] that name a note. Tied to its note, so the reader never
 * applies one note's set to the next while the next one's is being worked out.
 */
data class NoteBareLinks(val uri: Uri?, val destinations: Set<String>)

/**
 * The folder names above the note [uri], outermost first ([names] empty when it is in no added
 * folder, or they are not known yet). Tied to its note, so the open-file line never shows one
 * note's folders over the next note's name.
 */
data class NoteFolderPath(val uri: Uri?, val names: List<String>)

/**
 * The open-file line for the document [name] at [uri]: its folder names only when [path] was worked
 * out for this very document, otherwise the file name alone. Null when the document has no name.
 */
fun openFileLine(name: String, uri: Uri?, path: NoteFolderPath?): OpenFileLine? =
    name.takeIf { it.isNotBlank() }?.let { OpenFileLine(it, path?.takeIf { p -> p.uri == uri }?.names.orEmpty()) }

/** Where a note opened by a followed link lands: its [heading] (raw fragment), or the top when null (K3). */
data class NoteLanding(val uri: Uri, val heading: String?)

/**
 * M-160: following links between notes in an added folder (notes-folder spec, section 4), Back to the
 * note a link was followed from (K10), and the K13/K14 bare links of the open note.
 *
 * The screen reports every document it shows through [onDocumentShown]; that is how a followed link
 * is confirmed as opened, and how any other change of document ends the link history.
 */
class NoteLinksViewModel(private val repo: NoteLinkRepository) : ViewModel() {

    private val _events = Channel<NoteLinkEvent>(Channel.BUFFERED)
    val events: Flow<NoteLinkEvent> = _events.receiveAsFlow()

    /** The added folder the open note is in; null when none, or not known yet (today's behaviour). */
    private val _folder = MutableStateFlow<NoteFolderContext?>(null)
    val folder: StateFlow<NoteFolderContext?> = _folder.asStateFlow()

    private val _folderPath = MutableStateFlow(NoteFolderPath(null, emptyList()))
    val folderPath: StateFlow<NoteFolderPath> = _folderPath.asStateFlow()

    private val _bareLinks = MutableStateFlow(NoteBareLinks(null, emptySet()))
    val bareLinks: StateFlow<NoteBareLinks> = _bareLinks.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _landing = MutableStateFlow<NoteLanding?>(null)
    val landing: StateFlow<NoteLanding?> = _landing.asStateFlow()

    private val history = LinkHistory<Uri>()

    // The open the screen was asked for and has not shown yet: a followed link, or Back.
    private var pending: Pending? = null
    private var shownUri: Uri? = null
    private var shownContent: String? = null
    private var folderJob: Job? = null
    private var bareJob: Job? = null

    /**
     * A tap on a scheme-less link in the note [currentUri]. True when it is taken here: the note is in
     * an added folder and the link is a path ([NoteLinkPaths.isNotePath]); false keeps today's behaviour.
     */
    fun follow(currentUri: Uri, destination: String): Boolean {
        if (_folder.value == null || !NoteLinkPaths.isNotePath(destination)) return false
        viewModelScope.launch {
            val link = repo.resolve(currentUri, destination)
            val event = when {
                // The note already shown: no reopen, just its heading, or the top (K3).
                link is ResolvedLink.OpenNote && repo.sameDocument(currentUri, link.uri) -> {
                    _landing.value = NoteLanding(currentUri, link.heading)
                    null
                }
                link is ResolvedLink.OpenNote -> {
                    pending = Pending(from = currentUri, to = link.uri, heading = link.heading, back = false)
                    NoteLinkEvent.Open(link.uri, link.heading)
                }
                link is ResolvedLink.OpenFile -> NoteLinkEvent.OpenExternal(link.uri, link.mimeType)
                link is ResolvedLink.NotFound -> NoteLinkEvent.NotFound(link.name)
                else -> NoteLinkEvent.Fallback
            }
            event?.let { _events.send(it) }
        }
        return true
    }

    /** K10: the note Back returns to from [currentUri], or null when Back is not a link Back. */
    fun goBack(currentUri: Uri?): Uri? {
        if (currentUri == null) return null
        val from = history.peek(currentUri) ?: return null
        pending = Pending(from = currentUri, to = from, heading = null, back = true)
        return from
    }

    /** The unsaved-changes prompt was cancelled: the open asked for will not happen. */
    fun cancelPending() {
        pending = null
    }

    /** The reader landed the note opened by a link; a later rebuild (rotation) keeps the user's place. */
    fun onLanded() {
        _landing.value = null
    }

    /** Every document the screen shows, and every change of its text; null = nothing open. */
    fun onDocumentShown(uri: Uri?, content: String?) {
        if (uri != shownUri) {
            documentChanged(uri)
        } else if (content == shownContent) {
            return
        }
        shownContent = content
        recomputeBareLinks(uri, content)
    }

    private fun documentChanged(uri: Uri?) {
        val opened = pending?.takeIf { it.to == uri }
        pending = null
        when {
            opened == null -> history.clear()
            opened.back -> history.back()
            else -> history.push(opened.from, opened.to)
        }
        _landing.value = opened?.takeIf { !it.back }?.let { NoteLanding(it.to, it.heading) }
        shownUri = uri
        _canGoBack.value = history.canGoBack(uri)
        _folder.value = null
        _folderPath.value = NoteFolderPath(uri, emptyList())
        _bareLinks.value = NoteBareLinks(uri, emptySet())
        folderJob?.cancel()
        folderJob = uri?.let {
            viewModelScope.launch {
                val folder = repo.folderFor(it)
                _folder.value = folder
                if (folder != null) _folderPath.value = NoteFolderPath(it, repo.folderNames(folder))
            }
        }
    }

    private fun recomputeBareLinks(uri: Uri?, content: String?) {
        bareJob?.cancel()
        if (uri == null) {
            _bareLinks.value = NoteBareLinks(null, emptySet())
            return
        }
        // Outside the reader (the editor) there is no text to work from: keep this note's set, so
        // coming back to the reader finds its model still built with it (M-160).
        if (content == null) return
        bareJob = viewModelScope.launch {
            _bareLinks.value = NoteBareLinks(uri, repo.resolvableBareLinks(uri, content))
        }
    }

    private data class Pending(val from: Uri, val to: Uri, val heading: String?, val back: Boolean)

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteLinksViewModel(container.noteLinkRepository) as T
        }
    }
}
