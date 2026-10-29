// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.domain.folders.FolderEntry
import com.pilcrowmd.domain.folders.FolderIdentity
import com.pilcrowmd.domain.folders.FolderLabels
import com.pilcrowmd.domain.folders.NoteMark
import com.pilcrowmd.domain.folders.NoteMarks
import com.pilcrowmd.domain.folders.OwnSave
import com.pilcrowmd.domain.folders.SeenRecord
import com.pilcrowmd.domain.folders.shownOpenFolderIds
import com.pilcrowmd.repository.AddFolderResult
import com.pilcrowmd.repository.FolderListing
import com.pilcrowmd.repository.NotesFolder
import com.pilcrowmd.repository.NotesFolderRepository
import com.pilcrowmd.storage.NoteMarksStore
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.expandedKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One row of the folder list. [suffix] is the grey text after the name (P15). [markCount] is the
 * number of marked notes in the folder (M7), known only for the ticked folder, null for the others.
 */
data class FolderRowUi(
    val treeUri: Uri,
    val name: String,
    val suffix: String?,
    val available: Boolean,
    val selected: Boolean,
    val markCount: Int? = null,
)

sealed interface ListingUi {
    /** F6: no folder added yet. */
    data object NoFolders : ListingUi

    data object Loading : ListingUi

    /** F4: the ticked folder lost its grant or cannot be read; offer Find again and Remove. */
    data object Unavailable : ListingUi

    /**
     * The ticked folder's notes; [expanded] holds the document IDs of its open subfolders (L2).
     * [marks] are its notes' New and Updated marks by document ID, [counts] the marked notes below
     * each folder, the ticked one included (M7), and [rows] what the list shows, in order.
     */
    data class Loaded(
        val root: FolderEntry,
        val expanded: Set<String>,
        val marks: Map<String, NoteMark> = emptyMap(),
        val counts: Map<String, Int> = emptyMap(),
        val rows: List<FileRowUi> = emptyList(),
    ) : ListingUi
}

enum class FolderMessage { ALREADY_LISTED, NOT_A_FOLDER, PERMISSION_REFUSED }

/** [refreshing] is true from a pull-to-refresh until the listing it asked for is shown (L6). */
data class NotesFoldersUiState(
    val folders: List<FolderRowUi>,
    val listing: ListingUi,
    val message: FolderMessage?,
    val refreshing: Boolean = false,
)

/**
 * M-162b: the notes-folder list (spec sections 2 and 3). One folder has the tick; its notes are
 * listed, and listed again on refresh and, while watching, when the folder changes (L6). Each
 * listing updates the New and Updated marks (section 3b), dated by [clock].
 */
@OptIn(FlowPreview::class)
// One function per event of the Files screen; built from its sources and the timings tests shorten.
@Suppress("TooManyFunctions", "LongParameterList")
class NotesFoldersViewModel(
    private val repo: NotesFolderRepository,
    private val store: NotesFolderStore,
    marksStore: NoteMarksStore,
    private val clock: () -> Long = System::currentTimeMillis,
    // Where marks and rows are worked out: never the main thread (L7). Injected for tests.
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // M4: each document the app has just saved in place.
    savedDocuments: Flow<Uri> = emptyFlow(),
    // S8-A52: each save in place as it starts, and how often and how long a saved note is read again.
    saveStarts: Flow<Uri> = emptyFlow(),
    private val recheckMs: Long = OwnSave.RECHECK_MS,
    private val recheckWindowMs: Long = OwnSave.RECHECK_WINDOW_MS,
) : ViewModel() {

    private val listing = MutableStateFlow<Listing?>(null)
    private val listRequests = MutableStateFlow(0)
    private val message = MutableStateFlow<FolderMessage?>(null)
    private val pulled = MutableStateFlow(false)
    private val marks = NoteMarksKeeper(marksStore, clock, computeDispatcher)
    private val expandedEdits = Mutex()
    private var watchJob: Job? = null
    private var recheckJob: Job? = null

    /** The ticked folder, only while it is in the list. */
    private val selected: StateFlow<Uri?> = combine(repo.folders, store.selectedFolder) { folders, uri ->
        uri?.takeIf { folders.any { it.treeUri == uri } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<NotesFoldersUiState> = combine(
        repo.folders,
        selected,
        store.expanded,
        combine(listing, marks.shown, ::Pair),
        combine(message, pulled, ::Pair),
    ) { folders, uri, expanded, (shown, folderMarks), (msg, refreshing) ->
        val listingUi = listingFor(folders, uri, expanded, shown, folderMarks, clock())
        val total = (listingUi as? ListingUi.Loaded)?.let { it.counts[it.root.documentId] }
        NotesFoldersUiState(rowsFor(folders, uri, total), listingUi, msg, refreshing)
    }.flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, NotesFoldersUiState(emptyList(), ListingUi.Loading, null))

    init {
        // M4: the app's own save sets the note's new baseline, so it never reads as Updated.
        // S8-A46: and the provider's later date for that save is taken in too (Drive sets it ~8 s on).
        viewModelScope.launch { savedDocuments.collect { noteSeen(it, ownSave = true) } }
        // S8-A52: a save that starts ends the re-reads after the one before; the two never overlap.
        viewModelScope.launch { saveStarts.collect { stopSaveRecheck() } }
        // F6/P12: the tick stays on a listed folder. A stored tick that is no longer listed (or none
        // at all) moves to the first folder; with no folders left there is no tick.
        viewModelScope.launch {
            combine(repo.folders, store.selectedFolder) { folders, uri -> folders to uri }.collect { (folders, uri) ->
                val tick = when {
                    folders.isEmpty() -> null
                    folders.any { it.treeUri == uri } -> uri
                    else -> folders.first().treeUri
                }
                if (tick != uri) store.setSelectedFolder(tick)
            }
        }
        viewModelScope.launch {
            combine(selected, listRequests, ::Pair).collectLatest { (uri, requests) ->
                // Nothing is listed until the Files screen first asks (refresh): for a cloud folder a
                // listing may go over the network, and nothing runs that the user did not ask for
                // (L6, no polling). After that, a new tick is listed at once.
                if (requests == 0) return@collectLatest
                if (uri == null) {
                    pulled.value = false
                    return@collectLatest
                }
                val generation = marks.generationOf(uri)
                val result = repo.list(uri)
                // A listing overtaken by a newer one (the tick moved, a refresh) is never shown, even
                // when the repository finished it regardless.
                currentCoroutineContext().ensureActive()
                if (result is FolderListing.Listed) marks.listed(uri, result.root, generation)
                listing.value = Listing(uri, result)
                pulled.value = false
            }
        }
    }

    /** The folder picker's answer (F1). */
    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            when (val result = repo.add(uri)) {
                is AddFolderResult.Added -> {
                    // P16: a new folder shows with its subfolders closed, even one added before.
                    editExpanded { withoutFolder(it, uri) }
                    store.setSelectedFolder(result.folder.treeUri)
                }
                is AddFolderResult.AlreadyListed -> {
                    store.setSelectedFolder(result.folder.treeUri)
                    message.value = FolderMessage.ALREADY_LISTED
                }
                else -> message.value = messageFor(result)
            }
        }
    }

    fun select(uri: Uri) {
        viewModelScope.launch { store.setSelectedFolder(uri) }
    }

    /** F5/P12: the tick moves to the first remaining folder (see init). */
    fun remove(uri: Uri) {
        viewModelScope.launch {
            repo.remove(uri)
            marks.drop(uri)
            editExpanded { withoutFolder(it, uri) }
        }
    }

    /** F4/P11: the unavailable folder [old] is found again as [picked]. */
    fun findAgain(old: Uri, picked: Uri) {
        viewModelScope.launch {
            when (val result = repo.findAgain(old, picked)) {
                is AddFolderResult.Added -> {
                    // The old folder's subfolder IDs mean nothing in the one found again, and its
                    // notes start from a fresh baseline (M3).
                    if (old != picked) editExpanded { withoutFolder(it, old) }
                    marks.drop(old)
                    refresh()
                }
                else -> message.value = messageFor(result)
            }
        }
    }

    /** L2: opens or closes a subfolder of the ticked folder, remembered across restarts. */
    fun toggleExpanded(documentId: String) {
        val uri = selected.value ?: return
        val key = expandedKey(uri, documentId)
        viewModelScope.launch { editExpanded { keys -> if (key in keys) keys - key else keys + key } }
    }

    /**
     * L6: list the ticked folder again (on return to the app, on opening the list, on pull-to-refresh).
     * [pulled]: asked for by a pull-to-refresh, which shows its indicator until the listing is shown.
     */
    fun refresh(pulled: Boolean = false) {
        if (pulled) this.pulled.value = true
        listRequests.update { it + 1 }
    }

    /** M5: every note of the ticked folder and its subfolders seen, as it was listed last. */
    fun markAllSeen() {
        val uri = selected.value ?: return
        val shown = listing.value?.takeIf { it.treeUri == uri } ?: return
        val root = (shown.result as? FolderListing.Listed)?.root ?: return
        viewModelScope.launch { marks.allSeen(uri, root) }
    }

    /**
     * M4, M5: the reader shows [documentUri] (opened from the list, from Recents or from another app),
     * or has just saved it. If it is a note of an added folder, its mark goes and its current date
     * and size become the baseline, so the app's own save never reads as Updated.
     */
    fun onNoteSeen(documentUri: Uri) = noteSeen(documentUri, ownSave = false)

    private fun noteSeen(documentUri: Uri, ownSave: Boolean) {
        viewModelScope.launch {
            val id = repo.documentIdOf(documentUri) ?: return@launch
            val holding = repo.folders.first()
                .map { it.treeUri }
                .filter { it.authority == documentUri.authority && marks.knows(it, id) }
            if (holding.isEmpty()) return@launch
            val stats = repo.noteStats(documentUri)
            holding.forEach { marks.seen(it, id, stats, ownSave) }
            if (ownSave) recheckSave(documentUri, id, holding)
        }
    }

    /**
     * S8-A52: Drive gives a saved note's new date seconds after the save, and a listing takes it in
     * only within [OwnSave.SETTLE_MS], so a first return to the list after that marked the app's own
     * save Updated. The note is read again every [recheckMs] for [recheckWindowMs] until the date is
     * in. Ends at the next save and when the app goes to the background ([stopSaveRecheck]). If the
     * app is ended meanwhile, only a listing within the 2 min can still take the date in (accepted).
     */
    private fun recheckSave(documentUri: Uri, id: String, holding: List<Uri>) {
        recheckJob?.cancel()
        recheckJob = viewModelScope.launch {
            repeat((recheckWindowMs / recheckMs).toInt()) {
                delay(recheckMs)
                val stats = repo.noteStats(documentUri)
                val waiting = holding.map { marks.recheck(it, id, stats) }
                if (waiting.none { it }) return@launch
            }
        }
    }

    /** The document ID of [documentUri] when it may be a note of the ticked folder, for the highlight. */
    fun openNoteId(documentUri: Uri?): String? {
        val tree = selected.value ?: return null
        if (documentUri == null || documentUri.authority != tree.authority) return null
        return repo.documentIdOf(documentUri)
    }

    /** The URI the reader opens for the note [documentId] of the ticked folder. */
    fun noteUri(documentId: String): Uri? = selected.value?.let { repo.noteUri(it, documentId) }

    fun messageShown() {
        message.value = null
    }

    /**
     * L6: while the list is on screen, a change in the ticked folder or a subfolder shown open lists it
     * again, 300 ms after the last change. Watching restarts when the tick or the open subfolders change.
     */
    fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            combine(selected, listing, store.expanded) { uri, shown, expanded -> watchTarget(uri, shown, expanded) }
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target == null) return@collectLatest
                    repo.changes(target.treeUri, target.folderIds).debounce(WATCH_DEBOUNCE_MS).collect { refresh() }
                }
        }
    }

    /** S8-A52: the app went to the background, or another save started: no more re-reads of the last save. */
    fun stopSaveRecheck() {
        recheckJob?.cancel()
        recheckJob = null
    }

    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    private suspend fun editExpanded(change: (Set<String>) -> Set<String>) {
        expandedEdits.withLock { store.setExpanded(change(store.expanded.first())) }
    }

    companion object {
        private const val WATCH_DEBOUNCE_MS = 300L

        fun provideFactory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NotesFoldersViewModel(
                repo = container.notesFolderRepository,
                store = container.notesFolderStore,
                marksStore = container.noteMarksStore,
                savedDocuments = container.savedDocuments,
                saveStarts = container.saveStarts,
            ) as T
        }
    }
}

/** A listing, and the folder it is of: a listing of a folder no longer ticked is never shown. */
private data class Listing(val treeUri: Uri, val result: FolderListing)

/** What to watch: the ticked folder's root and its subfolders shown open (section 6, L6). */
private data class WatchTarget(val treeUri: Uri, val folderIds: Set<String>)

// Pure helpers, top-level so the ViewModel keeps to its events (detekt TooManyFunctions).

private fun watchTarget(uri: Uri?, shown: Listing?, expanded: Set<String>): WatchTarget? {
    if (uri == null || shown?.treeUri != uri) return null
    val root = (shown.result as? FolderListing.Listed)?.root ?: return null
    return WatchTarget(uri, shownOpenFolderIds(root, expandedIds(uri, expanded)))
}

/** [markTotal]: the ticked folder's marked notes (M7), when it is listed. */
private fun rowsFor(folders: List<NotesFolder>, selected: Uri?, markTotal: Int?): List<FolderRowUi> {
    val suffixes = FolderLabels.labels(folders.map { FolderIdentity(it.name, it.parentHint) })
    return folders.mapIndexed { i, folder ->
        val ticked = folder.treeUri == selected
        FolderRowUi(folder.treeUri, folder.name, suffixes[i], folder.available, ticked, markTotal.takeIf { ticked })
    }
}

@Suppress("LongParameterList") // the listing, and each thing it is shown with
private fun listingFor(
    folders: List<NotesFolder>,
    uri: Uri?,
    expanded: Set<String>,
    shown: Listing?,
    folderMarks: Map<Uri, Map<String, SeenRecord>>,
    now: Long,
): ListingUi {
    if (folders.isEmpty()) return ListingUi.NoFolders
    if (uri == null || shown?.treeUri != uri) return ListingUi.Loading
    return when (val result = shown.result) {
        is FolderListing.Listed -> {
            val open = expandedIds(uri, expanded)
            val records = folderMarks[uri].orEmpty()
            val marks = records.mapNotNull { (id, record) -> NoteMarks.markOf(record)?.let { id to it } }.toMap()
            val counts = NoteMarks.markedCounts(result.root, marks)
            ListingUi.Loaded(result.root, open, marks, counts, fileTreeRows(result.root, open, marks, counts, now))
        }
        FolderListing.Unavailable -> ListingUi.Unavailable
    }
}

private fun expandedIds(uri: Uri, expanded: Set<String>): Set<String> {
    val prefix = expandedKey(uri, "")
    return expanded.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
}

private fun messageFor(result: AddFolderResult): FolderMessage? = when (result) {
    is AddFolderResult.Added -> null
    is AddFolderResult.AlreadyListed -> FolderMessage.ALREADY_LISTED
    AddFolderResult.NotAFolder -> FolderMessage.NOT_A_FOLDER
    AddFolderResult.PermissionRefused -> FolderMessage.PERMISSION_REFUSED
}

/** [keys] without the open-subfolder keys of the folder [treeUri]. */
private fun withoutFolder(keys: Set<String>, treeUri: Uri): Set<String> {
    val prefix = expandedKey(treeUri, "")
    return keys.filterNot { it.startsWith(prefix) }.toSet()
}
