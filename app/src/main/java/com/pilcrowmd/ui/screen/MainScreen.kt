// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pilcrowmd.domain.model.ThemeMode
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.ui.components.EditorController
import com.pilcrowmd.ui.components.GitHubIntegrationScreen
import com.pilcrowmd.ui.components.HeadingsDrawer
import com.pilcrowmd.ui.components.LicensesScreen
import com.pilcrowmd.ui.components.MarkdownEditor
import com.pilcrowmd.ui.components.MarkdownPreview
import com.pilcrowmd.ui.components.PilcrowToolbar
import com.pilcrowmd.ui.components.SearchBar
import com.pilcrowmd.ui.components.SettingsScreen
import com.pilcrowmd.ui.components.WelcomeScreen
import com.pilcrowmd.ui.theme.DarkColorScheme
import com.pilcrowmd.ui.theme.FontSets
import com.pilcrowmd.ui.theme.LightColorScheme
import com.pilcrowmd.ui.theme.LocalMDColors
import com.pilcrowmd.ui.theme.mdColors
import com.pilcrowmd.viewmodel.ExportState
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.ViewMode
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch

/**
 * Main screen: toolbar + conditional preview/editor + welcome screen.
 * Wires up MarkdownViewModel state to UI components.
 * Integrates SAF file picker for open markdown file.
 * Preserves scroll and edits across mode toggle.
 * Implements code-block copy affordance setup.
 *
 * Architecture:
 * - Toolbar at top (44dp, fixed)
 * - Content area: welcome screen (no file) or Preview/Editor (file open)
 * - SAF file picker launched from welcome screen or toolbar action
 * - Mode toggle switches between READER and EDITOR
 * - Scroll position preserved in ViewModel (previewScroll/editorScroll)
 */
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MarkdownViewModel,
    context: Context,
    renderer: MarkwonRenderer,
) {
    // Collect state from ViewModel (collectAsStateWithLifecycle returns State<T>)
    val currentDocument = viewModel.currentDocument.collectAsStateWithLifecycle()
    val mode = viewModel.mode.collectAsStateWithLifecycle()
    val fileLoadState = viewModel.fileLoadState.collectAsStateWithLifecycle()
    // Transient = no persisted write grant (read-only "Open with"): show a banner + route Save→Save-As.
    val transient = viewModel.transient.collectAsStateWithLifecycle()
    // Stranded WAL slots (escape hatch): non-empty → persistent indicator; dialog when visible.
    val strandedSlots = viewModel.strandedSlots.collectAsStateWithLifecycle()
    val strandedDialogVisible = viewModel.strandedDialogVisible.collectAsStateWithLifecycle()
    val lineNumbersEnabled = viewModel.lineNumbersEnabled.collectAsStateWithLifecycle()
    // Separate preview/editor scales and selected font set.
    val previewFontScale = viewModel.previewFontScale.collectAsStateWithLifecycle()
    val editorFontScale = viewModel.editorFontScale.collectAsStateWithLifecycle()
    val fontSetId = viewModel.fontSetId.collectAsStateWithLifecycle()
    val fontSet = FontSets.byId(fontSetId.value)
    val mermaidCloudEnabled = viewModel.mermaidCloudEnabled.collectAsStateWithLifecycle()
    // Theme mode (Dark/Light)
    val themeMode = viewModel.themeMode.collectAsStateWithLifecycle()
    // appInfo injected from ViewModel (PackageManager call deferred to AppContainer init)
    val appInfo = viewModel.appInfo
    val previewScroll = viewModel.previewScroll.collectAsStateWithLifecycle()
    val editorScroll = viewModel.editorScroll.collectAsStateWithLifecycle()
    val editorCursor = viewModel.editorCursor.collectAsStateWithLifecycle()
    val recentFiles = viewModel.recentFiles.collectAsStateWithLifecycle()

    // Search state
    val searchQuery = viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchMatches = viewModel.searchMatches.collectAsStateWithLifecycle()
    val currentMatchIndex = viewModel.currentMatchIndex.collectAsStateWithLifecycle()
    val searchVisible = viewModel.searchVisible.collectAsStateWithLifecycle()

    // Warm-intent file waiting on the unsaved-changes prompt
    val pendingOpenUri = viewModel.pendingOpenUri.collectAsStateWithLifecycle()

    // TOC state
    val headings = viewModel.headings.collectAsStateWithLifecycle()
    val headingJump = viewModel.headingJump.collectAsStateWithLifecycle()

    // Export result feedback (success/error) — surfaced via the centered status toast below.
    val exportState = viewModel.exportState.collectAsStateWithLifecycle()

    // The TOC slide-in drawer is driven directly by this DrawerState (single source of
    // truth): the toolbar book icon opens it, tapping a heading closes it, and swipe-to-open
    // still works in Reader mode. (The earlier viewModel.tocVisible flag never opened the
    // drawer — only the swipe gesture did.)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Unsaved-changes guard for the Close (X) action. rememberSaveable so the dialog survives a
    // config-change recreate (rotation) — otherwise the user's save/discard decision point silently
    // vanishes on rotate. Matches showSettings/showLicenses + pendingRescueSlotKey below.
    var showCloseConfirm by rememberSaveable { mutableStateOf(false) }

    // Double-back-to-exit guard for the home/root screen (Fix: Back must navigate within the app,
    // not terminate it). Armed by the first root Back press; auto-disarms after a short window.
    var exitArmed by remember { mutableStateOf(false) }

    // Transient centered status toast (save + export feedback): the message, whether it's an error,
    // and a visible flag.
    var statusToastText by remember { mutableStateOf("") }
    var statusToastError by remember { mutableStateOf(false) }
    var statusToastVisible by remember { mutableStateOf(false) }

    // Settings screen visibility. rememberSaveable so the modal survives a config
    // change / rotation.
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Licenses screen visibility (mirror of showSettings for independent modal).
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    // GitHub-integration teaser sub-screen visibility (mirror of showLicenses).
    var showGitHub by rememberSaveable { mutableStateOf(false) }

    // Hoist the CodeEditor instance to MainScreen level so the native undo stack
    // survives a Reader⇄Editor mode toggle (if the CodeEditor were in Editor's remember, it would be
    // destroyed when Editor leaves composition in Reader mode).
    val soraCodeEditor = remember {
        CodeEditor(context)
    }
    // Release the hoisted CodeEditor when MainScreen leaves composition (app exit / Activity
    // destroy) so its native resources + Context reference are freed (guards the leak surface
    // that LeakCanary watches). DisposableEffect(Unit) only fires on true teardown, not mode toggles.
    DisposableEffect(Unit) {
        onDispose {
            try {
                soraCodeEditor.release()
            } catch (
                e: Exception,
            ) {
                Log.w("MainScreen", "editor release failed: ${e.message}")
            }
        }
    }

    // Thin editor-control abstraction wrapping Sora internals.
    // Key the remember on soraCodeEditor: if the Compose runtime ever
    // recreates the underlying CodeEditor (incl. null -> instance), the controller is rebuilt
    // and can never hold a stale/detached View reference.
    val editorController = remember(soraCodeEditor) {
        EditorController(soraCodeEditor)
    }

    // Drive both the system-bar icon appearance AND the bar background from the active theme.
    // Light theme → dark icons (legible on cream); Dark theme → light icons (legible on dark).
    //
    // The bar BACKGROUND is the missing piece behind QA #3 (status bar invisible in light theme on
    // Android 12 / OPPO A15): on API ≤34 the system draws the status bar with the activity theme's
    // default statusBarColor (a dark Material value), so light-theme dark icons sat on a dark bar and
    // vanished. Setting statusBarColor/navigationBarColor to the active theme's primaryBackground (the
    // token layer — Safeguard 4, no hardcoded hex) makes the bar match the app in both themes. On
    // API 35+ these setters are deprecated no-ops (edge-to-edge transparent bars), where the Column's
    // primaryBackground already shows through — so this is purely an API ≤34 correctness fix.
    val view = LocalView.current
    val lightSystemBars = themeMode.value == ThemeMode.LIGHT
    val systemBarColor = (if (lightSystemBars) LightColorScheme else DarkColorScheme).primaryBackground
    androidx.compose.runtime.LaunchedEffect(lightSystemBars) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        applyLegacySystemBarColors(window, systemBarColor.toArgb())
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
    }

    // Close the TOC drawer whenever the open file changes (e.g. a warm-intent open, which can
    // otherwise leave the previous file's drawer on screen). A new document shouldn't show the
    // old document's table of contents.
    androidx.compose.runtime.LaunchedEffect(currentDocument.value?.uri) {
        drawerState.close()
    }

    // Auto-disarm the double-back exit guard ~2s after it was armed, so a stray first Back press
    // never silently primes a later accidental exit.
    androidx.compose.runtime.LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(EXIT_CONFIRM_WINDOW_MS)
            exitArmed = false
        }
    }

    // System/gesture Back: navigate within the app instead of terminating it. A single handler
    // executes the pure-resolved BackIntent (precedence in resolveBackIntent). Always enabled — even
    // at root we intercept for the double-back exit guard. (AlertDialogs consume Back themselves, so
    // the unsaved-close / stranded-slot prompts are dismissed by the framework before this runs.)
    BackHandler {
        val intent = resolveBackIntent(
            BackNavState(
                searchVisible = searchVisible.value,
                drawerOpen = drawerState.isOpen,
                showLicenses = showLicenses,
                showGitHub = showGitHub,
                showSettings = showSettings,
                hasDocument = currentDocument.value != null,
                documentDirty = currentDocument.value?.dirty == true,
                exitArmed = exitArmed,
            ),
        )
        when (intent) {
            BackIntent.CloseSearch -> viewModel.setSearchVisible(false)
            BackIntent.CloseDrawer -> scope.launch { drawerState.close() }
            BackIntent.LicensesToSettings -> {
                showLicenses = false
                showSettings = true
            }
            BackIntent.GitHubToSettings -> {
                showGitHub = false
                showSettings = true
            }
            BackIntent.CloseSettings -> showSettings = false
            // Reuse the existing unsaved-edits guard — never invent a new save dialog here.
            BackIntent.PromptUnsavedClose -> showCloseConfirm = true
            BackIntent.CloseFile -> viewModel.closeFile()
            BackIntent.ArmExit -> {
                exitArmed = true
                Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
            BackIntent.Exit -> (context as? android.app.Activity)?.finish()
        }
    }

    // Drive editor to the current search match when in Editor mode and match changes.
    // Delegate offset math + editor internals to EditorController.
    androidx.compose.runtime.LaunchedEffect(currentMatchIndex.value, mode.value) {
        if (mode.value == ViewMode.EDITOR) {
            val matchOffset = viewModel.getCurrentMatchOffset()
            val match = searchMatches.value.getOrNull(currentMatchIndex.value)
            val content = currentDocument.value?.content ?: ""
            if (matchOffset >= 0 && match != null) {
                val endOffset = matchOffset + match.content.length
                editorController.selectAndRevealMatch(matchOffset, endOffset, content.length)
            }
        }
    }

    // Drive editor to the selected heading when in Editor mode.
    // Delegate heading-marker search + offset math + editor internals to EditorController.
    androidx.compose.runtime.LaunchedEffect(headingJump.value?.seq, mode.value) {
        if (mode.value == ViewMode.EDITOR && headingJump.value != null) {
            val selectedHeading = headings.value.find { it.adapterPosition == headingJump.value!!.position }
            val content = currentDocument.value?.content ?: ""
            if (selectedHeading != null) {
                editorController.scrollToHeading(selectedHeading.level, selectedHeading.text, content, content.length)
            }
        }
    }

    // Transient centered status toast — visible over the editor. (The bottom SnackBar was unreliable:
    // hidden behind the keyboard while editing, and under the gesture nav bar otherwise.) "Saved" /
    // "Exported" use the accent background; "Save failed" / "Export failed" use the red error
    // background, so a failure is never silent (Safeguard 1). Each one-shot outcome is consumed
    // immediately (reset → Idle) so a recomposition / screen remount can't replay it.
    androidx.compose.runtime.LaunchedEffect(fileLoadState.value) {
        when (fileLoadState.value) {
            is FileLoadState.SaveSuccess -> {
                statusToastText = "Saved"
                statusToastError = false
                statusToastVisible = true
                viewModel.resetSaveState()
            }
            is FileLoadState.SaveError -> {
                statusToastText = "Save failed"
                statusToastError = true
                statusToastVisible = true
                viewModel.resetSaveState()
            }
            else -> {}
        }
    }
    androidx.compose.runtime.LaunchedEffect(exportState.value) {
        when (exportState.value) {
            is ExportState.Success -> {
                statusToastText = "Exported"
                statusToastError = false
                statusToastVisible = true
                viewModel.resetExportState()
            }
            is ExportState.Error -> {
                statusToastText = "Export failed"
                statusToastError = true
                statusToastVisible = true
                viewModel.resetExportState()
            }
            else -> {} // Idle / InProgress — no message
        }
    }
    // Auto-hide keyed on the LOCAL toast state (not the volatile flows), so a mid-window state change
    // can't strand it visible. A failure lingers a little longer so it can't be missed.
    androidx.compose.runtime.LaunchedEffect(statusToastVisible, statusToastError) {
        if (statusToastVisible) {
            kotlinx.coroutines.delay(if (statusToastError) 2800L else 1500L)
            statusToastVisible = false
        }
    }

    // SAF file picker (launches ACTION_OPEN_DOCUMENT)
    // Accept text/markdown files (.md, .markdown, .txt)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Persist read/write THEN load in one sequence so the transient check sees the granted
            // write permission (avoids a race that could mis-flag a freshly-picked file as transient).
            viewModel.openPickedFile(uri)
        }
    }

    // "Browse all files" fallback picker. Uses OpenAnyDocument (no EXTRA_MIME_TYPES) so EVERY openable
    // file is selectable — OpenDocument's `arrayOf("*/*")` left non-Markdown files greyed on Samsung's
    // picker (it doesn't treat a literal "*/*" mime entry as "all"; S24+ UAT). Same read-only load.
    val openAnyFileLauncher = rememberLauncherForActivityResult(
        contract = OpenAnyDocument(),
    ) { uri ->
        if (uri != null) viewModel.openPickedFile(uri)
    }

    // PDF export launcher (launches ACTION_CREATE_DOCUMENT)
    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            // Trigger the PDF export via ViewModel
            viewModel.exportPdf(uri)
        }
    }

    // When non-null, the next CreateDocument result rescues this stranded WAL slot
    // instead of saving the active document — so the ONE shared launcher serves both paths.
    // rememberSaveable so it SURVIVES the Activity recreate that happens if the device is rotated
    // while the system file picker is foregrounded — otherwise the key would reset to null and the
    // picker result would misroute to the document Save-As (writing the wrong content).
    var pendingRescueSlotKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Save-As / "Save a copy" launcher (ACTION_CREATE_DOCUMENT). Mirrors the PDF launcher. Dispatches
    // by what initiated the pick: a stranded-slot rescue (raw WAL bytes, never touches the open doc)
    // when pendingRescueSlotKey is set, else the document Save-As (write + adopt).
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val rescueKey = pendingRescueSlotKey
        if (uri != null) {
            if (rescueKey != null) viewModel.rescueStrandedSlot(uri, rescueKey) else viewModel.saveActiveDocumentAs(uri)
        }
        pendingRescueSlotKey = null // clear regardless (incl. picker cancel) so a later Save-As isn't misrouted
    }
    // Default filename for the create dialog: a "Copy of <name>.md" so the suggested name never
    // collides with the source (Save-As is "save a COPY"). Critical for a transient doc opened from
    // the Download folder: defaulting to the original name invites overwriting the user's own file.
    // (baseName from the display name — a content-URI lastPathSegment is a doc ID like "msf:47".)
    val launchSaveAs = {
        val baseName = currentDocument.value?.displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
        saveAsLauncher.launch("Copy of ${baseName ?: "document"}.md")
    }
    // Rescue a stranded slot: route the next picker result to rescueStrandedSlot(slotKey).
    val launchRescue = { slot: com.pilcrowmd.repository.StrandedSlot ->
        pendingRescueSlotKey = slot.key
        val baseName = slot.displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
        saveAsLauncher.launch("Recovered ${baseName ?: "document"}.md")
    }

    // UI hierarchy.
    // background() fills the whole window (incl. behind the status bar) with the dark
    // primary background; systemBarsPadding() then insets the toolbar + content below the
    // status bar and above the nav bar (API 35 enforces edge-to-edge — without this the
    // toolbar renders under the system clock/battery and its controls are unreachable).
    val c = mdColors()
    val activeColorScheme = when (themeMode.value) {
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.LIGHT -> LightColorScheme
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Button-to-open, swipe-to-close. Gestures enabled ONLY while the drawer is
        // open — so edge-swipe can't accidentally OPEN the TOC during normal reading (the toolbar
        // book button opens it via drawerState.open(), unaffected by this flag), while swipe/drag
        // still CLOSES it. Replaces the over-sensitive always-on edge-swipe-to-open.
        gesturesEnabled = drawerState.isOpen,
        // Render the drawer content UNCONDITIONALLY so its measured width stays a constant 280dp
        // across the empty→non-empty heading transition. Gating on headings.isNotEmpty() made the
        // content jump 0→280dp as a file loads, which re-anchored ModalNavigationDrawer and sprang
        // it OPEN (then a reactive close produced a visible flash). A stable width never re-anchors;
        // an empty heading list simply renders the title with no rows.
        drawerContent = {
            HeadingsDrawer(
                headings = headings.value,
                onHeadingSelected = { heading ->
                    viewModel.jumpToHeading(heading)
                    scope.launch { drawerState.close() }
                },
            )
        },
        scrimColor = c.scrimOverlay,
        modifier = modifier.fillMaxSize(),
    ) {
        CompositionLocalProvider(LocalMDColors provides activeColorScheme) {
            // Box wrapper so the "Saved" message can stack ON TOP of the content as a true
            // overlay. (If the message lived in the Column below, its fillMaxSize would steal
            // the weight(1f) content's height and make everything vanish while it showed.)
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(mdColors().primaryBackground)
                        .systemBarsPadding(),
                ) {
                    // Persistent stranded-WAL-slot indicator (escape hatch): shows on every screen
                    // (welcome + document) whenever recovery files await action, and opens the dialog
                    // on tap. The dialog itself (auto-popped on a cold launcher start, else opened
                    // from here) lives at the Box level below.
                    if (strandedSlots.value.isNotEmpty()) {
                        StrandedSlotIndicator(
                            count = strandedSlots.value.size,
                            onClick = { viewModel.showStrandedDialog() },
                        )
                    }
                    // Toolbar only when a file is open — the welcome screen has its own Open
                    // action, so toggle/save/close would be no-ops there.
                    if (currentDocument.value != null) {
                        PilcrowToolbar(
                            currentMode = mode.value,
                            onModeSelected = { selected -> viewModel.setMode(selected) },
                            // A transient doc (read-only "Open with") can't save in place → route Save to
                            // Save-As; otherwise the normal in-place save.
                            onSave = { if (transient.value) launchSaveAs() else viewModel.saveFile() },
                            onSaveACopy = { launchSaveAs() },
                            onClose = {
                                // Safeguard against silent data loss: prompt if there are unsaved edits.
                                if (currentDocument.value?.dirty == true) {
                                    showCloseConfirm = true
                                } else {
                                    viewModel.closeFile()
                                }
                            },
                            onSearch = { viewModel.setSearchVisible(true) },
                            onTOC = { scope.launch { drawerState.open() } },
                            onExportPdf = {
                                // Launch SAF CREATE_DOCUMENT dialog for PDF export. Default name = the
                                // open doc's SAF display name with its extension swapped for .pdf (a
                                // content-URI lastPathSegment is a doc ID like "msf:47", not a name).
                                val baseName = currentDocument.value?.displayName
                                    ?.substringBeforeLast('.')
                                    ?.takeIf { it.isNotBlank() }
                                exportPdfLauncher.launch("${baseName ?: "document"}.pdf")
                            },
                            isSaving = fileLoadState.value is FileLoadState.Saving,
                            onUndo = if (mode.value == ViewMode.EDITOR) ({ soraCodeEditor.undo() }) else null,
                            onRedo = if (mode.value == ViewMode.EDITOR) ({ soraCodeEditor.redo() }) else null,
                        )

                        // Transient (read-only "Open with") banner: opened from another app, no
                        // persisted write grant → won't stay in Recents and can't save in place. Tapping
                        // it (or Save) routes to "Save a copy". Informational, never auto-pops the picker.
                        if (transient.value) {
                            TransientBanner(
                                enabled = fileLoadState.value !is FileLoadState.Saving,
                                onClick = { launchSaveAs() },
                            )
                        }

                        // Conditionally render search bar when visible (works in both Reader and Editor modes)
                        if (searchVisible.value) {
                            SearchBar(
                                query = searchQuery.value,
                                onQueryChange = { viewModel.updateSearchQuery(it) },
                                matchCount = searchMatches.value.size,
                                currentIndex = currentMatchIndex.value,
                                onPrevious = { viewModel.previousMatch() },
                                onNext = { viewModel.nextMatch() },
                                onClose = { viewModel.setSearchVisible(false) },
                            )
                        }
                    }

                    // Settings screen (full-screen modal)
                    if (showSettings) {
                        SettingsScreen(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            fontSetId = fontSetId.value,
                            onFontSetSelected = { viewModel.setFontSet(it) },
                            previewFontScale = previewFontScale.value,
                            onPreviewFontScaleChanged = { viewModel.setPreviewFontScale(it) },
                            editorFontScale = editorFontScale.value,
                            onEditorFontScaleChanged = { viewModel.setEditorFontScale(it) },
                            lineNumbersEnabled = lineNumbersEnabled.value,
                            onLineNumbersChanged = { viewModel.setLineNumbersEnabled(it) },
                            mermaidCloudEnabled = mermaidCloudEnabled.value,
                            onMermaidCloudChanged = { viewModel.setMermaidCloudEnabled(it) },
                            themeMode = themeMode.value,
                            onThemeSelected = { viewModel.setThemeMode(it) },
                            appVersion = appInfo.versionName,
                            onClose = { showSettings = false },
                            // Close Settings as we open Licenses — the modal blocks are checked
                            // showSettings-first, so leaving it true would keep Settings on top.
                            onOpenLicenses = {
                                showSettings = false
                                showLicenses = true
                            },
                            // Same pattern as Licenses: close Settings, open the GitHub teaser.
                            onOpenGitHub = {
                                showSettings = false
                                showGitHub = true
                            },
                        )
                    } else if (showLicenses) {
                        LicensesScreen(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            // Back from Licenses returns to Settings (where the user came from).
                            onClose = {
                                showLicenses = false
                                showSettings = true
                            },
                        )
                    } else if (showGitHub) {
                        GitHubIntegrationScreen(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            // Back from the GitHub teaser returns to Settings (where the user came from).
                            onClose = {
                                showGitHub = false
                                showSettings = true
                            },
                        )
                    } else {
                        // Main content area — weight(1f) gives it exactly the space BELOW the toolbar.
                        // clipToBounds() is essential: the preview hosts a RecyclerView via AndroidView, and
                        // interop views can paint outside their layout bounds (here, up over the toolbar).
                        // Clipping confines that draw to this box so content never overlaps the toolbar/search bar.
                        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            if (currentDocument.value == null) {
                                // Welcome screen (no file open)
                                WelcomeScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    recentFiles = recentFiles.value,
                                    onOpenFile = {
                                        // Default: soft-filter the picker to text/Markdown so document
                                        // files surface and binaries (PNG/PDF) don't. Providers that
                                        // mislabel .md as application/octet-stream are served by the
                                        // "Browse all files" fallback below (onOpenAnyFile).
                                        filePickerLauncher.launch(
                                            arrayOf("text/markdown", "text/plain", "text/*"),
                                        )
                                    },
                                    onOpenAnyFile = {
                                        // Show-all fallback for a mislabeled .md (octet-stream/etc.).
                                        // Uses the no-MIME-filter contract so every file is selectable;
                                        // same read-only load path — opening never writes to the file.
                                        openAnyFileLauncher.launch(Unit)
                                    },
                                    onOpenRecent = { uri -> viewModel.loadFile(uri) },
                                    onRemoveRecent = { uri -> viewModel.removeRecent(uri) },
                                    onClearRecents = { viewModel.clearRecents() },
                                    onOpenSettings = { showSettings = true },
                                )
                            } else {
                                // File is open: show preview or editor based on mode
                                // Scroll position and edits preserved across mode toggles
                                when (mode.value) {
                                    ViewMode.READER -> {
                                        // Preview mode (block-level RecyclerView). Restores scroll
                                        // from previewScroll and reports changes via callback.
                                        // imePadding so the open search keyboard insets the preview
                                        // (like the editor already does) — otherwise a scrolled-to
                                        // search match near the document end is painted correctly but
                                        // hidden behind the keyboard with no room to scroll above it.
                                        MarkdownPreview(
                                            modifier = Modifier.fillMaxSize().imePadding(),
                                            content = currentDocument.value!!.content,
                                            renderer = renderer,
                                            fontScale = previewFontScale.value,
                                            fontSet = fontSet,
                                            mermaidCloudEnabled = mermaidCloudEnabled.value,
                                            scrollPosition = previewScroll.value,
                                            onScrollChanged = { position ->
                                                viewModel.updatePreviewScroll(position)
                                            },
                                            // Pinch-to-zoom commits the new preview scale through the
                                            // same setting the Settings A−/A+ slider writes (stays in sync).
                                            onFontScaleChange = { viewModel.setPreviewFontScale(it) },
                                            searchMatches = searchMatches.value,
                                            currentMatchIndex = currentMatchIndex.value,
                                            jumpPosition = headingJump.value?.position ?: -1,
                                            jumpSeq = headingJump.value?.seq ?: 0,
                                        )
                                    }
                                    ViewMode.EDITOR -> {
                                        // Editor mode: editable source with line numbers + One Dark highlighting.
                                        // Restores scroll from editorScroll.
                                        // key(uri): the editor owns its TextFieldValue locally; re-seed it only
                                        // when a different file is opened, never on every keystroke echo.
                                        key(currentDocument.value!!.uri) {
                                            MarkdownEditor(
                                                modifier = Modifier.fillMaxSize(),
                                                content = currentDocument.value!!.content,
                                                onContentChange = { newContent ->
                                                    // Update ViewModel: marks content dirty, flows to UI
                                                    viewModel.updateContent(newContent)
                                                },
                                                lineNumbersEnabled = lineNumbersEnabled.value,
                                                fontScale = editorFontScale.value,
                                                fontSet = fontSet,
                                                themeMode = themeMode.value,
                                                scrollPosition = editorScroll.value,
                                                onScrollChanged = { position ->
                                                    viewModel.updateEditorScroll(position)
                                                },
                                                initialCursor = editorCursor.value,
                                                onCursorChange = { offset ->
                                                    viewModel.updateEditorCursor(offset)
                                                },
                                                codeEditorInstance = soraCodeEditor,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Unsaved-changes guard. Closing a dirty file asks to Save or Discard
                    // rather than silently dropping edits. Save-then-close happens in one VM
                    // coroutine; a failed save keeps the file open and surfaces the error
                    // (Safeguard 1: never lose the user's content).
                    if (showCloseConfirm) {
                        AlertDialog(
                            onDismissRequest = { showCloseConfirm = false },
                            containerColor = mdColors().secondarySurface,
                            titleContentColor = mdColors().primaryText,
                            textContentColor = mdColors().secondaryText,
                            title = { Text("Unsaved changes") },
                            text = { Text("You have unsaved edits. Save before closing?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCloseConfirm = false
                                    // A transient doc can't save in place → route to Save-As (adopts a
                                    // persistable copy; the user can then close it normally). Otherwise
                                    // the atomic save-then-close.
                                    if (transient.value) launchSaveAs() else viewModel.saveAndClose()
                                }) {
                                    Text("Save", color = mdColors().primaryText)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showCloseConfirm = false
                                    viewModel.closeFile()
                                }) {
                                    Text("Discard", color = mdColors().secondaryText)
                                }
                            },
                        )
                    }

                    // Warm-intent open while the current file has unsaved edits. Same
                    // Save/Discard guard as Close, so switching files can't silently drop edits.
                    if (pendingOpenUri.value != null) {
                        AlertDialog(
                            onDismissRequest = { viewModel.cancelPendingOpen() },
                            containerColor = mdColors().secondarySurface,
                            titleContentColor = mdColors().primaryText,
                            textContentColor = mdColors().secondaryText,
                            title = { Text("Unsaved changes") },
                            text = { Text("Open the new file? Save your current edits first, or discard them.") },
                            confirmButton = {
                                // A transient doc can't save in place → route to Save-As (adopts a
                                // persistable copy); the prompt stays up so a second Save (now clean)
                                // proceeds to open the pending file. Otherwise the atomic save-then-open.
                                TextButton(onClick = {
                                    if (transient.value) launchSaveAs() else viewModel.saveAndOpenPending()
                                }) {
                                    Text("Save", color = mdColors().primaryText)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.discardAndOpenPending() }) {
                                    Text("Discard", color = mdColors().secondaryText)
                                }
                            },
                        )
                    }

                    // Stranded-WAL-slot dialog (escape hatch): lists 1..N stranded slots, each with its
                    // own "Save a copy" (rescue raw bytes to a new file) and "Discard". Auto-popped on a
                    // cold launcher start with slots; otherwise opened from the persistent indicator.
                    // Closes itself when the last slot is rescued/discarded (list goes empty). Never
                    // auto-discards — dismiss leaves every slot intact.
                    if (strandedDialogVisible.value && strandedSlots.value.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissStrandedDialog() },
                            containerColor = mdColors().secondarySurface,
                            titleContentColor = mdColors().primaryText,
                            textContentColor = mdColors().secondaryText,
                            title = { Text("Recover unsaved files") },
                            text = {
                                Column {
                                    Text(
                                        "These saves couldn't be written to their original location. " +
                                            "Save a copy of each to keep it, or discard it.",
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    strandedSlots.value.forEach { slot ->
                                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                            Text(slot.displayName, color = mdColors().primaryText)
                                            Row {
                                                TextButton(onClick = { launchRescue(slot) }) {
                                                    Text("Save a copy", color = mdColors().primaryText)
                                                }
                                                TextButton(onClick = { viewModel.discardStrandedSlot(slot.key) }) {
                                                    Text("Discard", color = mdColors().secondaryText)
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.dismissStrandedDialog() }) {
                                    Text("Close", color = mdColors().primaryText)
                                }
                            },
                        )
                    }
                }

                // Transient centered save-status message. Lives at the Box level (sibling of the
                // Column above) so it overlays the content instead of displacing it. Accent
                // background on success, red on failure — clearly visible over the editor.
                AnimatedVisibility(
                    visible = statusToastVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = statusToastText,
                        color = if (statusToastError) mdColors().onError else mdColors().onAccent,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .background(
                                if (statusToastError) mdColors().error else mdColors().accent,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Banner shown below the toolbar when the open document is transient — opened read-only via
 * "Open with", so it holds no persisted write grant: it can't be saved in place. Accent (purple)
 * background with white text so it reads as an actionable prompt; the trailing "…" signals it's
 * tappable. Tapping it (like the Save button and the overflow "Save a copy") launches Save-As; it
 * never auto-pops the picker (deliberate UX decision). Colours come only from the token layer (Safeguard 4).
 */
@Composable
private fun TransientBanner(onClick: () -> Unit, enabled: Boolean = true) {
    val c = mdColors()
    // Dim the accent + text while disabled (a save is in flight) so the temporarily-inert banner
    // reads as deactivated rather than a live tap target.
    val background = if (enabled) c.accent else c.accent.copy(alpha = TRANSIENT_BANNER_DISABLED_ALPHA)
    val textColor = if (enabled) c.onAccent else c.onAccent.copy(alpha = TRANSIENT_BANNER_DISABLED_ALPHA)
    Text(
        text = "Temporary file opened from another app.\nTap to Save a copy…",
        color = textColor,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private const val TRANSIENT_BANNER_DISABLED_ALPHA = 0.5f

/**
 * Persistent bar shown on every screen while stranded WAL slots exist (the escape hatch) — saves
 * that couldn't reach their original location and need the user to save a copy or discard. Tapping it
 * opens the recovery dialog. Uses the error token so it's clearly distinct from the accent transient
 * banner (Safeguard 4 — colours only from the token layer). Content is never at risk; this just makes
 * the otherwise-silent stranded state actionable.
 */
@Composable
private fun StrandedSlotIndicator(count: Int, onClick: () -> Unit) {
    val c = mdColors()
    val label = if (count == 1) {
        "1 recovered file to save — tap to resolve"
    } else {
        "$count recovered files to save — tap to resolve"
    }
    Text(
        text = label,
        color = c.onError,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.error)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * "Open any document" SAF contract — like [ActivityResultContracts.OpenDocument] but with **no**
 * `EXTRA_MIME_TYPES`, so the system picker leaves every openable file selectable. The standard
 * `OpenDocument` always sets `EXTRA_MIME_TYPES`, and some providers (notably Samsung's picker) do not
 * treat a literal all-types wildcard entry there as "all" — they keep non-matching files greyed out.
 * Omitting the extra entirely (only the wildcard `type`) is the reliable "show all files" path that
 * backs the "Browse all files" fallback for a mislabeled `.md` (S24+ UAT). Read-only — never writes.
 */
private class OpenAnyDocument : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * Set the status- and navigation-bar background to [color] (ARGB). `Window.statusBarColor` /
 * `navigationBarColor` are deprecated on API 35+ (where edge-to-edge makes the bars transparent and
 * these are no-ops), but on API ≤34 they are the only way to colour the system-drawn bars — needed so
 * the bar background follows the app theme (QA #3). Isolated here so the deprecation suppression is
 * narrow and the call site stays clean.
 */
@Suppress("DEPRECATION")
private fun applyLegacySystemBarColors(window: android.view.Window, color: Int) {
    window.statusBarColor = color
    window.navigationBarColor = color
}
