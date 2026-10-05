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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
     * Opaque per-instance identity (M-147). Defaulted and LAST so no construction site changes;
     * `copy()` carries it, so typing never changes identity. See [DocumentId].
     */
    val id: DocumentId = DocumentId.mint(),
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
     * Where [loadDocument]'s CPU work runs. Injectable ONLY so a test can assert that work leaves
     * the main thread (Play Vitals ANR, 1.0.3) — production always uses the default. Defaulted, so
     * `provideFactory` and the AppContainer are untouched.
     */
    private val cpuDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) : ViewModel() {
    // The open document and everything derived from it. This class READS all of it, but can
    // replace the document only through the slot's adjudicated doors (M-109) — see [DocumentSlot].
    private val slot = DocumentSlot()

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
            refreshHeadings(doc.id, doc.content)
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

    /** Headings feed the TOC drawer, for document [expected] only, and only if no newer pass has started. */
    private suspend fun refreshHeadings(expected: DocumentId, content: String) {
        val generation = ++headingsGeneration
        val headings = headingsOrEmpty(content, slot.renderMode.value)
        // A newer load, mode toggle or Save-As owns the TOC now; this result is for older content.
        if (generation == headingsGeneration) {
            slot.setRendering(expected, headings = headings)
        }
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

    // Opt-in Mermaid cloud rendering (default off).
    val mermaidCloudEnabled: StateFlow<Boolean> = storage.mermaidCloudEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Reader code blocks: wrap long lines instead of side-scrolling (default off, M-134).
    val wrapCodeLines: StateFlow<Boolean> = storage.wrapCodeLines
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
        if (lastUri != null) loadDocument(lastUri, claim, ReplacePolicy.IF_CLEAN)
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
        viewModelScope.launch {
            restoreJob.cancel()
            if (takePermission) repository.takePersistableUriPermission(uri)
            loadDocument(uri, claim, policy)
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
    private suspend fun loadDocument(uri: Uri, claim: Claim, policy: ReplacePolicy) {
        try {
            loadDocumentOrThrow(uri, claim, policy)
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

    private suspend fun loadDocumentOrThrow(uri: Uri, claim: Claim, policy: ReplacePolicy) {
        // A new attempt retires the last failure's message (M-126) — one place, because this is
        // the single point every load passes through.
        slot.markLoadFailure(false)
        loadingClaim = claim
        _fileLoadState.value = FileLoadState.Loading
        val file = repository.readFile(uri).getOrElse { error ->
            if (loadingClaim == claim) slot.markLoadFailure(true)
            finishLoad(claim, FileLoadState.Error(error.message ?: "Unknown error"))
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
                // The TOC is the one value allowed to arrive AFTER the document: heading extraction
                // is a full parse, about two seconds on a 25 MB file, and the reader must not wait
                // for it. UNDISPATCHED, so this pass is registered before anything else can start
                // one; a newer pass supersedes it and its result is dropped (refreshHeadings).
                viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    refreshHeadings(publication.document.id, content)
                }
                finishLoad(claim, FileLoadState.Success)
                // Persist as last file + record in recents. The pointer is written only while this
                // load is still what is on screen, so it cannot name a file the user moved away
                // from (M-145).
                if (slot.isLatestPublished(claim)) storage.saveLastFileUri(uri)
                storage.addRecent(RecentFile(uri, publication.document.displayName, System.currentTimeMillis()))
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

    /**
     * Bumped by every [refreshHeadings] pass, on Main. A pass applies its result only if the counter
     * still holds the value it took, so a slow parse of an earlier document (or of the other render
     * mode) can never overwrite the TOC of what is on screen now.
     */
    private var headingsGeneration = 0L

    /**
     * The TOC for the given content. **Safeguard 3:** a parser throw degrades to no TOC instead of
     * escaping — the load's pass runs in its own coroutine after the document is shown, where an
     * uncaught throw would crash the app. `Exception` only: an `Error` (OOM) still propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun headingsOrEmpty(content: String, renderMode: RenderMode): List<HeadingNode> {
        if (renderMode == RenderMode.PLAIN) return emptyList()
        return try {
            withContext(Dispatchers.Default) { parseHeadingsUseCase.extractHeadings(content) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MarkdownViewModel", "heading extraction failed; opening without a TOC", e)
            emptyList()
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
                    val result = repository.saveFile(target, contentForDisk(doc))
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
                repository.saveFile(target, contentForDisk(doc))
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

    fun toggleMode() {
        viewModelScope.launch {
            slot.mode.update { if (it == ViewMode.READER) ViewMode.EDITOR else ViewMode.READER }
        }
    }

    /** Set the view mode directly (used by the segmented Reader/Editor toggle). */
    fun setMode(mode: ViewMode) {
        viewModelScope.launch { slot.mode.value = mode }
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
        slot.editorCursor.value = offset
    }

    fun updateSearchQuery(query: String) {
        viewModelScope.launch {
            _searchQuery.emit(query)
            if (query.isNotEmpty()) {
                val content = slot.document.value?.content ?: return@launch
                // Search parses + scans the whole document — run it off the main thread so a
                // large file (or a common term with thousands of hits) can't freeze the UI / ANR.
                // Plain mode: the visible text IS the literal source, so match over the
                // same chunk split the plain render uses (adapter positions align).
                val matches = withContext(Dispatchers.Default) {
                    if (slot.renderMode.value == RenderMode.PLAIN) {
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
            if (visible) _searchVisible.emit(true) else closeSearch()
        }
    }

    private suspend fun closeSearch() {
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
                repository.saveFile(target, contentForDisk(doc))
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

                repository.saveFile(targetUri, contentForDisk(doc))
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
                            applyDerivedRenderMode(savedId, targetUri, displayName)
                            refreshHeadings(savedId, doc.content)
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

    fun setMermaidCloudEnabled(enabled: Boolean) {
        viewModelScope.launch { storage.setMermaidCloudEnabled(enabled) }
    }

    fun setWrapCodeLines(enabled: Boolean) {
        viewModelScope.launch { storage.setWrapCodeLines(enabled) }
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
    data class Error(val message: String) : FileLoadState()
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
