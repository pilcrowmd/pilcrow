// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.domain.markdown.PlainTextChunks
import com.pilcrowmd.domain.markdown.ReaderModel
import com.pilcrowmd.domain.model.DocumentKind
import com.pilcrowmd.domain.model.EditorSupport
import com.pilcrowmd.domain.model.HeadingNode
import com.pilcrowmd.domain.model.RenderMode
import com.pilcrowmd.domain.model.SearchMatch
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
import com.pilcrowmd.domain.usecase.ReadEditLanding
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.repository.ByteFingerprint
import com.pilcrowmd.repository.Draft
import com.pilcrowmd.repository.DraftInfo
import com.pilcrowmd.repository.DraftRepository
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.StrandedSlot
import com.pilcrowmd.storage.RecentFile
import com.pilcrowmd.storage.ScrollAnchor
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** UI model for a recent file row, including whether its permission is still held. */
data class RecentFileUi(val uri: Uri, val displayName: String, val lastOpened: Long, val available: Boolean)

/** A heading-jump request. seq increments per tap so repeats to the same block still fire. */
data class HeadingJump(val position: Int, val seq: Int)

/**
 * State for markdown reading/editing.
 * Modeled as collection-capable (v1 holds one file, vNext can extend to tabs/multiple).
 */
data class Document(
    /**
     * The document's backing file, or **null for a document that has never been saved** — the
     * "Create MD File" path (M-91), which opens a blank document straight in the editor.
     *
     * Nullable rather than a sentinel URI ON PURPOSE. Every consumer of this field is a place
     * where "there is no file yet" needs a decision, and several of them are persistence keys —
     * last-file, recents, per-file scroll anchors, the save journal's `sha256(uri)` slot. A
     * sentinel would type-check its way into all of them and write junk state; null makes the
     * compiler demand an answer at each one. The blast radius was measured before choosing: ten
     * call sites in `main`.
     */
    val uri: Uri?,
    val content: String,
    val dirty: Boolean = false,
    // SAF display name (e.g. "notes.md"); drives the default PDF export filename. Empty until
    // resolved (intent/cold paths still route through loadFile, which populates it).
    val displayName: String = "",
    /**
     * True when the file's bytes were not valid UTF-8 (NEW-12). Saves write UTF-8, so saving over
     * the file would change its encoding and replace every invalid sequence (Safeguards 1 and 2):
     * every in-place save refuses, and Save-As writes a UTF-8 copy to a new file instead. Carried by
     * `copy()` with the identity, re-derived on every load, cleared when Save-As adopts the copy.
     */
    val notUtf8: Boolean = false,
    /**
     * Opaque per-instance identity (M-147). Defaulted and after every required field, so no
     * construction site changes; `copy()` carries it, so typing never changes identity. See [DocumentId].
     */
    val id: DocumentId = DocumentId.mint(),
    /**
     * How much editor this document gets (M-154), fixed from its length when it was opened.
     * `copy()` carries it, so typing never switches the editor's mode under the user's fingers, and
     * the screen reads it in the same frame the document appears — no window where a large file
     * reaches the editor before its limit is known.
     */
    val editorSupport: EditorSupport = EditorSupport.of(content.length),
) {
    /**
     * True when this document has no file on disk yet, so an in-place save is impossible and
     * Save must route to Save-As. Deliberately reads as a question about the document rather than
     * a null check, because that is what every call site is actually asking.
     */
    val isUnsaved: Boolean get() = uri == null
}

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
     * Where [loadDocument]'s CPU work and the search scan run. Injectable ONLY so a test can see that
     * work leave Main (Play Vitals ANR, 1.0.3) or hold a search while the state under it changes —
     * production always uses the default. Defaulted, so `provideFactory` and the AppContainer are untouched.
     */
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    /**
     * Builds what the reader paints (M-172, M-179). `provideFactory` passes the reader's own Markwon
     * parse; the default parses with the TOC's parity parser, whose top-level blocks are the same
     * (ParseParityTest), so a test that only reads positions, the TOC or search sees production's.
     */
    private val prepareReaderModel: PrepareReaderModelUseCase =
        PrepareReaderModelUseCase(parseHeadingsUseCase::parseTree, parseHeadingsUseCase),
    /** Where unsaved text is kept between keystrokes and a save, so a kill does not lose it. */
    private val drafts: DraftRepository = DraftRepository.None,
    /** S8-A46: whether a file opened from an added folder changes outside the app soon after. */
    private val changeCheck: OpenFileChangeCheck = OpenFileChangeCheck(stats = { null }),
) : ViewModel() {
    // The open document and everything derived from it. This class READS all of it, but can
    // replace the document only through the slot's adjudicated doors (M-109) — see [DocumentSlot].
    private val slot = DocumentSlot()

    // S8-A46 (see [startChangeCheck]). Before the startup restore below, which may publish inline.
    private val _changedOutside = MutableStateFlow(false)

    /** The open file changed on another device after it was opened: the reload banner shows. */
    val changedOutside: StateFlow<Boolean> = _changedOutside.asStateFlow()

    private var changeCheckJob: Job? = null

    // The document (identity and file) being checked. Another document, or this one saved under a
    // new URI, ends the check and the banner.
    private var checkedDocument: Pair<DocumentId, Uri>? = null

    init {
        viewModelScope.launch {
            slot.document.collect { doc ->
                val checked = checkedDocument ?: return@collect
                if (doc == null || doc.id != checked.first || doc.uri != checked.second) endChangeCheck()
            }
        }
    }

    // Unsaved text is kept as a draft while the document is dirty (crash recovery). Declared straight
    // after the slot and before anything that can load a document: the startup restore below may run
    // inline, and it asks this to hold and offer the draft of the file it publishes.
    private val draftKeeper = DraftKeeper(
        drafts = drafts,
        scope = viewModelScope,
        cpu = cpuDispatcher,
        slot = slot,
        isProtected = ::draftFileHasChanged,
    )

    // The stored draft of the file just opened, until the user decides: Restore, Save a copy or Discard.
    private val _recoveryOffer = MutableStateFlow<RecoveryOffer?>(null)
    val recoveryOffer: StateFlow<RecoveryOffer?> = _recoveryOffer.asStateFlow()

    init {
        viewModelScope.launch { slot.document.collect { draftKeeper.onDocument(it) } }
    }

    // v1 holds single document, but the structure allows collection.
    val currentDocument: StateFlow<Document?> = slot.document

    // How the reader pane renders the current document: extension default, overridable
    // per file. Re-derived on EVERY document-identity change (load + Save-As adoption).
    val renderMode: StateFlow<RenderMode> = slot.renderMode

    // True iff the current document is a `.txt` — gates the "View as Markdown" overflow toggle.
    val plainToggleAvailable: StateFlow<Boolean> = slot.plainToggleAvailable

    /**
     * Flip the reader between plain and Markdown rendering for the current document and persist
     * the choice per file (explicit in both directions). Viewing-only: touches
     * render state and the override store, never the document bytes or the save path.
     */
    fun toggleRenderMode() {
        viewModelScope.launch {
            val doc = slot.document.value ?: return@launch
            val next = if (slot.renderMode.value == RenderMode.PLAIN) RenderMode.MARKDOWN else RenderMode.PLAIN
            slot.setRendering(doc.id, renderMode = next)
            // An unsaved document has no URI to key an override on; the in-memory mode still flips.
            doc.uri?.let { storage.setRenderModeOverride(it, next) }
            refreshActiveSearch() // the TOC follows the new render mode through [readerModel]
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
     * wins; otherwise the extension default. Called on Save-As adoption, for document [expected]
     * only. A load or a new document derives the same value before it publishes (M-127).
     */
    private suspend fun applyDerivedRenderMode(expected: DocumentId, uri: Uri, displayName: String) {
        val kind = DocumentKind.fromDisplayName(displayName)
        val override = storage.getRenderModeOverride(uri)
        slot.setRendering(
            expected,
            renderMode = override ?: defaultRenderMode(kind),
            plainToggleAvailable = kind == DocumentKind.PLAIN_TEXT,
        )
    }

    // Transient = the open document holds no persisted *write* grant (e.g. opened read-only via
    // "Open with"), so an in-place save would fail. Derived from the repository at load/adopt time;
    // drives the transient banner and routes Save → Save-As. false when no
    // document is open.
    val transient: StateFlow<Boolean> = slot.transient

    // Stranded WAL slots (the escape hatch): saves the WAL could not commit
    // because the target became permanently inaccessible. 1..N independent of the open document.
    // The list backs both the dialog and the passive indicator; never auto-discarded.
    private val _strandedSlots = MutableStateFlow<List<StrandedSlot>>(emptyList())
    val strandedSlots: StateFlow<List<StrandedSlot>> = _strandedSlots.asStateFlow()

    // Drafts of unsaved edits whose file can no longer be opened (deleted, moved, access lost): they are
    // never offered by reopening the file, so they are listed in the same dialog. Never aged out.
    private val _orphanDrafts = MutableStateFlow<List<OrphanDraft>>(emptyList())
    val orphanDrafts: StateFlow<List<OrphanDraft>> = _orphanDrafts.asStateFlow()

    // Whether the stranded-slot dialog is shown. Auto-opened ONCE on a cold launcher start with
    // slots present (not on an intent open / onNewIntent / after dismissal) — the passive indicator
    // (driven by [strandedSlots] being non-empty) opens it on tap on every other path.
    private val _strandedDialogVisible = MutableStateFlow(false)
    val strandedDialogVisible: StateFlow<Boolean> = _strandedDialogVisible.asStateFlow()

    // One-shot guard so the cold-start auto-pop is evaluated once per process (survives the
    // Activity/composition recreate that re-runs the triggering LaunchedEffect on rotation).
    private var strandedStartEvaluated = false

    // Mode toggle state
    val mode: StateFlow<ViewMode> = slot.mode.asStateFlow()

    // Line-ending format preservation (Safeguard 2).
    // Tracks whether the file uses CRLF (\r\n) or LF (\n) so save can restore the original format.
    val lineEnding: StateFlow<String> = slot.lineEnding

    // Scroll position preservation: save position when toggling modes.
    // previewScroll: reader-mode anchor (first-visible block + intra-block offset)
    // editorScroll: editor/source-mode absolute pixel offset (Sora owns its own scroller)
    val previewScroll: StateFlow<ScrollAnchor> = slot.previewScroll.asStateFlow()
    val editorScroll: StateFlow<Int> = slot.editorScroll.asStateFlow()

    // Editor caret offset, hoisted so it survives the editor leaving/re-entering composition on a
    // mode toggle (otherwise the cursor jumped to the top of the file each time).
    val editorCursor: StateFlow<Int> = slot.editorCursor.asStateFlow()

    // In-document search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchMatches = MutableStateFlow<List<SearchMatch>>(emptyList())
    val searchMatches: StateFlow<List<SearchMatch>> = _searchMatches.asStateFlow()

    private val _currentMatchIndex = MutableStateFlow(0)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    private val _searchVisible = MutableStateFlow(false)
    val searchVisible: StateFlow<Boolean> = _searchVisible.asStateFlow()

    init {
        // A search belongs to the document it ran against. When a DIFFERENT document becomes
        // current (load, new document) or none does (close), close and clear it, exactly as the
        // user closing the bar would — otherwise the bar kept the old query and "2/2" over a file
        // that has neither. Keyed on the slot's DocumentId, which every publish door changes and
        // Save-As adoption keeps, so Save-As still re-runs the search ([refreshActiveSearch]).
        viewModelScope.launch {
            slot.document.map { it?.id }.distinctUntilChanged().collect { closeSearch() }
        }
    }

    // Heading table-of-contents navigation
    val headings: StateFlow<List<HeadingNode>> = slot.headings

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible.asStateFlow()

    // Each tap carries an incrementing seq so tapping the SAME heading twice (after scrolling
    // away) still fires — a plain position StateFlow would dedupe the repeat emission.
    private var headingJumpSeq = 0
    private val _headingJump = MutableStateFlow<HeadingJump?>(null)
    val headingJump: StateFlow<HeadingJump?> = _headingJump.asStateFlow()

    // Per-file scroll-position memory (keyed by document URI)
    private val _fileScrollPositions = mutableMapOf<String, Int>()

    // The user closed the temporary-file notice. Held in the slot, so another file, a reload or a
    // Save a copy brings the notice back; never persisted.
    val transientNoticeClosed: StateFlow<Boolean> = slot.transientNoticeClosed

    fun closeTransientNotice() {
        slot.transientNoticeClosed.value = true
    }

    // Edit to Read: the reader block the caret was in. Each switch to the reader takes a new seq.
    private var caretRevealSeq = 0
    private val _caretReveal = MutableStateFlow<CaretReveal?>(null)
    val caretReveal: StateFlow<CaretReveal?> = _caretReveal.asStateFlow()

    // Issue #15 (M-388, NEW-105): a switch lands on the same text. Each mode keeps its own place
    // unless the user moved the other one since the last switch: then Edit opens at the line in the
    // middle of the Read screen, and Read shows the caret's block. Plain view state, read only here.
    private val readEditLanding = ReadEditLanding(parseHeadingsUseCase)
    private var modeSwitch: Job? = null
    private var pendingMode: ViewMode? = null

    // How long the Read to Edit search may probe; a test shortens it.
    @androidx.annotation.VisibleForTesting
    internal var readEditBudgetNs = READ_EDIT_LANDING_BUDGET_NS

    // Read to Edit, worked out ahead: once the reader has stopped moving, the line its middle maps to
    // is searched for with no time limit, so the tap finds it ready (see [searchLanding]).
    private var settleJob: Job? = null
    private var landing: Landing? = null

    private class Landing(
        val content: String,
        val renderMode: RenderMode,
        val point: ReadEditLanding.ReaderPoint,
        val offset: Int,
        // A code block or plain chunk lands on the line the fraction names; a prose block on its first.
        val fractional: Boolean,
    )

    // How long a document may be for the landing to be searched ahead; a test shortens it.
    @androidx.annotation.VisibleForTesting
    internal var precomputeMaxChars = LANDING_PRECOMPUTE_MAX_CHARS

    /** Whether a landing is worked out ahead for the reader's current middle. */
    @androidx.annotation.VisibleForTesting
    internal val landingReady: Boolean get() = landing != null
    private var readerMovedSinceSwitch = true
    private var editorMovedSinceSwitch = false
    private var readerMiddle: ReaderMiddle? = null

    // A file arriving from an intent while another dirty file is open is held here
    // until the user resolves the unsaved-changes prompt (Save/Discard). null = no pending open.
    private val _pendingOpenUri = MutableStateFlow<Uri?>(null)
    val pendingOpenUri: StateFlow<Uri?> = _pendingOpenUri.asStateFlow()

    // M-126: a load failure earns a PERSISTENT message only when it leaves the user with nothing
    // on screen. That is decided at the instant the load fails, because only then is it known
    // whether a document was open (a message stored while a document was open would surface, stale,
    // the moment the user closed it). The slot decides it, and every publish retires it.
    val loadFailedWithNoDocument: StateFlow<Boolean> = slot.loadFailedWithNoDocument

    // A close that was refused because the document has unsaved edits (M-152's close half): the UI
    // answers it with its existing close prompt, then consumes it. One-shot, like the save outcomes.
    private val _closeNeedsConfirm = MutableStateFlow(false)
    val closeNeedsConfirm: StateFlow<Boolean> = _closeNeedsConfirm.asStateFlow()

    // File I/O state
    private val _fileLoadState = MutableStateFlow<FileLoadState>(FileLoadState.Idle)
    val fileLoadState: StateFlow<FileLoadState> = _fileLoadState.asStateFlow()

    // The load whose `Loading` is on screen. Only it may end that state, so a load that lost the
    // slot, or was cancelled, cannot reset `Loading` in the middle of a newer one (the job the
    // restore's join used to do). Main-confined, like everything that touches it.
    private var loadingClaim: Claim? = null

    // ── The write claim (M-149) ───────────────────────────────────────────────────────────────
    //
    // ⚠️ DO NOT GO BACK TO `_fileLoadState.value is FileLoadState.Saving` FOR ADMISSION CONTROL.
    // `Saving` is a VALUE of a shared state flow, not a held claim, and every load overwrites it:
    // `loadDocumentOrThrow` emits `Loading` as its FIRST statement, before `readFile`, and its own
    // terminal emit keeps `Saving` gone afterwards. So the guard re-opened the instant any load
    // STARTED, and all four save paths plus the whole toolbar went live mid-write. That is M-149,
    // and removing `:417` would not have fixed it — the terminal emit clobbers it too.
    //
    // THE SPLIT, in one line:
    //   *** writeInFlight decides what is ALLOWED. FileLoadState decides what the user is TOLD. ***
    //
    // `FileLoadState.Saving` is deliberately KEPT and still emitted: it is the observable marker
    // that a write started — the progress affordance, and what the test barriers wait on. It is no
    // longer read to decide whether anything may happen.
    private val _writeInFlight = MutableStateFlow(false)
    val writeInFlight: StateFlow<Boolean> = _writeInFlight.asStateFlow()

    // Preference flows (from storage)
    val lineNumbersEnabled: StateFlow<Boolean> = storage.lineNumbersEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** M-90: open documents straight in the editor. Default off. */
    val openInEditMode: StateFlow<Boolean> = storage.openInEditMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Font scales (0.85–1.6, persisted in DataStore).
    // Preview and Edit are independent app-wide prefs.
    val previewFontScale: StateFlow<Float> = storage.previewFontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    val editorFontScale: StateFlow<Float> = storage.editorFontScale
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0f)

    // Selected font set id (drives reading + mono typefaces app-wide).
    val fontSetId: StateFlow<String> = storage.fontSetId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "source")

    // Reader code blocks: wrap long lines instead of side-scrolling (default off, M-134).
    val wrapCodeLines: StateFlow<Boolean> = storage.wrapCodeLines
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Editor formatting bar above the keyboard (default on, M-217).
    val formattingBarEnabled: StateFlow<Boolean> = storage.formattingBarEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Hide the top bar and system bars while reading (default off, S8-A41).
    val fullScreenReading: StateFlow<Boolean> = storage.fullScreenReading
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

    // Crash-loop guard. The remembered file's pointer is written when it loads, but a file that
    // kills the process while it is read or first drawn (an endless stream, a poison table) would
    // be reopened at every start, before the user could reach Close. So a durable flag is set
    // BEFORE the read and cleared once the document has been shown ([onRestoredDocumentShown], called
    // by the UI after its first frames) or the restore ended without one. A start that finds the
    // flag still set does not reopen: it forgets the pointer (the file stays in Recent) and tells
    // the start screen why nothing opened. Without the pointer cleared, the next start would
    // alternate between crashing and not crashing.
    private val _lastFileNotReopened = MutableStateFlow(false)
    val lastFileNotReopened: StateFlow<Boolean> = _lastFileNotReopened.asStateFlow()

    // Restore the last-opened file ONCE on startup.
    // This must NOT keep collecting lastFileUri. DataStore re-emits the whole
    // Preferences on any write (e.g. saving a scroll position when switching to Reader),
    // so a perpetual collector would call loadFile again with the same URI, reload from
    // disk, and silently discard the user's unsaved edits (Safeguard 1/2). first() reads
    // the current value once and stops.
    //
    // The restore is a fallback, so it yields to any file the user actually asked for (M-145). It
    // claims FIRST — before it reads the remembered URI — so every open the user starts afterwards
    // claims later and wins the slot however the two finish (M-109). Explicit opens still cancel it,
    // but only to save the wasted read: they no longer join it, because the claim, not the join, is
    // what keeps the right document on screen, and joining waited on an uninterruptible read.
    private val restoreJob: Job = viewModelScope.launch {
        val claim = slot.claim()
        val lastUri = storage.lastFileUri.first()
        if (storage.isRestoreInProgress()) {
            // A flag left with no pointer (the file was closed before the flag was cleared) has
            // nothing to skip and nothing to say.
            if (lastUri != null) guardWrite("forget the remembered file") { storage.clearLastFileUri() }
            guardWrite("clear the restore flag") { storage.setRestoreInProgress(false) }
            if (lastUri != null) _lastFileNotReopened.value = true // last: the screen reads the rest as settled
            return@launch
        }
        if (lastUri == null) return@launch
        try {
            // Committed before the file is touched. A write that fails (a full disk) leaves the file
            // unguarded, but the restore is only a convenience: it still opens, as before this guard.
            guardWrite("set the restore flag") { storage.setRestoreInProgress(true) }
            loadDocument(lastUri, claim, ReplacePolicy.IF_CLEAN)
        } finally {
            // Only a document this restore put on screen keeps the flag, for the UI to clear once it
            // has drawn. A failed, superseded, refused or cancelled restore ends it here, and the
            // cancelled one must still be able to write.
            if (!slot.isLatestPublished(claim)) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    guardWrite("clear the restore flag") { storage.setRestoreInProgress(false) }
                }
            }
        }
    }

    /**
     * A write of the crash-loop guard's own state. It runs outside [loadDocument]'s catch, at launch,
     * so a DataStore write that throws (a full disk) must not take the app down: it is logged and the
     * restore goes on, since the guard is a safety net and not part of opening the file.
     */
    private suspend fun guardWrite(what: String, write: suspend () -> Unit) {
        try {
            write()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            android.util.Log.e("MarkdownViewModel", "restore guard: could not $what", e)
        }
    }

    /** The start screen has said why nothing was reopened; do not say it again. */
    fun resetLastFileNotReopened() {
        _lastFileNotReopened.value = false
    }

    /**
     * The UI drew the open document (a few frames after it, so a crash in the first bind has
     * already killed the process): the restore's flag can go. Idempotent; a no-op when none is set.
     */
    fun onRestoredDocumentShown() {
        viewModelScope.launch {
            guardWrite("clear the marker once shown") {
                if (storage.isRestoreInProgress()) storage.setRestoreInProgress(false)
            }
        }
    }

    fun loadFile(uri: Uri) = open(uri, ReplacePolicy.IF_CLEAN)

    /**
     * Open a file chosen via the SAF picker. Persists read/write **before** loading so the transient
     * check inside [loadDocument] reflects the just-granted write permission — a separate
     * fire-and-forget [takePermission] would race the load and could momentarily mis-flag a writable
     * file as transient. Best-effort: a provider that rejects the persist simply leaves the file
     * transient (which is then correct), so the result is ignored and never blocks the load.
     */
    fun openPickedFile(uri: Uri) = open(uri, ReplacePolicy.IF_CLEAN, takePermission = true)

    /**
     * Every explicit open. The claim is taken HERE, before anything suspends, so the order of claims
     * is the order the user asked (M-109).
     */
    private fun open(uri: Uri, policy: ReplacePolicy, takePermission: Boolean = false) {
        val claim = slot.claim()
        _lastFileNotReopened.value = false
        viewModelScope.launch {
            restoreJob.cancel()
            if (takePermission) repository.takePersistableUriPermission(uri)
            loadDocument(uri, claim, policy, countsAsOpen = true)
        }
    }

    /** End the load's `Loading` with [state] — only if this load still owns it (see [loadingClaim]). */
    private fun finishLoad(claim: Claim, state: FileLoadState) {
        if (loadingClaim != claim) return
        loadingClaim = null
        _fileLoadState.value = state
    }

    /**
     * Guards [loadDocumentOrThrow] so the load ALWAYS leaves [FileLoadState.Loading]. **M-115.**
     *
     * `readFile`'s failure is a `Result` and was always handled; everything after it — the CPU pass,
     * `displayName`, the permission IPC, the `openInEditMode` read, the render-mode derivation, the
     * scroll lookup — ran on the raw path to `emit(Success)`, so a throw anywhere in there skipped
     * the terminal emit and left `Loading` set for good. That was harmless while nothing was gated
     * on `Loading`; the welcome screen now disables its actions on it, which would turn a silent
     * failure into a permanently dead screen.
     *
     * **The body is wrapped rather than rewritten, deliberately.** The emission ORDER inside it is
     * load-bearing: the outcome is emitted BEFORE the last-file and recents persistence, so the
     * outcome never waits on DataStore I/O, and the load tests' barriers are built on that sequence.
     * A wrapper adds the missing exit without touching a single emit or its position.
     *
     * **`CancellationException` is rethrown**, not converted: it is an `Exception`, so a bare
     * `catch (e: Exception)` would swallow coroutine cancellation and leave the load looking like a
     * failure instead of a cancellation. It is written FULLY QUALIFIED on purpose — adding an import
     * shifts every line below it, and `app/config/ktlint/baseline.xml` pins this file's
     * `backing-property-naming` suppression to line 257 BY NUMBER. One new import moves that
     * declaration to 258, the baseline entry stops matching, and a pre-existing, unrelated,
     * deliberately-suppressed warning fails the gate.
     *
     * **Cancellation clears `Loading` to `Idle` before rethrowing.** A cancelled load is not a
     * failure to report to the user, but it is still a load that has to leave `Loading` — the
     * welcome-screen gate cannot tell a cancelled load from a stuck one, and a cancelled cold-start
     * restore would otherwise leave its actions disabled for good. Emitting from the cancelled
     * coroutine is sound, and that is a property of `MutableStateFlow` rather than an assumption:
     * its `emit` is a suspend function that never suspends — the whole body is `value = v`, which
     * compiles to `setValue(v); return Unit` with no `COROUTINE_SUSPENDED` path — so it performs no
     * cancellation check and takes effect even once the coroutine is cancelled.
     *
     * **Only the load that owns `Loading` ends it** ([finishLoad]), so a cancelled or superseded load
     * cannot reset it in the middle of a newer load.
     *
     * **The state emit only fires while still `Loading`.** A throw in the post-outcome persistence
     * lands after `emit(Success)`, and retroactively turning a document that loaded fine into an
     * error would be a worse lie than the crash it replaces. It is logged rather than dropped: the
     * load did finish, so there is nothing to tell the user, but a swallowed throw with no trace at
     * all is how a persistence bug stays invisible.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadDocument(uri: Uri, claim: Claim, policy: ReplacePolicy, countsAsOpen: Boolean = false) {
        try {
            loadDocumentOrThrow(uri, claim, policy, countsAsOpen)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            if (_fileLoadState.value is FileLoadState.Loading) finishLoad(claim, FileLoadState.Idle)
            throw e
        } catch (e: Exception) {
            if (_fileLoadState.value is FileLoadState.Loading && loadingClaim == claim) {
                slot.markLoadFailure(true)
                finishLoad(claim, FileLoadState.Error(e.message ?: "Unknown error"))
            } else {
                android.util.Log.e("MarkdownViewModel", "load threw after its outcome was published", e)
            }
        }
    }

    private suspend fun loadDocumentOrThrow(uri: Uri, claim: Claim, policy: ReplacePolicy, countsAsOpen: Boolean) {
        // A new attempt retires the last failure's message (M-126) — one place, because this is
        // the single point every load passes through.
        slot.markLoadFailure(false)
        loadingClaim = claim
        _fileLoadState.value = FileLoadState.Loading
        val file = repository.readFile(uri).getOrElse { error ->
            if (loadingClaim == claim) slot.markLoadFailure(true)
            finishLoad(
                claim,
                // Fully qualified: an import would shift the ktlint baseline's pinned line number.
                FileLoadState.Error(
                    error.message ?: "Unknown error",
                    tooLarge = error is com.pilcrowmd.repository.FileTooLargeException,
                ),
            )
            return
        }
        val content = file.content
        // ── PREPARE, THEN PUBLISH ONCE (M-127, M-128) ─────────────────────────────────────────
        // Every value the new document needs is computed first, and NOTHING the screen reads is
        // written until all of them exist. A throw or a cancellation during preparation — a
        // DataStore IOException, the permission lookup, or the startup restore being cancelled by
        // an explicit open (M-145) — leaves the previous document exactly as it was, with its own
        // line ending and dirty baseline. Before this, the load wrote the new file's line ending
        // and baseline ahead of the publish (M-128: the previous document's next save would apply
        // the wrong ending, a Safeguard 2 break) and the transient flag, mode, render mode, TOC
        // and scroll after it (M-127: a throw there left the new file on screen with the previous
        // one's state).
        //
        // ⚠️ Add new load work to prepareLoad, never between the publish and the terminal state.
        //
        // The publish is ADJUDICATED (M-109): it lands only if no operation that started later has
        // published, and — unless the user chose Discard — only if the document on screen has no
        // unsaved edits. Text typed while this load ran is never replaced silently (M-180): the
        // load stands down and the existing Save/Discard prompt asks instead.
        val publication = prepareLoad(uri, content, notUtf8 = !file.isUtf8)
        when (slot.publish(claim, publication, policy)) {
            PublishOutcome.PUBLISHED -> {
                // Before anything suspends: from here a keystroke could start overwriting the draft.
                checkForStoredDraft(publication.document)
                startChangeCheck(publication.document, file.takeIf { it.isUtf8 }?.content)
                // The TOC is the one value allowed to arrive AFTER the document: it comes from the
                // reader model, which [readerModel] prepares off Main once the document is published.
                finishLoad(claim, FileLoadState.Success)
                // Persist as last file + record in recents. The pointer is written only while this
                // load is still what is on screen, so it cannot name a file the user moved away
                // from (M-145).
                if (slot.isLatestPublished(claim)) storage.saveLastFileUri(uri)
                storage.addRecent(RecentFile(uri, publication.document.displayName, System.currentTimeMillis()))
                // M-177: an open the user started counts once THIS load has published. Only [open]
                // asks, so the startup restore is not a new use; a failed, superseded or refused load
                // never gets here, not even when another load of the same file has just published.
                // LAST, so a failed count write (caught and logged by [loadDocument]) cannot cost the
                // user their last-file pointer or recents entry.
                if (countsAsOpen) storage.incrementDocumentsOpened()
            }
            PublishOutcome.SUPERSEDED -> finishLoad(claim, FileLoadState.Idle)
            PublishOutcome.REFUSED_DIRTY -> {
                _pendingOpenUri.value = uri
                finishLoad(claim, FileLoadState.Idle)
            }
        }
    }

    /**
     * Compute everything a load publishes. **Changes no ViewModel state** — that is its whole
     * contract, and what makes a throw or cancellation anywhere in here harmless.
     */
    private suspend fun prepareLoad(uri: Uri, content: String, notUtf8: Boolean): Publication {
        // EVERY per-character pass over the document happens here, off the main thread.
        // `readFile` is `withContext(Dispatchers.IO)`, so it RETURNS to this coroutine's context —
        // `viewModelScope` = Dispatchers.Main.immediate — and until 1.0.4 that meant this ran on
        // Main. On a large file that froze the UI for minutes and Play Vitals recorded it as an ANR
        // (Pixel 6a, 1.0.3). Only the results cross back.
        //
        // The editor works in LF, so the content is normalised here and the original ending is
        // restored on save (applyLineEnding). Content and baseline are both LF, so a CRLF file does
        // not read as dirty the moment it opens.
        val (lineEnding, normalized) = withContext(cpuDispatcher) {
            detectLineEnding(content) to content.replace("\r\n", "\n")
        }
        // Resolved once; reused for the open document (export filename) and the recents entry.
        val displayName = repository.displayName(uri)
        // Transient iff we hold no persisted write grant (read-only "Open with"). A binder call, so
        // not on Main; IO rather than [cpuDispatcher] because it blocks, it does not compute.
        val writable = withContext(Dispatchers.IO) { repository.hasPersistedWritePermission(uri) }
        // M-90: open straight in the editor when asked. The storage read, not `openInEditMode.value`:
        // that StateFlow starts at `false` until DataStore delivers, and a cold "Open with" can get
        // here first.
        val openInEditor = storage.openInEditMode.first()
        val kind = DocumentKind.fromDisplayName(displayName)
        val renderMode = storage.getRenderModeOverride(uri) ?: defaultRenderMode(kind)
        return Publication(
            document = Document(
                uri = uri,
                content = normalized,
                dirty = false,
                displayName = displayName,
                notUtf8 = notUtf8,
            ),
            lineEnding = lineEnding,
            transient = !writable,
            switchToEditor = openInEditor,
            renderMode = renderMode,
            plainToggleAvailable = kind == DocumentKind.PLAIN_TEXT,
            headings = emptyList(), // filled in after the publish, see loadDocumentOrThrow
            previewScroll = storage.getScrollPosition(uri),
            resetEditorScroll = false,
        )
    }

    /**
     * The extension default: plain text renders as PLAIN, everything else as MARKDOWN. Declared
     * down here, not beside [applyDerivedRenderMode], because app/config/ktlint/baseline.xml pins a
     * suppression further up this file BY LINE NUMBER.
     */
    private fun defaultRenderMode(kind: DocumentKind): RenderMode =
        if (kind == DocumentKind.PLAIN_TEXT) RenderMode.PLAIN else RenderMode.MARKDOWN

    // ── The reader model (M-172, M-179) ───────────────────────────────────────────────────────
    //
    // What the reader paints, prepared OFF MAIN, ONCE per version of the text, and shared: the
    // reader renders it (it never parses), and the TOC is taken from it. Keyed by text identity +
    // render mode, so a font-scale, theme or pinch rebuild, or an editor round trip with no edits,
    // reuses it instead of parsing again.
    private val _readerModel = MutableStateFlow<ReaderModel?>(null)
    val readerModel: StateFlow<ReaderModel?> = _readerModel.asStateFlow()

    // The document the last model was prepared for. Lets an edited document go un-prepared while the
    // editor is open (it is prepared again when the reader comes back) without skipping the first
    // preparation of a document opened straight into the editor, whose TOC is needed there too.
    private var readerModelPreparedFor: DocumentId? = null

    // M-160 (K13/K14): the bare note links of the open note that name a note, worked out off Main by
    // the screen's NoteLinksViewModel and handed over through [setBareLinks]. Part of the model's
    // key: the arrival of a set is a new model of the same text (see [refreshReaderModel]).
    private val bareLinks = MutableStateFlow(NoteBareLinks(null, emptySet()))

    // The set the current model was built with.
    private var readerModelBareLinks: Set<String> = emptySet()

    /** The bare note links of the note [links] names. Another note's set is never applied to this one. */
    fun setBareLinks(links: NoteBareLinks) {
        bareLinks.value = links
    }

    init {
        // Issue #15: a newly opened document has no switch behind it. Its reader position is not the
        // editor's, so a first Edit lands on the reader's middle line, unless the caret was kept from
        // earlier in this session: then Edit returns to the line last edited.
        viewModelScope.launch {
            slot.document.map { it?.id }.distinctUntilChanged().collect {
                readerMovedSinceSwitch = !slot.editorCursorFromSession
                editorMovedSinceSwitch = false
                readerMiddle = null
                settleJob?.cancel()
                landing = null
            }
        }
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                slot.document,
                slot.renderMode,
                slot.mode,
                bareLinks,
            ) { doc, renderMode, mode, bare ->
                ReaderKey(doc, renderMode, mode, bare.destinations.takeIf { bare.uri == doc?.uri }.orEmpty())
            }
                // The same text, modes and applicable set again (another note's set being cleared when
                // this one opens) must not cancel a parse that cannot be interrupted and start it over.
                .distinctUntilChanged { old, new ->
                    old.doc === new.doc &&
                        old.renderMode == new.renderMode &&
                        old.mode == new.mode &&
                        old.bareLinks == new.bareLinks
                }
                // Latest wins: a newer text or mode cancels a preparation in flight (which is joined
                // first, so at most one parse runs at a time) and its result is never published.
                .collectLatest { refreshReaderModel(it.doc, it.renderMode, it.mode, it.bareLinks) }
        }
    }

    private fun ReaderModel.isReusableFor(doc: Document, renderMode: RenderMode, mode: ViewMode, bare: Set<String>) =
        content === doc.content &&
            this.renderMode == renderMode &&
            (this is ReaderModel.Plain || mode == ViewMode.EDITOR || readerModelBareLinks == bare)

    private data class ReaderKey(
        val doc: Document?,
        val renderMode: RenderMode,
        val mode: ViewMode,
        val bareLinks: Set<String>,
    )

    private suspend fun refreshReaderModel(doc: Document?, renderMode: RenderMode, mode: ViewMode, bare: Set<String>) {
        // FIRST, before any branch: this can be called INLINE from inside `DocumentSlot.publish`
        // (Main.immediate resumes the collector synchronously when the document is set), and publish
        // still has the rest of its fields to write — the render mode, and an empty TOC. Yielding lets
        // that publish finish, so a half-applied pair is cancelled here instead of costing a parse,
        // and a TOC set below cannot be reset by the very publish that triggered it (PR #225 CI).
        kotlinx.coroutines.yield()
        val current = _readerModel.value
        when {
            doc == null -> _readerModel.value = null
            // The same text (Save-As adopting a new identity, or a reload serving the same String):
            // reuse the model — but re-publish its TOC for this document, because a load's publish
            // resets the TOC to empty and nothing else would fill it again.
            // M-160: and the same bare links. A plain model has no tree to link, and the editor does not
            // care which set the reader's tree was built with: it keeps the model for the round trip.
            current != null && current.isReusableFor(doc, renderMode, mode, bare) -> {
                readerModelPreparedFor = doc.id
                slot.setRendering(doc.id, headings = current.tocHeadings)
            }
            // Typing: release the tree now rather than carry it next to the editor's own copy of the
            // text (M-154); the reader prepares a fresh one when it comes back.
            mode == ViewMode.EDITOR && readerModelPreparedFor == doc.id -> _readerModel.value = null
            else -> {
                // The same text with only the bare links changed keeps the old model on screen until
                // the new one is ready, so the reader does not blank while they arrive (M-160).
                val sameText = current != null && current.content === doc.content && current.renderMode == renderMode
                if (!sameText) _readerModel.value = null // drop the old tree before the new one is built
                val model = withContext(cpuDispatcher) { prepareReaderModel.prepare(doc.content, renderMode, bare) }
                readerModelPreparedFor = doc.id
                readerModelBareLinks = bare
                slot.setRendering(doc.id, headings = model.tocHeadings)
                _readerModel.value = model
            }
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
        val current = slot.document.value
        when {
            current?.uri == uri -> return // already viewing this file
            current?.dirty == true -> _pendingOpenUri.value = uri // confirm before discarding
            else -> loadFile(uri) // IF_CLEAN: typing during the load still reaches the prompt (M-180)
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
        open(uri, ReplacePolicy.DISCARD)
    }

    /**
     * Save the current file, then open the pending intent file — sequentially, so the save
     * completes first. A failed save aborts the switch and surfaces the error, keeping both
     * the current file and the pending request intact (Safeguard 1).
     *
     * The open that follows is [ReplacePolicy.IF_CLEAN]: a keystroke typed while the save was
     * writing keeps the document dirty ([DocumentSlot.markSaved] clears `dirty` only if nothing was
     * typed), so the open stands down and the prompt comes back, instead of dropping the keystroke
     * or marking it saved (M-152).
     */
    fun saveAndOpenPending() {
        val uri = _pendingOpenUri.value ?: return
        viewModelScope.launch {
            withWriteClaim {
                val doc = slot.document.value
                if (doc != null && doc.dirty) {
                    // M-110: an unsaved document (M-91) has no file to save in place. The View routes
                    // it to Save-As first; if it ever reaches here anyway, stop — keep the document
                    // and the prompt — rather than fall through to an open that would replace it.
                    val target = doc.uri ?: return@withWriteClaim
                    // NEW-12: keep the document, its edits and the prompt, exactly as a failed save.
                    if (refuseNotUtf8(doc)) return@withWriteClaim
                    _fileLoadState.emit(FileLoadState.Saving)
                    val result = saveTracked(target, contentForDisk(doc))
                    if (result.isFailure) {
                        _fileLoadState.emit(
                            FileLoadState.SaveError(result.exceptionOrNull()?.message ?: "Unknown error"),
                        )
                        return@withWriteClaim // keep current file + pending prompt; do not lose data
                    }
                    slot.markSaved(doc.id, doc.content)
                }
                _pendingOpenUri.value = null
                loadFile(uri)
            }
        }
    }

    /**
     * Close the current file and return to the welcome screen (privacy).
     * Clears the in-memory document and the persisted last-file URI so the app
     * does not auto-reopen it on next launch.
     *
     * [ReplacePolicy.IF_CLEAN]: a document with unsaved edits is NOT closed; the close prompt is
     * raised instead ([closeNeedsConfirm]). Discarding the edits is [discardAndClose], which only
     * that prompt calls — one method used to serve both, and no single policy fits both.
     */
    fun closeFile() = close(ReplacePolicy.IF_CLEAN)

    /** Discard the unsaved edits and close — the close prompt's Discard, and nothing else. */
    fun discardAndClose() = close(ReplacePolicy.DISCARD)

    private fun close(policy: ReplacePolicy) {
        // M-150: never close while a save is writing. The toolbar X and Back are already disabled
        // on [writeInFlight] (M-148/M-149), but that made the rule depend on the View; a close that
        // got through anyway left the write to report "Saved" on the welcome screen, for a document
        // no longer open. The report itself is KEPT — a write that succeeded says so (C4, M-147) —
        // so the fix is that the close cannot happen, not that the report is hidden. Checked
        // before the claim, so a refused close takes no claim at all.
        if (_writeInFlight.value) return
        val claim = slot.claim()
        when (slot.publishClose(claim, policy)) {
            PublishOutcome.PUBLISHED -> onClosed(claim)
            PublishOutcome.REFUSED_DIRTY -> _closeNeedsConfirm.value = true
            PublishOutcome.SUPERSEDED -> Unit // cannot happen: a claim taken just now is the newest
        }
    }

    /** After a close published: nothing is loading any more, and the pointer is cleared. */
    private fun onClosed(claim: Claim) {
        loadingClaim = null
        _fileLoadState.value = FileLoadState.Idle
        viewModelScope.launch {
            if (slot.isLatestPublished(claim)) storage.clearLastFileUri()
        }
    }

    /** The UI has raised the close prompt for [closeNeedsConfirm]; do not raise it again. */
    fun consumeCloseNeedsConfirm() {
        _closeNeedsConfirm.value = false
    }

    /**
     * Save the current file, then close it — sequentially in one coroutine so the save
     * always reads the live document before it's cleared (unsaved-changes guard).
     * If the save fails the file stays open and a SaveError is surfaced; edits are never
     * dropped on a failed write (Safeguard 1).
     *
     * A keystroke typed while the save was writing keeps the document open and dirty: the close is
     * [ReplacePolicy.IF_CLEAN]. The write did succeed, so "Saved" is reported (C4) — explicitly,
     * because a completed close reports `Idle` — and the close prompt comes back for the rest.
     */
    fun saveAndClose() {
        viewModelScope.launch {
            withWriteClaim {
                val doc = slot.document.value ?: return@withWriteClaim
                // An unsaved document (M-91) has no in-place target. The View routes Save→Save-As for
                // it, so reaching here with a null URI would mean closing WITHOUT the save the user
                // asked for — return instead of closing, and the document stays open with its text.
                val target = doc.uri ?: return@withWriteClaim
                // NEW-12: not written, so not closed — the document stays open and dirty.
                if (refuseNotUtf8(doc)) return@withWriteClaim
                // Claimed after the early returns (an abandoned claim must not exist) and before the
                // write suspends, so an open the user starts during the write wins over this close.
                val claim = slot.claim()
                _fileLoadState.emit(FileLoadState.Saving)
                saveTracked(target, contentForDisk(doc))
                    .onSuccess {
                        slot.markSaved(doc.id, doc.content)
                        when (slot.publishClose(claim, ReplacePolicy.IF_CLEAN)) {
                            PublishOutcome.PUBLISHED -> onClosed(claim)
                            PublishOutcome.REFUSED_DIRTY -> {
                                _fileLoadState.value = FileLoadState.SaveSuccess
                                _closeNeedsConfirm.value = true
                            }
                            // Something the user started later is on screen now. The write still
                            // succeeded, so say so (C4).
                            PublishOutcome.SUPERSEDED -> _fileLoadState.value = FileLoadState.SaveSuccess
                        }
                    }
                    .onFailure { error ->
                        _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                    }
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

    /** The user typed into document [expected]; ignored if it is no longer the open one. */
    fun updateContent(expected: DocumentId, newContent: String) {
        // Dirty is measured against the baseline, not a flag, so type-then-undo is clean again.
        viewModelScope.launch { slot.updateContent(expected, newContent) }
    }

    fun toggleMode() = switchMode { if (it == ViewMode.READER) ViewMode.EDITOR else ViewMode.READER }

    /** Set the view mode directly (used by the toolbar's Read/Edit mode button). */
    fun setMode(mode: ViewMode) = switchMode { mode }

    /**
     * Switch to the mode [target] picks from the current one, landing on the same text (issue #15).
     * When there is somewhere new to land, it is worked out off Main BEFORE the mode changes, so the
     * new mode composes in place instead of showing the old position first; otherwise the switch is
     * immediate. A newer switch cancels one still working and picks its target from the mode that
     * one was heading for, so a second tap reverses the first. A switch whose document was replaced
     * meanwhile is dropped: it was for the other document.
     */
    private fun switchMode(target: (ViewMode) -> ViewMode) {
        val before = slot.mode.value
        val mode = target(pendingMode ?: before)
        modeSwitch?.cancel()
        settleJob?.cancel()
        pendingMode = mode
        modeSwitch = viewModelScope.launch {
            val seq = ++caretRevealSeq
            _caretReveal.value = null
            val doc = slot.document.value
            var caretBlock: Int? = null
            if (doc != null && before != mode) {
                when {
                    mode == ViewMode.EDITOR -> landEditorOnReader(doc)
                    editorMovedSinceSwitch -> caretBlock = landReaderOnCaret(doc)
                }
                if (slot.document.value?.id != doc.id) {
                    pendingMode = null
                    return@launch
                }
                readerMovedSinceSwitch = false
                editorMovedSinceSwitch = false
            }
            slot.mode.value = mode
            if (mode == ViewMode.EDITOR) {
                slot.editorShownOnDocument = true
                settleJob?.cancel()
            }
            pendingMode = null
            if (doc != null && before == ViewMode.EDITOR && mode == ViewMode.READER) {
                publishCaretReveal(doc, seq, caretBlock)
            }
        }
    }

    /**
     * Edit to Read after the caret moved: put the caret's block a third of the way down the Read
     * screen. Returns that block when the reader shows Markdown, for the keyboard reveal. Not
     * bounded: it is one parse of the text, which the reader has to make anyway to show it.
     */
    private suspend fun landReaderOnCaret(doc: Document): Int? {
        val markdown = !prepareReaderModel.showsAsPlain(doc.content, slot.renderMode.value)
        val model = currentReaderModel(doc)
        val plain = model?.let { it is ReaderModel.Plain } ?: !markdown
        val caret = slot.editorCursor.value
        val block = withContext(cpuDispatcher) {
            readEditLanding.blockForOffset(doc.content, plain, caret, model?.blocks)
        } ?: return null
        if (slot.document.value?.id != doc.id) return null
        updatePreviewScroll(ScrollAnchor(block, (readerMiddle?.viewportHeight ?: 0) / CARET_BLOCK_SCREEN_SHARE))
        return block.takeUnless { plain }
    }

    /**
     * Edit to Read: publish the block the caret sat in ([known] when the landing found it already),
     * so the reader can bring it above an open keyboard. Plain-text rendering has no blocks to point
     * at, so it publishes nothing; that includes a document past the Markdown ceiling, which must not
     * be parsed (M-179). A newer switch or a different document that arrives meanwhile takes over
     * (the seq no longer matches) and this result is dropped.
     */
    private suspend fun publishCaretReveal(doc: Document, seq: Int, known: Int?) {
        if (prepareReaderModel.showsAsPlain(doc.content, slot.renderMode.value)) return
        val caret = slot.editorCursor.value
        val block = known ?: withContext(cpuDispatcher) {
            parseHeadingsUseCase.readerBlockForOffset(doc.content, caret)
        }
        if (block != null && seq == caretRevealSeq && slot.document.value?.id == doc.id) {
            _caretReveal.value = CaretReveal(block, seq)
        }
    }

    /**
     * Read to Edit: when the reader moved since the last switch, move the caret to the line that was
     * in the middle of the Read screen (the editor brings it to its own middle). Otherwise the caret
     * stays, and the editor comes back exactly as it was. The line is usually ready: it is searched for
     * when the reader stops ([searchLanding]). If not, the search runs now and stops probing after
     * [READ_EDIT_LANDING_BUDGET_NS], and the caret goes to the nearest line it found; a probe still
     * running then gets [READ_EDIT_LANDING_GRACE_NS] more to finish, and past that the caret goes to
     * the guess from the blocks' sizes.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun landEditorOnReader(doc: Document) {
        if (!readerMovedSinceSwitch) return
        val middle = readerMiddle ?: return
        val model = currentReaderModel(doc) ?: return
        val point = ReadEditLanding.ReaderPoint(middle.block, middle.fraction)
        val ready = landing?.takeIf { it.matches(doc, slot.renderMode.value, point) }
        if (ready != null) {
            slot.editorCursor.value = ready.offset
            return
        }
        val plain = model is ReaderModel.Plain
        val budget = readEditBudgetNs
        val deadline = System.nanoTime() + budget
        // The wait is a little longer than the search's own budget, so the nearest line it found when
        // the budget ran out is received and not thrown away. The search is cancelled however the wait
        // ends (a timeout, or a newer switch): it stops at its next probe, and one still running is
        // left to finish unseen, so the tap never waits for a slow probe.
        val search = viewModelScope.async(cpuDispatcher) {
            readEditLanding.offsetForBlock(doc.content, plain, point, model.blocks) {
                isActive && System.nanoTime() < deadline
            }
        }
        val found = try {
            withTimeoutOrNull((budget + READ_EDIT_LANDING_GRACE_NS) / NANOS_PER_MILLI) { search.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            searchAheadFailed(e)
        } catch (e: RuntimeException) {
            searchAheadFailed(e)
        } finally {
            search.cancel()
        }
        // A probe that overran even the grace: the guess from the blocks' sizes, not the old caret.
        val offset = found ?: readEditLanding.estimatedOffsetForBlock(doc.content, plain, point, model.blocks)
        if (offset != null && slot.document.value?.id == doc.id) slot.editorCursor.value = offset
    }

    private fun Landing.matches(doc: Document, renderMode: RenderMode, point: ReadEditLanding.ReaderPoint): Boolean {
        val sameText = content === doc.content && this.renderMode == renderMode
        val sameFraction = kotlin.math.abs(this.point.fraction - point.fraction) <= LANDING_FRACTION_TOLERANCE
        return sameText && this.point.block == point.block && (!fractional || sameFraction)
    }

    /**
     * The reader stopped moving: work out, with no time limit, the line its middle maps to, so the
     * tap on Edit finds it ready. Cancelled by the reader moving again, a switch or another document.
     * Only a reader still showing, and a document short enough to be worth it ([precomputeMaxChars]);
     * a failure of the search (it is a parse on a worker) changes nothing: the tap searches then.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun searchLanding() {
        val doc = slot.document.value ?: return
        val middle = readerMiddle
        val model = currentReaderModel(doc)
        if (middle == null || model == null || !worthSearchingAhead(doc)) return
        val renderMode = slot.renderMode.value
        val point = ReadEditLanding.ReaderPoint(middle.block, middle.fraction)
        if (landing?.matches(doc, renderMode, point) == true) return
        val offset = try {
            withContext(cpuDispatcher) {
                val plain = model is ReaderModel.Plain
                readEditLanding.offsetForBlock(doc.content, plain, point, model.blocks) { isActive }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            searchAheadFailed(e)
        } catch (e: RuntimeException) {
            searchAheadFailed(e)
        }
        if (offset != null && worthSearchingAhead(doc) && slot.document.value?.id == doc.id) {
            val code = model.blocks.getOrNull(point.block) is org.commonmark.node.FencedCodeBlock
            val fractional = model is ReaderModel.Plain || code
            landing = Landing(doc.content, renderMode, point, offset, fractional)
        }
    }

    private fun worthSearchingAhead(doc: Document) =
        slot.mode.value == ViewMode.READER && doc.content.length <= precomputeMaxChars

    private fun searchAheadFailed(e: Throwable): Int? {
        landing = null
        android.util.Log.e("MarkdownViewModel", "landing search ahead failed", e)
        return null
    }

    /** The reader model when it is the one for this text and render mode, else null. */
    private fun currentReaderModel(doc: Document): ReaderModel? =
        _readerModel.value?.takeIf { it.content === doc.content && it.renderMode == slot.renderMode.value }

    /**
     * The user moved the reader: a drag or fling, a jump button, a heading or search jump. A pinch,
     * a layout or the caret reveal does not count.
     */
    fun onReaderMoved() {
        readerMovedSinceSwitch = true
    }

    /**
     * The reader block under the middle of the Read screen, [fraction] of the way down it, and the
     * screen's height in pixels. The reader reports it from its scroll callback, which RecyclerView also
     * calls (with dx 0, dy 0) after a layout, so a reader nobody scrolled reports it too.
     */
    fun updateReaderMiddle(block: Int, fraction: Float, viewportHeight: Int) {
        readerMiddle = ReaderMiddle(block, fraction, viewportHeight)
        // Debounced: the search starts once the reports stop, and only for a reader somebody moved.
        settleJob?.cancel()
        if (!readerMovedSinceSwitch || slot.mode.value != ViewMode.READER) return
        settleJob = viewModelScope.launch {
            kotlinx.coroutines.delay(READER_SETTLE_MILLIS)
            searchLanding()
        }
    }

    /**
     * The reader performed (or, with no keyboard open, skipped) reveal [seq]. Only the matching seq
     * clears it, so a late report for an older switch never drops a newer one (as [onHeadingJumpHandled]).
     */
    fun onCaretRevealHandled(seq: Int) {
        if (_caretReveal.value?.seq == seq) _caretReveal.value = null
    }

    fun updatePreviewScroll(anchor: ScrollAnchor) {
        viewModelScope.launch {
            slot.previewScroll.value = anchor
            // Persist scroll anchor per file.
            val uri = slot.document.value?.uri ?: return@launch
            storage.saveScrollPosition(uri, anchor)
        }
    }

    fun updateEditorScroll(position: Int) {
        viewModelScope.launch { slot.editorScroll.value = position }
    }

    /** Remember the editor caret offset so a mode toggle restores it (not reset to 0). */
    fun updateEditorCursor(offset: Int) {
        if (offset != slot.editorCursor.value) editorMovedSinceSwitch = true
        slot.editorCursor.value = offset
    }

    // The search in flight, cancelled by a newer query and by [closeSearch], so a scan that was
    // still running when the bar closed or the document changed never publishes its matches.
    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchQuery.emit(query)
            if (query.isNotEmpty()) {
                val content = slot.document.value?.content ?: return@launch
                val renderMode = slot.renderMode.value
                // The model, only if it is the one for this text and mode (a toggle re-runs search
                // before the new model exists).
                val model = _readerModel.value?.takeIf { it.content === content && it.renderMode == renderMode }
                // Search parses + scans the whole document — run it off the main thread so a
                // large file (or a common term with thousands of hits) can't freeze the UI / ANR.
                // Shown as plain (the mode, or past the Markdown ceiling): the visible text IS the
                // literal source, so match over the chunks the reader paints (positions align) —
                // the model's own when there is one, so a huge file is not chunked a second time.
                val matches = withContext(cpuDispatcher) {
                    scanOrNothing {
                        when {
                            model is ReaderModel.Plain ->
                                searchUseCase.findPlainSearchMatches(model.chunkLiterals, query)
                            model == null && prepareReaderModel.showsAsPlain(content, renderMode) ->
                                searchUseCase.findPlainSearchMatches(PlainTextChunks.split(content), query)
                            else -> searchUseCase.findSearchMatches(content, query)
                        }
                    }
                }
                // No re-check needed here: the scan does not stop when cancelled, but withContext
                // throws on return once this job is cancelled, so its result is never published.
                _searchMatches.emit(matches)
                _currentMatchIndex.emit(0) // Focus first match
            } else {
                _searchMatches.emit(emptyList())
                _currentMatchIndex.emit(0)
            }
        }
    }

    /**
     * M-401: the last line, not the fix. A search scan that throws anyway finds nothing, so typing a
     * query can never crash the app. The scan never suspends, so no cancellation passes through here.
     */
    @Suppress("TooGenericExceptionCaught") // any scan failure, whatever its type, must stop here
    private fun scanOrNothing(scan: () -> List<SearchMatch>): List<SearchMatch> = try {
        scan()
    } catch (e: RuntimeException) {
        android.util.Log.e("MarkdownViewModel", "search scan threw", e)
        emptyList()
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
            if (visible) _searchVisible.emit(true) else closeSearch()
        }
    }

    private suspend fun closeSearch() {
        searchJob?.cancel()
        _searchVisible.emit(false)
        // Exiting search clears its state. The preview observes the now-empty
        // matches and re-binds without highlight spans — no stale highlights linger.
        _searchQuery.emit("")
        _searchMatches.emit(emptyList())
        _currentMatchIndex.emit(0)
    }

    fun setTocVisible(visible: Boolean) {
        viewModelScope.launch { _tocVisible.emit(visible) }
    }

    fun jumpToHeading(heading: HeadingNode) {
        // Signal Preview to smooth-scroll to this block; seq makes repeat taps distinct.
        headingJumpSeq++
        _headingJump.value = HeadingJump(heading.adapterPosition, headingJumpSeq)
    }

    /**
     * The surface that performed jump [seq] reports it (M-184). A jump is a one-shot command: left
     * in place, the next freshly composed reader (the reader coming back from the editor, say)
     * saw it as unhandled and ran it again. Only the matching seq clears it, so a late report for
     * an older tap never drops a newer one.
     */
    fun onHeadingJumpHandled(seq: Int) {
        if (_headingJump.value?.seq == seq) _headingJump.value = null
    }

    init {
        // M-184: a jump belongs to the document it was tapped in. When a different document becomes
        // current (load, re-open, new document) or none does (close), drop any jump not yet
        // performed, or the new document's reader runs it at a position from the old one. Same key
        // as the search reset in the first init block: Save-As keeps the DocumentId, so it keeps the
        // jump. The pending caret reveal goes the same way. A separate init block because it must
        // run after `_headingJump` and `_caretReveal` are initialised.
        viewModelScope.launch {
            slot.document.map { it?.id }.distinctUntilChanged().collect {
                _headingJump.value = null
                _caretReveal.value = null
            }
        }
    }

    /**
     * Runs [block] only if no write is already in flight, and ALWAYS releases the claim afterwards.
     * The single admission gate for every path that writes a file (M-149).
     *
     * **THE RULE, STATED ONCE AND NOT DECIDED PER CALL SITE:**
     *
     * > **The claim covers the OPERATION, including its persistence tail — not just the write,
     * > and not just up to the terminal emit.**
     *
     * `lastFileUri` and the recents entry are *persisted pointers to the document*. A claim that
     * ended at the "Saved" toast would leave them unguarded, which is precisely the shape M-145
     * is about — releasing early would rebuild the thing being fixed. **Accepted consequence: the
     * toolbar re-enables after DataStore, not after the write.**
     *
     * All four claimed blocks conform, and the tails differ: `saveActiveDocumentAs` persists
     * render mode, `lastFileUri` and recents; `saveAndClose` clears `lastFileUri`; `saveFile` and
     * `saveAndOpenPending` have no persistence after their outcome.
     *
     * **The one write path deliberately NOT claimed is [rescueStrandedSlot]** — it writes a
     * stranded WAL slot to a target of its own and never touches the open document, and it was
     * unguarded before this change. Named as the exception rather than swept in.
     *
     * **Can the tail block for an unbounded time? NO — established at source.**
     * Everything after the terminal emit is `LocalStorageManager`, which has **zero**
     * `ContentResolver` references and no network or binder call: DataStore reads and writes
     * against the app's own private storage, plus CPU passes over the document. Bounded by disk
     * and document size. The genuinely unbounded calls — the SAF write and the `ContentResolver`
     * queries into a third-party `DocumentsProvider` — sit *before* the tail and were inside the
     * old guard too, so extending the claim over the tail adds bounded time only. **`finally`
     * bounds the claim on the tail RETURNING; the tail returns in bounded time.**
     *
     * **`compareAndSet`, not read-then-set.** The old guard read `_fileLoadState` and emitted
     * separately, so two callers could both pass the check before either marked it. Claiming
     * atomically closes that on top of the defect this exists for.
     *
     * **⚠️ THE `finally` IS LOAD-BEARING, AND SO IS ITS POSITION.** A claim that is not released
     * bricks every save for the rest of the session — worse than the bug being fixed — and a
     * suite of refusal assertions would go GREENER, not redder, for it. A claim released at the
     * terminal emit instead would pass a naive release test while leaving the tail unguarded.
     * **Both are covered by `theClaimIsReleasedAfterThePersistenceTailNotAtTheTerminalEmit`, and
     * both mutations are recorded in M-149.**
     */
    private suspend fun withWriteClaim(block: suspend () -> Unit) {
        if (!_writeInFlight.compareAndSet(expect = false, update = true)) return
        try {
            block()
        } finally {
            _writeInFlight.value = false
        }
    }

    fun saveFile() {
        viewModelScope.launch {
            withWriteClaim {
                // Guard against a concurrent save (e.g. double-tap) — two simultaneous writes to
                // the same URI could interleave/truncate each other (Safeguard 1).
                val doc = slot.document.value ?: return@withWriteClaim
                // No file on disk yet (M-91): there is nothing to save IN PLACE. The View routes this
                // case to Save-As before calling here; returning rather than inventing a target is the
                // safe half of that contract — a wrong guess would write the user's text somewhere they
                // did not choose (Safeguard 1).
                val target = doc.uri ?: return@withWriteClaim
                if (refuseNotUtf8(doc)) return@withWriteClaim

                _fileLoadState.emit(FileLoadState.Saving)

                // contentForDisk restores the original line ending before the write (Safeguard 2),
                // shared by every save path so a CRLF file is never silently converted to LF.
                saveTracked(target, contentForDisk(doc))
                    .onSuccess {
                        // Baseline = the content we just persisted, in the editor's LF form (doc.content).
                        // The disk form (contentForDisk) may be CRLF; the editor/model always work in LF, so
                        // the baseline and the dirty comparison MUST use the LF doc.content, not the disk
                        // form — otherwise dirty would never clear for a CRLF file. Dirty clears only
                        // if nothing was typed during the save (Safeguard 2), and neither moves if
                        // another document is on screen by now.
                        slot.markSaved(doc.id, doc.content)
                        _fileLoadState.emit(FileLoadState.SaveSuccess)
                    }
                    .onFailure { error ->
                        _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                    }
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
            withWriteClaim {
                val doc = slot.document.value ?: return@withWriteClaim
                // M-147: the identity of the document whose bytes we are about to write. Compared
                // before the stamp below, because the slot can change while the write is suspended.
                val savedId = doc.id
                // NEW-12: the copy goes to a NEW file. A picker that hands back the original itself
                // would make this an in-place save in another encoding, so it is refused the same way.
                if (targetUri == doc.uri && refuseNotUtf8(doc)) return@withWriteClaim
                // It can also hand the original back under ANOTHER URI (opened from Recent, then
                // "Overwrite" on the same file in Downloads), which no URI comparison can see. So
                // the copy only goes to a new, empty file; a target that already has content, or
                // cannot be read, is refused.
                if (doc.notUtf8 && !repository.isNewEmptyFile(targetUri)) return@withWriteClaim refusePickNewFile()
                _fileLoadState.emit(FileLoadState.Saving)

                // Best-effort persist; result ignored so a provider that rejects it can't abort the write.
                repository.takePersistableUriPermission(targetUri)

                saveTracked(targetUri, contentForDisk(doc))
                    .onSuccess {
                        val displayName = repository.displayName(targetUri)

                        // ── M-147: ADOPT ONLY ONTO THE DOCUMENT WE ACTUALLY WROTE ───────────────
                        // The slot can change while the write is suspended — a warm intent or a
                        // Discard can publish a different document here. Stamping `targetUri` onto
                        // whatever happens to be current left B on screen wearing the copy's URI,
                        // and B's next save overwrote the copy. The slot compares by opaque
                        // DocumentId, NOT by URI: two unsaved documents both have `uri == null`, so
                        // `null == null` is not an identity check. It keeps the live content, so
                        // edits typed during the write survive and keep the document dirty.
                        //
                        // The permission lookup is a synchronous binder call; it answers whether the
                        // adopted file is writable, and clears the transient banner if so.
                        val adopted = slot.adoptIdentity(
                            expected = savedId,
                            // Baseline = the LF content we persisted (contentForDisk may be CRLF).
                            writtenContent = doc.content,
                            uri = targetUri,
                            displayName = displayName,
                            transient = !repository.hasPersistedWritePermission(targetUri),
                        )

                        // ALWAYS: the bytes reached disk. A failure report would be false, and
                        // would invite a retry that writes a SECOND stray file.
                        _fileLoadState.emit(FileLoadState.SaveSuccess)

                        if (adopted) {
                            // Everything below is derived from the document's identity, so on a
                            // mismatch it would describe a document that is not on screen: the
                            // render mode would follow the target's extension, the TOC would be
                            // rebuilt from the captured content, and `lastFileUri` would make the
                            // next launch open a file the user was not looking at — which is
                            // M-145's shape exactly.
                            applyDerivedRenderMode(savedId, targetUri, displayName) // the TOC follows via [readerModel]
                            refreshActiveSearch()
                            storage.saveLastFileUri(targetUri)
                        }

                        // ALWAYS, and deliberately outside the gate. On a
                        // failed adoption every other trace of the target is discarded, so recents
                        // is the ONLY surviving pointer to a file the user just created. Safe
                        // because M-148 and M-149 leave no close path inside the write window —
                        // IF THIS BRANCH IS EVER SPLIT, THE QUESTION REOPENS (see M-147).
                        storage.addRecent(RecentFile(targetUri, displayName, System.currentTimeMillis()))
                    }
                    .onFailure { error ->
                        _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error"))
                    }
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
            val orphans = findOrphanDrafts()
            _orphanDrafts.value = orphans
            if ((slots.isNotEmpty() || orphans.isNotEmpty()) && !openingFileFromIntent) {
                _strandedDialogVisible.value = true
            }
        }
    }

    /** Re-read the stranded-slot and orphan-draft lists (after a rescue/discard). */
    private fun refreshStrandedSlots() {
        viewModelScope.launch {
            val slots = repository.strandedSlots().getOrDefault(emptyList())
            _strandedSlots.value = slots
            val orphans = findOrphanDrafts()
            _orphanDrafts.value = orphans
            // Keep the dialog flag honest: once the last entry is resolved, close it (so a NEW slot
            // appearing later this session can't auto-reopen the dialog via a stale visible flag).
            if (slots.isEmpty() && orphans.isEmpty()) _strandedDialogVisible.value = false
        }
    }

    /**
     * The stored drafts whose file cannot be opened any more, newest first. The draft of the open
     * document and the one being offered are excluded: the user is already looking at those.
     */
    private suspend fun findOrphanDrafts(): List<OrphanDraft> {
        val orphans = drafts.list().filter { isOrphan(it.uri) }
        if (orphans.isEmpty()) return emptyList()
        val known = storage.recentFiles.first().associate { it.uri to it.displayName }
        return orphans.map { OrphanDraft(it.uri, known[it.uri] ?: nameFromUri(it.uri), it.savedAt) }
    }

    /**
     * Whether the draft stored for [uri] is an orphan right now: not the open document, not the draft being
     * offered, and its file cannot be opened. The list is a snapshot, so the actions ask again before acting.
     */
    private suspend fun isOrphan(uri: Uri): Boolean =
        uri != slot.document.value?.uri && uri != _recoveryOffer.value?.uri && repository.readFile(uri).isFailure

    /** A name for a file nobody remembers: the last path segment (already URL-decoded), after any `/` or `:`. */
    private fun nameFromUri(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
            ?: uri.toString()

    /** Save an orphan draft's text as a copy at [targetUri]; the draft is removed only once that succeeded. */
    fun saveOrphanDraftAsCopy(targetUri: Uri, draftUri: Uri) {
        viewModelScope.launch {
            // A stale row (its file opens again) is left alone: the draft is offered when that file is reopened.
            if (isOrphan(draftUri)) {
                drafts.read(draftUri)?.let { writeDraftCopy(it, targetUri) { forgetOrphanFile(draftUri) } }
            }
            refreshStrandedSlots()
        }
    }

    /** Explicitly discard an orphan draft at the user's request. */
    fun discardOrphanDraft(draftUri: Uri) {
        viewModelScope.launch {
            if (isOrphan(draftUri)) {
                drafts.delete(draftUri)
                forgetOrphanFile(draftUri)
            }
            refreshStrandedSlots()
        }
    }

    /**
     * The orphan's draft is settled, so its file is dead to the app: drop it from Recent and, when it is the
     * remembered last file, forget that too. The startup restore of that last file is what put "Couldn't open
     * that file" on the welcome screen, so that line goes with it.
     */
    private suspend fun forgetOrphanFile(uri: Uri) {
        storage.removeRecent(uri)
        if (storage.lastFileUri.first() == uri) {
            storage.clearLastFileUri()
            slot.markLoadFailure(false)
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
            // NEW-12: the picker can hand back an existing file, and one that is not UTF-8 is never
            // written over. Refused before any write, so the slot is kept.
            if (!repository.readsAsUtf8(targetUri)) return@launch refusePickNewFile()
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

    // ── Drafts: recovery of unsaved edits ─────────────────────────────────────────────

    /**
     * The file [doc] was just opened: if a draft of unsaved edits is stored for it, offer it. Writing
     * a draft for this file is held from this very turn until the user has decided, so nothing typed
     * meanwhile can overwrite the stored one.
     */
    private fun checkForStoredDraft(doc: Document) {
        val uri = doc.uri ?: return
        // An offer for another file is dropped with the file; its draft stays stored, offered again on reopening.
        _recoveryOffer.value?.takeIf { it.uri != uri }?.let {
            _recoveryOffer.value = null
            draftKeeper.release(it.uri)
        }
        draftKeeper.hold(uri)
        viewModelScope.launch {
            val offer = try {
                draftKeeper.offerFor(uri, doc.displayName, doc.content)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                android.util.Log.w("MarkdownViewModel", "could not look for a stored draft", e)
                null
            }
            // The file may have been replaced while the draft was read: no offer is shown for a file no longer open.
            val stillOpen = slot.document.value?.uri == uri
            if (offer == null || !stillOpen) draftKeeper.release(uri) else _recoveryOffer.value = offer
        }
    }

    /** Whether the file a stored draft belongs to is no longer what the draft was typed on (or cannot be told). */
    private suspend fun draftFileHasChanged(info: DraftInfo): Boolean {
        val disk = repository.readFile(info.uri).getOrNull() ?: return true
        return withContext(cpuDispatcher) { ByteFingerprint.of(disk.content.replace("\r\n", "\n")) } != info.baseline
    }

    /**
     * Restore: put the stored text in the editor. Nothing is written to the file; the document is
     * dirty and the draft stays stored until the user saves or discards.
     */
    fun restoreDraft() {
        val offer = _recoveryOffer.value ?: return
        viewModelScope.launch {
            val draft = drafts.read(offer.uri)
            val doc = slot.document.value
            if (draft != null && doc?.uri == offer.uri) {
                slot.updateContent(doc.id, draft.text)
                setMode(ViewMode.EDITOR)
            }
            _recoveryOffer.value = null
            draftKeeper.release(offer.uri)
        }
    }

    /** Discard: the user does not want the stored text. The only way a draft that differs from its file is removed. */
    fun discardDraft() {
        val offer = _recoveryOffer.value ?: return
        viewModelScope.launch {
            drafts.delete(offer.uri)
            _recoveryOffer.value = null
            draftKeeper.release(offer.uri)
        }
    }

    /**
     * Save a copy: write the stored text to a new file of the user's choosing, with the line ending
     * the file had, through the crash-safe save. The draft is removed only once that has succeeded.
     */
    fun saveDraftAsCopy(targetUri: Uri) {
        val offer = _recoveryOffer.value ?: return
        viewModelScope.launch {
            val draft = drafts.read(offer.uri) ?: return@launch
            writeDraftCopy(draft, targetUri) {
                _recoveryOffer.value = null
                draftKeeper.release(offer.uri)
            }
        }
    }

    /**
     * Write [draft]'s text to [targetUri] with the line ending its file had, through the crash-safe
     * save, and remove the draft only once that has succeeded ([onSaved] runs then, before the outcome
     * is published). A refused or failed write leaves the draft where it was.
     */
    private suspend fun writeDraftCopy(draft: Draft, targetUri: Uri, onSaved: suspend () -> Unit) {
        // NEW-12: the picker can hand back an existing file, and one that is not UTF-8 is never written over.
        if (!repository.readsAsUtf8(targetUri)) return refusePickNewFile()
        repository.takePersistableUriPermission(targetUri) // best-effort; result intentionally ignored
        saveTracked(targetUri, applyLineEnding(draft.text, draft.lineEnding))
            .onSuccess {
                drafts.delete(draft.uri)
                onSaved()
                _fileLoadState.emit(FileLoadState.SaveSuccess)
            }
            .onFailure { error -> _fileLoadState.emit(FileLoadState.SaveError(error.message ?: "Unknown error")) }
    }

    /** The app is going to the background, where the system may end it: write the draft now. */
    fun flushDraft() = draftKeeper.flush()

    // ── S8-A46: the file changed outside the app soon after it was opened ──────────────────────
    // A save over the file the banner warned about is the user's answer to it: the banner goes.
    private suspend fun saveTracked(uri: Uri, content: String): Result<Unit> =
        changeCheck.saving { repository.saveFile(uri, content) }.also {
            if (it.isSuccess && checkedDocument?.second == uri) _changedOutside.value = false
        }

    /**
     * [read] is the file's text as read, exactly as on disk, when it is UTF-8: its size is then the
     * number of bytes read (strict UTF-8 round-trips byte for byte). Null skips that comparison.
     */
    private fun startChangeCheck(document: Document, read: String?) {
        endChangeCheck()
        val uri = document.uri ?: return
        if (!OpenFileChangeCheck.isFolderNote(uri)) return
        checkedDocument = document.id to uri
        changeCheckJob = viewModelScope.launch {
            val readSize = read?.let { withContext(cpuDispatcher) { it.toByteArray(Charsets.UTF_8).size.toLong() } }
            if (changeCheck.changed(uri, readSize)) _changedOutside.value = true
        }
    }

    private fun endChangeCheck() {
        changeCheckJob?.cancel()
        changeCheckJob = null
        checkedDocument = null
        _changedOutside.value = false
    }

    /** The app went to the background: no more checks for this open. A banner already shown stays. */
    fun stopChangeCheck() {
        changeCheckJob?.cancel()
        changeCheckJob = null
    }

    /**
     * The banner's Reload: read the file again, at the stored reading position, as on any open of
     * it. Unsaved edits are dropped only when [discardEdits], after the View has asked; otherwise
     * text typed while the file is read is never replaced (M-180's Save/Discard prompt asks). The
     * banner stays until the new text is published, so a failed read can be tried again.
     */
    fun reloadChangedFile(discardEdits: Boolean) {
        if (_writeInFlight.value) return
        val uri = slot.document.value?.uri ?: return
        val claim = slot.claim()
        viewModelScope.launch {
            restoreJob.cancel()
            loadDocument(uri, claim, if (discardEdits) ReplacePolicy.DISCARD else ReplacePolicy.IF_CLEAN)
        }
    }

    /** The banner's Keep mine: the user's text stays, and a save writes it over the other version. */
    fun keepMyVersion() = endChangeCheck()

    override fun onCleared() {
        draftKeeper.flush()
        super.onCleared()
    }

    fun setLineNumbersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            storage.setLineNumbersEnabled(enabled)
        }
    }

    /** M-90: persist the "open documents in edit mode" preference. */
    fun setOpenInEditMode(enabled: Boolean) {
        viewModelScope.launch { storage.setOpenInEditMode(enabled) }
    }

    /**
     * M-91: open a blank, never-saved document straight in the editor.
     *
     * The document carries **no URI** — it has no file on disk and deliberately does not get one
     * here. It gets one from the existing Save-As path ([saveActiveDocumentAs]), which already
     * knows how to write to a newly created SAF location and adopt it as the document's identity.
     * Adding a second create-and-save path would duplicate the one piece of code every data-loss
     * safeguard in this app is concentrated in, which is exactly what M-91 says not to do.
     *
     * Always EDITOR regardless of the M-90 setting: a blank reader renders nothing, so opening
     * this in the reader would show an empty screen with no hint that anything happened.
     *
     * Nothing is persisted — no last-file URI, no recents entry. Both are keyed by URI, and a
     * document with no file has no business appearing in a list of files you can reopen.
     */
    fun newDocument() {
        viewModelScope.launch {
            // Claimed BEFORE anything can suspend, so a startup restore that is still reading its
            // file claimed earlier and loses the slot, however the two finish (M-109: the restore
            // used to publish over the new document and discard what the user had typed).
            val claim = slot.claim()
            // Stopped as [open] stops it (M-202). The claim keeps the restore from publishing, but a
            // restore still waiting to learn which file to reopen would start its load AFTER this
            // publish, put the screen back in `Loading` and, if its read failed, report that failure
            // over the new document.
            restoreJob.cancel()
            // Everything is known without I/O — no file, no override, an empty TOC — so the whole
            // document is published in one step, with no late write-back (the late `Success` this
            // used to emit after a heading pass could overwrite a newer load's `Error`). Retiring
            // a previous open failure's message is part of every publish (M-126).
            val kind = DocumentKind.fromDisplayName(NEW_DOCUMENT_NAME)
            val publication = Publication(
                document = Document(uri = null, content = "", dirty = false, displayName = NEW_DOCUMENT_NAME),
                lineEnding = "LF",
                transient = false,
                // Always the editor, whatever the M-90 setting: a blank reader shows nothing at all.
                switchToEditor = true,
                renderMode = defaultRenderMode(kind),
                plainToggleAvailable = kind == DocumentKind.PLAIN_TEXT,
                headings = emptyList(),
                previewScroll = ScrollAnchor(),
                resetEditorScroll = true,
            )
            // IF_CLEAN: Create MD File is offered only on the welcome screen, with nothing open, so
            // a refusal is unreachable today; if a dirty document were ever open, it is kept.
            if (slot.publish(claim, publication, ReplacePolicy.IF_CLEAN) == PublishOutcome.PUBLISHED) {
                loadingClaim = null // a load still running for the restore no longer owns the screen
                _fileLoadState.value = FileLoadState.Success
                // M-177: Create counts as an open.
                storage.incrementDocumentsOpened()
            }
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

    fun setWrapCodeLines(enabled: Boolean) {
        viewModelScope.launch { storage.setWrapCodeLines(enabled) }
    }

    fun setFullScreenReading(enabled: Boolean) {
        viewModelScope.launch { storage.setFullScreenReading(enabled) }
    }

    fun setFormattingBarEnabled(enabled: Boolean) {
        viewModelScope.launch { storage.setFormattingBarEnabled(enabled) }
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
     * pass forever no matter what production did. It takes a `CharSequence` for the same test, which
     * passes a wrapper that counts every character read (M-215); callers pass the document `String`.
     */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun detectLineEnding(content: CharSequence): String {
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
     * NEW-12: refuse to write [doc] over its file when that file is not UTF-8, because every save
     * writes UTF-8 and would change the file's bytes (Safeguards 1 and 2). Publishes
     * [FileLoadState.SaveRefusedNotUtf8] so the user is told why and offered Save-As; changes nothing
     * else, so the document stays open and dirty and no close or switch goes ahead. Returns whether
     * it refused. Called on every path that writes over the document's own file, before any write.
     */
    private fun refuseNotUtf8(doc: Document): Boolean {
        if (!doc.notUtf8) return false
        _fileLoadState.value = FileLoadState.SaveRefusedNotUtf8
        return true
    }

    /** NEW-12a: a copy was refused because the picked file is not a new one it may go to. */
    private suspend fun refusePickNewFile() = _fileLoadState.emit(FileLoadState.SaveRefusedPickNewFile)

    /**
     * The document's content as it must be written to disk: the editor/model's LF text with the
     * file's original line ending restored (Safeguard 2). Centralised so EVERY save path
     * (saveFile / saveAndClose / saveAndOpenPending) writes byte-identical content — a path that
     * passed raw doc.content would silently convert a CRLF file to LF.
     */
    private fun contentForDisk(doc: Document): String = applyLineEnding(doc.content, slot.lineEnding.value)

    /**
     * Apply the detected line-ending format before saving.
     * If format is "CRLF", converts every \n to \r\n. If format is "LF", leaves as-is.
     *
     * Only `\n` is converted, never `\r\n` as a unit (M-353). Load folds every `\r\n` to `\n`
     * ([prepareLoad]), so a freshly loaded model holds no CRLF of its own and every `\r` in it is a
     * byte the file really had: `\r\r\n` on disk is `\r\n` in the model and must go back as
     * `\r\r\n`. The previous `\r?\n` regex treated that surviving CR plus the restored LF as one
     * CRLF and wrote the CR away (33 bytes in, 32 out; Safeguard 2). The one way a `\r\n` of its
     * own can enter the model is text pasted into the editor, which is not normalised; that pair
     * is now written back as `\r\r\n`, i.e. as the bytes the model holds.
     */
    private fun applyLineEnding(text: String, format: String): String {
        return if (format == "CRLF") {
            text.replace("\n", "\r\n")
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
            val markdownContent = slot.document.value?.content
            if (markdownContent == null) {
                _exportState.value = ExportState.Error("No file open")
                return@launch
            }
            _exportState.value = ExportState.InProgress()
            // A failed export (large/odd content, write error) must surface an error,
            // never crash. The atomic write deletes the partial file on failure.
            runCatching {
                pdfExporter.exportToUri(markdownContent, previewFontScale.value, uri, slot.renderMode.value)
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
     * Consume a one-shot save outcome ([FileLoadState.SaveSuccess], [FileLoadState.SaveError] or
     * [FileLoadState.SaveRefusedNotUtf8]) once the UI has shown its feedback, so a later recomposition
     * or screen remount can't replay it. Only resets those terminal outcomes → Idle, so a newer
     * Saving/Loading that began meanwhile is never clobbered.
     */
    /**
     * Clear a reported load failure once it has been shown (**M-126**).
     *
     * Narrow on purpose: it clears **only** [FileLoadState.Error], so it can never swallow a
     * `Loading` that is still in flight or a terminal save state that its own consumer has not
     * read yet. Same shape as [resetSaveState], which clears only the save outcomes.
     */
    fun resetLoadErrorState() {
        _fileLoadState.update { if (it is FileLoadState.Error) FileLoadState.Idle else it }
    }

    fun resetSaveState() {
        _fileLoadState.update {
            if (it is FileLoadState.SaveOutcome) FileLoadState.Idle else it
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
                        drafts = container.draftRepository,
                        changeCheck = OpenFileChangeCheck(container.notesFolderRepository::noteStats),
                        prepareReaderModel = PrepareReaderModelUseCase(
                            parse = container.markwonRenderer::parseForReader,
                            headings = container.parseMarkdownHeadingsUseCase,
                            inlineText = container.markwonRenderer::readerInlineText,
                        ),
                    ) as T
                }
            }
        }
    }
}

/**
 * Default name for a never-saved document (M-91). It is shown in the toolbar and pre-fills the
 * Save-As dialog — NOT "Copy of …", which is the Save-As default for a document that has a source
 * file it must not overwrite. A new document has no source, so there is nothing to copy.
 */
const val NEW_DOCUMENT_NAME = "Untitled.md"

/** Whether [uri] is empty, as a file the picker has just created is. False if it cannot be read. */
private suspend fun FileRepository.isNewEmptyFile(uri: Uri): Boolean =
    readFile(uri).getOrNull()?.content?.isEmpty() == true

/** Whether [uri] reads as UTF-8, as an empty new file does. False if it cannot be read. */
private suspend fun FileRepository.readsAsUtf8(uri: Uri): Boolean = readFile(uri).getOrNull()?.isUtf8 == true

enum class ViewMode {
    READER,
    EDITOR,
}

sealed class FileLoadState {
    object Idle : FileLoadState()
    object Loading : FileLoadState()
    object Success : FileLoadState()
    object Saving : FileLoadState()
    object SaveSuccess : FileLoadState(), SaveOutcome

    /** A load failed. [tooLarge]: the file is over the read limit, which the UI says in words of its own. */
    data class Error(val message: String, val tooLarge: Boolean = false) : FileLoadState()
    data class SaveError(val message: String) :
        FileLoadState(),
        SaveOutcome

    /** A save's one-shot outcome, which the UI shows once and then resets with [MarkdownViewModel.resetSaveState]. */
    sealed interface SaveOutcome

    /** NEW-12: an in-place save was refused because the file is not UTF-8. Nothing was written. */
    object SaveRefusedNotUtf8 : FileLoadState(), SaveOutcome

    /**
     * NEW-12: a copy was refused because the picked file is not a new one it may go to (it has
     * content, is not UTF-8, or cannot be read). Nothing was written.
     */
    object SaveRefusedPickNewFile : FileLoadState(), SaveOutcome
}
