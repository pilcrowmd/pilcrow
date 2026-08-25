// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.domain.model.DocumentKind
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.rendering.PlainTextBlocks
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.RecentFile
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI model for a recent file row, including whether its permission is still held. */
data class RecentFileUi(val uri: Uri, val displayName: String, val lastOpened: Long, val available: Boolean)

/** A heading-jump request. seq increments per tap so repeats to the same block still fire. */
data class HeadingJump(val position: Int, val seq: Int)

/**
 * State for markdown reading/editing.
 * Modeled as collection-capable (v1 holds one file, vNext can extend to tabs/multiple).
 */
data class Document(
    val uri: Uri,
    val content: String,
    val dirty: Boolean = false,
    // SAF display name (e.g. "notes.md"); drives the default PDF export filename. Empty until
    // resolved (intent/cold paths still route through loadFile, which populates it).
    val displayName: String = "",
)

/**
 * PDF export state for UI feedback.
 */
sealed class ExportState {
    object Idle : ExportState()
    data class InProgress(val progress: Int = 0) : ExportState() // 0-100 for future progress UI
    data class Success(val message: String = "PDF exported successfully") : ExportState()
    data class Error(val errorMessage: String) : ExportState()
}

// The 7th constructor parameter is `cpuDispatcher`, a DEFAULTED test seam rather than a new
// dependency — it exists so a test can assert that the load path's CPU work actually leaves the main
// thread, which is otherwise unobservable. Suppressed here rather than added to the detekt
// baseline: the baseline carries inherited debt, and a suppression introduced by new code should be
// visible at the code it excuses. Revisit if an 8th parameter is ever proposed — at that point the
// dependencies want grouping, not another exemption.
//
// Scoped to the CONSTRUCTOR, not the class. detekt honours @Suppress hierarchically, so the
// class-level form this originally used also silenced LongParameterList for every function in the
// class body — including ones not written yet. Measured rather than assumed (a review of
// PR #61): a 7-parameter member function added as a probe was NOT reported with the annotation on
// the class, and WAS reported once it moved here, with the constructor itself still correctly
// exempt. A suppression that silences more than the violation it was written for is how a rule
// quietly stops applying.
class MarkdownViewModel
@Suppress("LongParameterList")
constructor(
    private val repository: FileRepository,
    private val storage: StorageManager,
    private val parseHeadingsUseCase: ParseMarkdownHeadingsUseCase,
    private val searchUseCase: SearchMarkdownUseCase,
    private val pdfExporter: PdfExporter,
    val appInfo: com.pilcrowmd.di.AppInfo,
    /**
     * Where [loadDocument]'s CPU work runs. Injectable ONLY so a test can assert that work leaves
     * the main thread (Play Vitals ANR, 1.0.3) — production always uses the default. Defaulted, so
     * `provideFactory` and the AppContainer are untouched.
     */
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : ViewModel() {
    // v1 holds single document, but the structure allows collection.
    private val _currentDocument = MutableStateFlow<Document?>(null)
    val currentDocument: StateFlow<Document?> = _currentDocument.asStateFlow()

    // How the reader pane renders the current document: extension default, overridable
    // per file. Re-derived on EVERY document-identity change (load + Save-As adoption).
    private val _renderMode = MutableStateFlow(RenderMode.MARKDOWN)
    val renderMode: StateFlow<RenderMode> = _renderMode.asStateFlow()

    // True iff the current document is a `.txt` — gates the "View as Markdown" overflow toggle.
    private val _plainToggleAvailable = MutableStateFlow(false)
    val plainToggleAvailable: StateFlow<Boolean> = _plainToggleAvailable.asStateFlow()

    /**
     * Flip the reader between plain and Markdown rendering for the current document and persist
     * the choice per file (explicit in both directions). Viewing-only: touches
     * render state and the override store, never the document bytes or the save path.
     */
    fun toggleRenderMode() {
        viewModelScope.launch {
            val doc = _currentDocument.value ?: return@launch
            val next = if (_renderMode.value == RenderMode.PLAIN) RenderMode.MARKDOWN else RenderMode.PLAIN
            _renderMode.emit(next)
            storage.setRenderModeOverride(doc.uri, next)
            refreshHeadings(doc.content)
            refreshActiveSearch()
        }
    }

    /**
     * Re-run the active search after the render tree changed (mode toggle / Save-As
     * re-derivation): matches carry adapter positions and ordinals of the OLD tree and would
     * highlight or jump wrongly. No-op when no query is active; resets the focus
     * to the first match like any fresh query.
     */
    private fun refreshActiveSearch() {
        val query = _searchQuery.value
        if (query.isNotEmpty()) updateSearchQuery(query)
    }

    /**
     * Derive the render mode for a document identity: the user's remembered per-file override
     * wins; otherwise the extension default. Called from BOTH identity-assignment sites
     * ([loadDocument] and [saveActiveDocumentAs] adoption) so the state can never desync from
     * the document (a review finding).
     */
    private suspend fun applyDerivedRenderMode(uri: Uri, displayName: String) {
        val kind = DocumentKind.fromDisplayName(displayName)
        _plainToggleAvailable.emit(kind == DocumentKind.PLAIN_TEXT)
        val default = if (kind == DocumentKind.PLAIN_TEXT) RenderMode.PLAIN else RenderMode.MARKDOWN
        _renderMode.emit(storage.getRenderModeOverride(uri) ?: default)
    }

    /** Headings feed the TOC drawer — a plain-text document has none. */
    private suspend fun refreshHeadings(content: String) {
        val headings = if (_renderMode.value == RenderMode.PLAIN) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { parseHeadingsUseCase.extractHeadings(content) }
        }
        _headings.emit(headings)
    }

    // Transient = the open document holds no persisted *write* grant (e.g. opened read-only via
    // "Open with"), so an in-place save would fail. Derived from the repository at load/adopt time;
    // drives the transient banner and routes Save → Save-As. false when no
    // document is open.
    private val _transient = MutableStateFlow(false)
    val transient: StateFlow<Boolean> = _transient.asStateFlow()

    // Stranded WAL slots (the escape hatch): saves the WAL could not commit
    // because the target became permanently inaccessible. 1..N independent of the open document.
    // The list backs both the dialog and the passive indicator; never auto-discarded.
    private val _strandedSlots = MutableStateFlow<List<StrandedSlot>>(emptyList())
    val strandedSlots: StateFlow<List<StrandedSlot>> = _strandedSlots.asStateFlow()

    // Whether the stranded-slot dialog is shown. Auto-opened ONCE on a cold launcher start with
    // slots present (not on an intent open / onNewIntent / after dismissal) — the passive indicator
    // (driven by [strandedSlots] being non-empty) opens it on tap on every other path.
    private val _strandedDialogVisible = MutableStateFlow(false)
    val strandedDialogVisible: StateFlow<Boolean> = _strandedDialogVisible.asStateFlow()

    // One-shot guard so the cold-start auto-pop is evaluated once per process (survives the
    // Activity/composition recreate that re-runs the triggering LaunchedEffect on rotation).
    private var strandedStartEvaluated = false

    // Baseline content for dirty detection. When a file loads, this captures the original.
    // updateContent computes dirty = (newContent != originalContent). On save success, we update
    // this to the saved content so type-then-undo also clears dirty.
    private var originalContent: String = ""

    // Mode toggle state
    private val _mode = MutableStateFlow<ViewMode>(ViewMode.READER)
    val mode: StateFlow<ViewMode> = _mode.asStateFlow()

    // Line-ending format preservation (Safeguard 2).
    // Tracks whether the file uses CRLF (\r\n) or LF (\n) so save can restore the original format.
    private val _lineEnding = MutableStateFlow("LF")
    val lineEnding: StateFlow<String> = _lineEnding.asStateFlow()

    // Scroll position preservation: save position when toggling modes.
    // previewScroll: reader-mode anchor (first-visible block + intra-block offset)
    // editorScroll: editor/source-mode absolute pixel offset (Sora owns its own scroller)
    private val _previewScroll = MutableStateFlow(ScrollAnchor())
    val previewScroll: StateFlow<ScrollAnchor> = _previewScroll.asStateFlow()

    private val _editorScroll = MutableStateFlow(0)
    val editorScroll: StateFlow<Int> = _editorScroll.asStateFlow()

    // Editor caret offset, hoisted so it survives the editor leaving/re-entering composition on a
    // mode toggle (otherwise the cursor jumped to the top of the file each time).
    private val _editorCursor = MutableStateFlow(0)
    val editorCursor: StateFlow<Int> = _editorCursor.asStateFlow()

    // In-document search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMatches = MutableStateFlow<List<SearchMatch>>(emptyList())
    val searchMatches: StateFlow<List<SearchMatch>> = _searchMatches.asStateFlow()

    private val _currentMatchIndex = MutableStateFlow(0)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    private val _searchVisible = MutableStateFlow(false)
    val searchVisible: StateFlow<Boolean> = _searchVisible.asStateFlow()

    // Heading table-of-contents navigation
    private val _headings = MutableStateFlow<List<HeadingNode>>(emptyList())
    val headings: StateFlow<List<HeadingNode>> = _headings.asStateFlow()

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible.asStateFlow()

    // Each tap carries an incrementing seq so tapping the SAME heading twice (after scrolling
    // away) still fires — a plain position StateFlow would dedupe the repeat emission.
    private var headingJumpSeq = 0
    private val _headingJump = MutableStateFlow<HeadingJump?>(null)
    val headingJump: StateFlow<HeadingJump?> = _headingJump.asStateFlow()

    // Per-file scroll-position memory (keyed by document URI)
    private val _fileScrollPositions = mutableMapOf<String, Int>()

    // A file arriving from an intent while another dirty file is open is held here
    // until the user resolves the unsaved-changes prompt (Save/Discard). null = no pending open.
    private val _pendingOpenUri = MutableStateFlow<Uri?>(null)
    val pendingOpenUri: StateFlow<Uri?> = _pendingOpenUri.asStateFlow()

    // File I/O state
    private val _fileLoadState = MutableStateFlow<FileLoadState>(FileLoadState.Idle)
    val fileLoadState: StateFlow<FileLoadState> = _fileLoadState.asStateFlow()

    // Preference flows (from storage)
    val lineNumbersEnabled: StateFlow<Boolean> = storage.lineNumbersEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Font scales (0.85–1.6, persisted in DataStore).
    // Preview and Edit are independent app-wide prefs.
    val previewFontScale: StateFlow<Float> = storage.previewFontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val editorFontScale: StateFlow<Float> = storage.editorFontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    // Selected font set id (drives reading + mono typefaces app-wide).
    val fontSetId: StateFlow<String> = storage.fontSetId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "source")

    // Opt-in Mermaid cloud rendering (default off).
    val mermaidCloudEnabled: StateFlow<Boolean> = storage.mermaidCloudEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Theme mode selection (Dark or Light). Persisted in DataStore.
    val themeMode: StateFlow<ThemeMode> = storage.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    // Recent files, annotated with current permission availability.
    val recentFiles: StateFlow<List<RecentFileUi>> = storage.recentFiles
        .map { list ->
            list.map {
                RecentFileUi(it.uri, it.displayName, it.lastOpened, repository.hasPersistedPermission(it.uri))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // PDF export state for progress/error UI feedback
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    init {
        // Restore the last-opened file ONCE on startup.
        // This must NOT keep collecting lastFileUri. DataStore re-emits the whole
        // Preferences on any write (e.g. saving a scroll position when switching to Reader),
        // so a perpetual collector would call loadFile again with the same URI, reload from
        // disk, and silently discard the user's unsaved edits (Safeguard 1/2). first() reads
        // the current value once and stops.
        viewModelScope.launch {
            val lastUri = storage.lastFileUri.first()
            if (lastUri != null) {
                loadFile(lastUri)
            }
        }
    }

    fun loadFile(uri: Uri) {
        viewModelScope.launch { loadDocument(uri) }
    }

    /**
     * Open a file chosen via the SAF picker. Persists read/write **before** loading so the transient
     * check inside [loadDocument] reflects the just-granted write permission — a separate
     * fire-and-forget [takePermission] would race the load and could momentarily mis-flag a writable
     * file as transient. Best-effort: a provider that rejects the persist simply leaves the file
     * transient (which is then correct), so the result is ignored and never blocks the load.
     */
    fun openPickedFile(uri: Uri) {
        viewModelScope.launch {
            repository.takePersistableUriPermission(uri)
            loadDocument(uri)
        }
    }

    /** The results of the load-time CPU pass, carried back to Main as one value. */
    private data class PreparedLoad(val lineEnding: String, val normalized: String)

    private suspend fun loadDocument(uri: Uri) {
        _fileLoadState.emit(FileLoadState.Loading)
        val result = repository.readFile(uri)
        result
            .onSuccess { content ->
                // EVERY per-character pass over the document happens here, off the main thread.
                // `readFile` is `withContext(Dispatchers.IO)`, so it RETURNS to this coroutine's
                // context — `viewModelScope` = Dispatchers.Main.immediate — and until 1.0.4 that
                // meant the whole block below ran on Main. On a large file that froze the UI for
                // minutes and Play Vitals recorded it as an ANR (Pixel 6a, 1.0.3).
                //
                // Both passes are cheap now, but they are grouped into ONE hop on purpose: the next
                // expensive thing added to the load path should land on this side of the boundary by
                // default, not on Main. Only the results cross back.
                //
                // Detect and remember the original line-ending format (CRLF vs LF).
                // EditText normalizes \r\n → \n, so we detect here and restore on save.
                //
                // Normalize to LF for the in-memory model + editor (Sora/EditText work in LF). The
                // original line ending is restored on save via applyLineEnding(). Keeping content,
                // originalContent, and the editor all in LF avoids false-dirty and setText loops for
                // CRLF files (otherwise the LF editor text never equals the CRLF in-memory content,
                // so dirty could never clear and the update block would re-setText every recomposition).
                val prepared = withContext(cpuDispatcher) {
                    PreparedLoad(
                        lineEnding = detectLineEnding(content),
                        normalized = content.replace("\r\n", "\n"),
                    )
                }
                _lineEnding.emit(prepared.lineEnding)
                val normalized = prepared.normalized

                // Set baseline for dirty detection (type-then-undo clears dirty). Stays on Main:
                // the dirty check reads it from Main, so keeping the write here avoids any
                // cross-thread visibility question.
                originalContent = normalized

                // Resolve the SAF display name once and reuse it for both the open document
                // (export filename) and the recents entry below.
                val displayName = repository.displayName(uri)
                _currentDocument.emit(
                    Document(uri = uri, content = normalized, dirty = false, displayName = displayName),
                )
                // Transient iff we hold no persisted write grant for this URI (read-only "Open with").
                // The permission lookup is a synchronous ContentResolver/binder IPC — cheap in the
                // common case, unbounded when the providing app is slow or cold-starting — so it is
                // NOT left on Main either. IO rather than [cpuDispatcher]: it blocks, it does not compute.
                val writable = withContext(Dispatchers.IO) { repository.hasPersistedWritePermission(uri) }
                _transient.emit(!writable)
                _editorCursor.value = 0 // new file starts at the top

                // Render mode from the document identity (extension default ?: per-file override),
                // then headings for the TOC — gated to empty in plain mode.
                applyDerivedRenderMode(uri, displayName)
                refreshHeadings(content)

                // Restore saved scroll anchor for this file.
                val savedScroll = storage.getScrollPosition(uri)
                _previewScroll.emit(savedScroll)

                _fileLoadState.emit(FileLoadState.Success)
                // Persist as last file + record in recents.
                storage.saveLastFileUri(uri)
                storage.addRecent(RecentFile(uri, displayName, System.currentTimeMillis()))
            }
            .onFailure { error ->
                _fileLoadState.emit(FileLoadState.Error(error.message ?: "Unknown error"))
            }
    }

    /**
     * Entry point for a file opened from an intent (file manager / share sheet),
     * cold or warm. If the same file is already open it's a no-op. If a *different* file is
     * open with unsaved edits, the open is deferred behind a Save/Discard prompt
     * (pendingOpenUri) so warm-switching can't silently drop edits (Safeguard 1). Otherwise
     * it loads immediately.
     */
    fun openFromIntent(uri: Uri) {
        val current = _currentDocument.value
        when {
            current?.uri == uri -> return // already viewing this file
            current?.dirty == true -> _pendingOpenUri.value = uri // confirm before discarding
            else -> loadFile(uri)
        }
    }

    /** Dismiss the pending-open prompt, keeping the current file (cancel the switch). */
    fun cancelPendingOpen() {
        _pendingOpenUri.value = null
    }

    /** Discard the current file's unsaved edits and open the pending intent file. */
    fun discardAndOpenPending() {
        val uri = _pendingOpenUri.value ?: return
        _pendingOpenUri.value = null
        loadFile(uri)
    }

    /**
     * Save the current file, then open the pending intent file — sequentially, so the save
     * completes first. A failed save aborts the switch and surfaces the error, keeping both
     * the current file and the pending request intact (Safeguard 1).
     */
    fun saveAndOpenPending() {
        val uri = _pendingOpenUri.value ?: return
        viewModelScope.launch {
            if (_fileLoadState.value is FileLoadState.Saving) return@launch // no concurrent save
            val doc = _currentDocument.value
            if (doc != null && doc.dirty) {
                _fileLoadState.emit(FileLoadState.Saving)
                val result = repository.saveFile(doc.uri, contentForDisk(doc))
                if (result.isFailure) {
                    _fileLoadState.emit(
                        FileLoadState.SaveError(result.exceptionOrNull()?.message ?: "Unknown error"),
                    )
                    return@launch // keep current file + pending prompt; do not lose data
                }
                _currentDocument.update { it?.copy(dirty = false) }
            }
            _pendingOpenUri.value = null
            loadFile(uri)
        }
    }

    /**
     * Close the current file and return to the welcome screen (privacy).
     * Clears the in-memory document and the persisted last-file URI so the app
     * does not auto-reopen it on next launch. Unsaved edits are discarded (explicit-save-only).
     */
    fun closeFile() {
        viewModelScope.launch {
            storage.clearLastFileUri()
            _currentDocument.emit(null)
            _transient.emit(false)
            _mode.emit(ViewMode.READER)
            _previewScroll.emit(ScrollAnchor())
            _editorScroll.emit(0)
            _fileLoadState.emit(FileLoadState.Idle)
        }
    }

    /**
     * Save the current file, then close it — sequentially in one coroutine so the save
     * always reads the live document before it's cleared (unsaved-changes guard).
     * If the save fails the file stays open and a SaveError is surfaced; edits are never
     * dropped on a failed write (Safeguard 1).
     */
    fun saveAndClose() {
        viewModelScope.launch {
            if (_fileLoadState.value is FileLoadState.Saving) return@launch // no concurrent save
            val doc = _currentDocument.value ?: return@launch
            _fileLoadState.emit(FileLoadState.Saving)
            repository.saveFile(doc.uri, contentForDisk(doc))
                .onSuccess {
                    storage.clearLastFileUri()
                    _currentDocument.emit(null)
                    _transient.emit(false)
                    _mode.emit(ViewMode.READER)
                    _previewScroll.emit(ScrollAnchor())
                    _editorScroll.emit(0)
                    _fileLoadState.emit(FileLoadState.Idle)
                }
                .onFailure { error ->
                    _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                }
        }
    }

    /** Remove a single recent entry. */
    fun removeRecent(uri: Uri) {
        viewModelScope.launch { storage.removeRecent(uri) }
    }

    /** Clear the entire recents list. */
    fun clearRecents() {
        viewModelScope.launch { storage.clearRecents() }
    }

    fun updateContent(newContent: String) {
        viewModelScope.launch {
            _currentDocument.update { doc ->
                if (doc == null) return@update null
                // Compute dirty based on baseline comparison, not a flag.
                // If newContent matches originalContent, it's not dirty (handles type-then-undo).
                val isActuallyDirty = newContent != originalContent
                doc.copy(content = newContent, dirty = isActuallyDirty)
            }
        }
    }

    fun toggleMode() {
        viewModelScope.launch {
            _mode.update { current ->
                if (current == ViewMode.READER) ViewMode.EDITOR else ViewMode.READER
            }
        }
    }

    /** Set the view mode directly (used by the segmented Reader/Editor toggle). */
    fun setMode(mode: ViewMode) {
        viewModelScope.launch { _mode.update { mode } }
    }

    fun updatePreviewScroll(anchor: ScrollAnchor) {
        viewModelScope.launch {
            _previewScroll.emit(anchor)
            // Persist scroll anchor per file.
            val uri = _currentDocument.value?.uri ?: return@launch
            storage.saveScrollPosition(uri, anchor)
        }
    }

    fun updateEditorScroll(position: Int) {
        viewModelScope.launch {
            _editorScroll.emit(position)
        }
    }

    /** Remember the editor caret offset so a mode toggle restores it (not reset to 0). */
    fun updateEditorCursor(offset: Int) {
        _editorCursor.value = offset
    }

    fun updateSearchQuery(query: String) {
        viewModelScope.launch {
            _searchQuery.emit(query)
            if (query.isNotEmpty()) {
                val content = _currentDocument.value?.content ?: return@launch
                // Search parses + scans the whole document — run it off the main thread so a
                // large file (or a common term with thousands of hits) can't freeze the UI / ANR.
                // Plain mode: the visible text IS the literal source, so match over the
                // same chunk split the plain render uses (adapter positions align).
                val matches = withContext(Dispatchers.Default) {
                    if (_renderMode.value == RenderMode.PLAIN) {
                        searchUseCase.findPlainSearchMatches(PlainTextBlocks.chunkLiterals(content), query)
                    } else {
                        searchUseCase.findSearchMatches(content, query)
                    }
                }
                _searchMatches.emit(matches)
                _currentMatchIndex.emit(0) // Focus first match
            } else {
                _searchMatches.emit(emptyList())
                _currentMatchIndex.emit(0)
            }
        }
    }

    fun nextMatch() {
        viewModelScope.launch {
            val matches = _searchMatches.value
            if (matches.isEmpty()) return@launch
            val nextIdx = (_currentMatchIndex.value + 1) % matches.size
            _currentMatchIndex.emit(nextIdx)
        }
    }

    fun previousMatch() {
        viewModelScope.launch {
            val matches = _searchMatches.value
            if (matches.isEmpty()) return@launch
            val prevIdx = if (_currentMatchIndex.value == 0) {
                matches.size - 1
            } else {
                _currentMatchIndex.value - 1
            }
            _currentMatchIndex.emit(prevIdx)
        }
    }

    /** Get the character offset of the currently selected search match (for editor navigation). */
    fun getCurrentMatchOffset(): Int {
        val matches = _searchMatches.value
        val index = _currentMatchIndex.value
        return if (matches.isNotEmpty() && index < matches.size) {
            matches[index].startIndex
        } else {
            -1
        }
    }

    fun setSearchVisible(visible: Boolean) {
        viewModelScope.launch {
            _searchVisible.emit(visible)
            if (!visible) {
                // Exiting search clears its state. The preview observes the now-empty
                // matches and re-binds without highlight spans — no stale highlights linger.
                _searchQuery.emit("")
                _searchMatches.emit(emptyList())
                _currentMatchIndex.emit(0)
            }
        }
    }

    fun setTocVisible(visible: Boolean) {
        viewModelScope.launch { _tocVisible.emit(visible) }
    }

    fun jumpToHeading(heading: HeadingNode) {
        // Signal Preview to smooth-scroll to this block; seq makes repeat taps distinct.
        headingJumpSeq++
        _headingJump.value = HeadingJump(heading.adapterPosition, headingJumpSeq)
    }

    fun saveFile() {
        viewModelScope.launch {
            // Guard against a concurrent save (e.g. double-tap) — two simultaneous writes to the
            // same URI could interleave/truncate each other (Safeguard 1).
            if (_fileLoadState.value is FileLoadState.Saving) return@launch
            val doc = _currentDocument.value ?: return@launch

            _fileLoadState.emit(FileLoadState.Saving)

            // contentForDisk restores the original line ending before the write (Safeguard 2),
            // shared by every save path so a CRLF file is never silently converted to LF.
            repository.saveFile(doc.uri, contentForDisk(doc))
                .onSuccess {
                    // Baseline = the content we just persisted, in the editor's LF form (doc.content).
                    // The disk form (contentForDisk) may be CRLF; the editor/model always work in LF, so
                    // the baseline and the dirty comparison MUST use the LF doc.content, not the disk
                    // form — otherwise dirty would never clear for a CRLF file.
                    originalContent = doc.content
                    // Only clear the dirty flag if nothing was typed during the save — otherwise
                    // those newer edits would be silently marked saved and lost (Safeguard 2).
                    _currentDocument.update {
                        if (it != null && it.content == doc.content) it.copy(dirty = false) else it
                    }
                    _fileLoadState.emit(FileLoadState.SaveSuccess)
                }
                .onFailure { error ->
                    _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                }
        }
    }

    /**
     * Save the current document to a NEW user-chosen SAF location ("Save a copy" / Save-As), then
     * ADOPT that location as the document's identity. Serves a general Save-As and
     * gives a transient (read-only) document a real backing file.
     *
     * Reuses the unchanged crash-safe [FileRepository.saveFile] write and [contentForDisk]
     * line-ending fidelity (Safeguards 1 & 2). [targetUri] is a *different* URI from the source, so
     * the original file is never touched: a failed Save-As surfaces a SaveError and keeps the edits
     * in memory (a zero-byte created file may remain at the picked location — acceptable, no data
     * loss). [takePersistableUriPermission] is best-effort — a CreateDocument URI is already
     * SAF-persisted, so a failure must NOT abort the write (its Result is intentionally ignored).
     */
    fun saveActiveDocumentAs(targetUri: Uri) {
        viewModelScope.launch {
            // Reuse the concurrent-save guard: never start a Save-As while a save is in flight.
            if (_fileLoadState.value is FileLoadState.Saving) return@launch
            val doc = _currentDocument.value ?: return@launch
            _fileLoadState.emit(FileLoadState.Saving)

            // Best-effort persist; result ignored so a provider that rejects it can't abort the write.
            repository.takePersistableUriPermission(targetUri)

            repository.saveFile(targetUri, contentForDisk(doc))
                .onSuccess {
                    val displayName = repository.displayName(targetUri)
                    // Baseline = the LF content we persisted (contentForDisk may be CRLF; model is LF).
                    originalContent = doc.content
                    // Adopt the new identity. Preserve any edits typed during the write: keep the live
                    // content and only clear dirty if nothing changed since (mirrors saveFile, Safeguard 2).
                    _currentDocument.update { current ->
                        current?.copy(
                            uri = targetUri,
                            displayName = displayName,
                            dirty = current.content != doc.content,
                        )
                    }
                    // The adopted file is persistable+writable → recompute transient (clears the banner).
                    _transient.emit(!repository.hasPersistedWritePermission(targetUri))
                    // Surface success (and the "Saved" toast) before the recents/last-file persistence,
                    // which suspends on DataStore I/O — the outcome must not wait on it (mirrors loadFile,
                    // which persists last). The in-memory adoption above is what the session relies on.
                    _fileLoadState.emit(FileLoadState.SaveSuccess)
                    // Identity changed → re-derive the render mode for the NEW name/URI (a .txt
                    // saved-as .md must not stay stuck in plain). Sits with
                    // the other post-outcome persistence: it reads DataStore, and the save outcome
                    // above must not wait on that I/O.
                    applyDerivedRenderMode(targetUri, displayName)
                    refreshHeadings(doc.content)
                    refreshActiveSearch()
                    storage.saveLastFileUri(targetUri)
                    storage.addRecent(RecentFile(targetUri, displayName, System.currentTimeMillis()))
                }
                .onFailure { error ->
                    _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                }
        }
    }

    // ── Stranded WAL slots — escape hatch ─────────────────────────────────────────────

    /**
     * Evaluate stranded WAL slots once at process start. Detection ALWAYS runs (the query suspends
     * behind the journal lock until launch recovery completes; whatever remains is stranded). Only the
     * *presentation* is gated: auto-open the dialog only on a cold launcher start with slots present —
     * never when launched to open a specific file ([openingFileFromIntent]); the passive indicator
     * surfaces the slots on every other path. Idempotent per process (survives the rotation that
     * re-runs the triggering effect).
     */
    fun evaluateStrandedSlotsOnStart(openingFileFromIntent: Boolean) {
        if (strandedStartEvaluated) return
        strandedStartEvaluated = true
        viewModelScope.launch {
            val slots = repository.strandedSlots().getOrDefault(emptyList())
            _strandedSlots.value = slots
            if (slots.isNotEmpty() && !openingFileFromIntent) _strandedDialogVisible.value = true
        }
    }

    /** Re-read the stranded-slot list from the journal (after a rescue/discard). */
    private fun refreshStrandedSlots() {
        viewModelScope.launch {
            val slots = repository.strandedSlots().getOrDefault(emptyList())
            _strandedSlots.value = slots
            // Keep the dialog flag honest: once the last slot is resolved, close it (so a NEW slot
            // appearing later this session can't auto-reopen the dialog via a stale visible flag).
            if (slots.isEmpty()) _strandedDialogVisible.value = false
        }
    }

    /**
     * Rescue one stranded slot to a user-chosen [targetUri] (its "Save a copy"). Writes the slot's
     * raw bytes verbatim via the repository and, on success, the slot is discarded there; refreshes
     * the list. NEVER touches the open document. Best-effort persist of the new URI (ignored — must
     * not abort the rescue). On failure the slot is kept (surfaced via the refreshed list).
     */
    fun rescueStrandedSlot(targetUri: Uri, slotKey: String) {
        viewModelScope.launch {
            repository.takePersistableUriPermission(targetUri) // best-effort; result intentionally ignored
            repository.saveStrandedSlotToTarget(slotKey, targetUri)
                .onSuccess { _fileLoadState.emit(FileLoadState.SaveSuccess) }
                .onFailure { error -> _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error")) }
            refreshStrandedSlots()
        }
    }

    /** Explicitly discard one stranded slot at the user's request (the "Discard" action). */
    fun discardStrandedSlot(key: String) {
        viewModelScope.launch {
            repository.discardSlot(key)
            refreshStrandedSlots()
        }
    }

    /** Open the stranded-slot list (passive-indicator tap). */
    fun showStrandedDialog() {
        _strandedDialogVisible.value = true
    }

    /** Dismiss the stranded-slot dialog without acting — every slot stays intact (never auto-discarded). */
    fun dismissStrandedDialog() {
        _strandedDialogVisible.value = false
    }

    fun setLineNumbersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            storage.setLineNumbersEnabled(enabled)
        }
    }

    fun setPreviewFontScale(multiplier: Float) {
        viewModelScope.launch { storage.savePreviewFontScale(multiplier.coerceIn(0.85f, 1.6f)) }
    }

    fun setEditorFontScale(multiplier: Float) {
        viewModelScope.launch { storage.saveEditorFontScale(multiplier.coerceIn(0.85f, 1.6f)) }
    }

    fun setFontSet(id: String) {
        viewModelScope.launch { storage.saveFontSetId(id) }
    }

    fun setMermaidCloudEnabled(enabled: Boolean) {
        viewModelScope.launch { storage.setMermaidCloudEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { storage.setThemeMode(mode) }
    }

    fun takePermission(uri: Uri) {
        viewModelScope.launch {
            repository.takePersistableUriPermission(uri)
        }
    }

    /**
     * Detect the dominant line-ending format in the file.
     * Counts \r\n vs. \n occurrences and returns "CRLF" if CRLF is more common, "LF" otherwise.
     *
     * Limitation: Mixed line endings are normalized to the dominant style.
     * Per-line preservation is out of scope for v1 (acceptable per spec simplicity).
     *
     * **One pass, no regex — and that is a correctness fix, not a micro-optimisation.** This used to
     * be two `Regex(...).findAll(content).count()` scans. On Android those are not O(n): each match
     * re-enters ICU's `MatcherNative.setInput`, which re-copies the document, so the real cost is
     * proportional to BYTES x MATCHES. Play Vitals recorded it as an ANR on 1.0.3 — main thread
     * blocked in `findNext` -> `setInput` — and it was measured on device at 24.5 s for a 1.3 MB file
     * and 294 s for 3.3 MB. The loop below does 3.3 MB in 11 ms.
     *
     * **The cost was driven by LINE COUNT, not file size** (device UAT, S24+): two fixtures of
     * IDENTICAL byte size, 14,152 vs 75,934 lines, took ~15-20 s and >2 min respectively. A
     * line-oriented file — a log, an export, a dump — is the worst case, which is why a byte-only
     * size threshold is the wrong way to think about the old behaviour.
     *
     * **Both counts are still counted, deliberately.** Returning on the first match found would be
     * cheaper still and WRONG: the question is which ending DOMINATES, and a file that opens LF and
     * ends mostly CRLF must answer CRLF. Getting that wrong changes the bytes written back on save
     * (Safeguard 2), so the semantics are pinned by `MarkdownViewModelLineEndingTest` — including its
     * mixed-dominant pair — which this rewrite leaves untouched and green.
     *
     * Equivalence with the regex version holds for every input, including the ones worth naming:
     * empty string and a lone `\r` yield 0/0 and fall to "LF"; `\r\r\n` counts one CRLF; a `\n`
     * at index 0 counts as LF (the `i > 0` guard); and a tie returns "LF", matching `crlfCount > lfOnly`.
     *
     * `internal` rather than `private` ONLY so `MarkdownViewModelLineEndingScalingTest` can measure the
     * real function instead of a copy of it — a scaling test written against a duplicated loop would
     * pass forever no matter what production did.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun detectLineEnding(content: String): String {
        var crlf = 0
        var lf = 0
        for (i in content.indices) {
            if (content[i] == '\n') {
                if (i > 0 && content[i - 1] == '\r') crlf++ else lf++
            }
        }
        return if (crlf > lf) "CRLF" else "LF"
    }

    /**
     * The document's content as it must be written to disk: the editor/model's LF text with the
     * file's original line ending restored (Safeguard 2). Centralised so EVERY save path
     * (saveFile / saveAndClose / saveAndOpenPending) writes byte-identical content — a path that
     * passed raw doc.content would silently convert a CRLF file to LF.
     */
    private fun contentForDisk(doc: Document): String = applyLineEnding(doc.content, _lineEnding.value)

    /**
     * Apply the detected line-ending format before saving.
     * If format is "CRLF", converts any \n to \r\n (and any stray \r to \r\n, avoiding \r\r\n).
     * If format is "LF", leaves as-is (no conversion).
     *
     * Uses a careful regex replace to avoid double-conversion of existing \r\n:
     * The pattern \\r?\\n matches either \n (and replaces with \r\n) or existing \r\n (and replaces with \r\n).
     */
    private fun applyLineEnding(text: String, format: String): String {
        return if (format == "CRLF") {
            text.replace(Regex("""\r?\n"""), "\r\n")
        } else {
            text
        }
    }

    /**
     * Export the current preview to a PDF via SAF Uri (Dispatchers.IO).
     * Updates exportState with progress/error. Safeguard: atomic write (no partial files).
     *
     * @param uri The SAF CREATE_DOCUMENT Uri selected by the user
     */
    fun exportPdf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val markdownContent = _currentDocument.value?.content
            if (markdownContent == null) {
                _exportState.value = ExportState.Error("No file open")
                return@launch
            }
            _exportState.value = ExportState.InProgress()
            // A failed export (large/odd content, write error) must surface an error,
            // never crash. The atomic write deletes the partial file on failure.
            runCatching {
                pdfExporter.exportToUri(markdownContent, previewFontScale.value, uri, _renderMode.value)
            }.onSuccess {
                _exportState.value = ExportState.Success()
            }.onFailure { e ->
                _exportState.value = ExportState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Reset export state to Idle after the UI has shown its feedback. Called once the success
     * SnackBar is displayed so a recomposition can't re-trigger a duplicate message.
     */
    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    /**
     * Consume a one-shot save outcome ([FileLoadState.SaveSuccess] or [FileLoadState.SaveError]) once
     * the UI has shown its feedback (the "Saved" toast / the error SnackBar), so a later recomposition
     * or screen remount can't replay it. Only resets those two terminal outcomes → Idle, so a newer
     * Saving/Loading that began meanwhile is never clobbered.
     */
    fun resetSaveState() {
        _fileLoadState.update {
            if (it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError) FileLoadState.Idle else it
        }
    }

    // vNext: collection-capable means this could extend to:
    // fun openMultipleFiles(uris: List<Uri>): Result<Unit>
    // fun switchDocument(docId: String): Unit
    // fun closeDocument(docId: String): Result<Unit>

    companion object {
        /**
         * Factory method for injecting the ViewModel with dependencies from the AppContainer.
         * Returns a ViewModelProvider.Factory whose create() method instantiates MarkdownViewModel
         * with repository and storage from the container. The factory captures the container
         * as a closure variable, so dependencies are resolved at factory creation time (which
         * happens in the Composable on every recomposition), but the ViewModel itself is retained
         * by Compose's viewModel() so it survives recomposition and rotation.
         *
         * Usage:
         *   val container = (LocalContext.current.applicationContext as PilcrowApplication).container
         *   val viewModel: MarkdownViewModel = viewModel(factory = MarkdownViewModel.provideFactory(container))
         */
        fun provideFactory(container: com.pilcrowmd.di.AppContainer): androidx.lifecycle.ViewModelProvider.Factory {
            return object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MarkdownViewModel(
                        repository = container.fileRepository,
                        storage = container.storageManager,
                        parseHeadingsUseCase = container.parseMarkdownHeadingsUseCase,
                        searchUseCase = container.searchMarkdownUseCase,
                        pdfExporter = container.pdfExporter,
                        appInfo = container.appInfo,
                    ) as T
                }
            }
        }
    }
}

enum class ViewMode {
    READER,
    EDITOR,
}

sealed class FileLoadState {
    object Idle : FileLoadState()
    object Loading : FileLoadState()
    object Success : FileLoadState()
    object Saving : FileLoadState()
    object SaveSuccess : FileLoadState()
    data class Error(val message: String) : FileLoadState()
    data class SaveError(val message: String) : FileLoadState()
}
