// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pilcrowmd.ui.components.FilesScreen
import com.pilcrowmd.viewmodel.DocumentId
import com.pilcrowmd.viewmodel.NotesFoldersViewModel

/**
 * M-162b: the Files screen wired to its ViewModel, kept out of [MainScreen] so the screen chain
 * there only gains a branch. Owns the notes-folder picker (apart from the pictures one) and the
 * L6 lifecycle: the folder is listed again on show and on every return to the app (ON_RESUME,
 * which also arrives once when the screen is first shown), and watched from start to stop and
 * no longer once Files closes. [openDocumentUri] is the reader's document, for the highlight;
 * [onOpenNote] gets the URI of a tapped note.
 */
@Composable
internal fun FilesRoute(
    viewModel: NotesFoldersViewModel,
    openDocumentUri: Uri?,
    onOpenNote: (Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The folder Find again replaces (F4); null when the picker adds a folder. Saved, so the answer
    // still goes to the right place if the activity is recreated while the picker is up.
    var findAgainFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val old = findAgainFor
        findAgainFor = null
        when {
            tree == null -> Unit
            old != null -> viewModel.findAgain(Uri.parse(old), tree)
            else -> viewModel.onFolderPicked(tree)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LifecycleStartEffect(viewModel) {
        viewModel.startWatching()
        onStopOrDispose { viewModel.stopWatching() }
    }
    val openNoteId = remember(openDocumentUri, state) { viewModel.openNoteId(openDocumentUri) }
    FilesScreen(
        state = state,
        modifier = modifier,
        openNoteId = openNoteId,
        onBack = onClose,
        onAddFolder = {
            findAgainFor = null
            picker.launch(null)
        },
        onSelectFolder = viewModel::select,
        onRemoveFolder = viewModel::remove,
        onFindAgain = { old ->
            findAgainFor = old.toString()
            picker.launch(null)
        },
        onMarkAllSeen = viewModel::markAllSeen,
        onToggleFolder = viewModel::toggleExpanded,
        onOpenNote = { id -> viewModel.noteUri(id)?.let(onOpenNote) },
        onRefresh = { viewModel.refresh(pulled = true) },
        onMessageShown = viewModel::messageShown,
    )
}

/**
 * M-162b (M5): tells [viewModel] when the reader shows a document, however it was opened, so its
 * mark goes. The document last shown survives rotation, so turning the phone is not a new open.
 * S8-A52: the same file read again (Reload) is a new [documentId], and counts as seen too.
 * (The app's own saves reach the ViewModel from the data layer, M4.)
 */
@Composable
internal fun NoteSeenEffect(viewModel: NotesFoldersViewModel?, documentUri: Uri?, documentId: DocumentId?) {
    var lastShown by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Never saved (a DocumentId is process-local): after a rotation or a restore it starts empty.
    var lastId by remember { mutableStateOf<DocumentId?>(null) }
    LaunchedEffect(viewModel, documentUri, documentId) {
        noteToMarkSeen(lastShown, lastId, documentUri, documentId)?.let { viewModel?.onNoteSeen(it) }
        lastShown = documentUri
        lastId = documentId
    }
}

/**
 * The document to mark seen, or null: [shown] when it was not the one shown before ([lastShown]),
 * or when it is that one read again (a load gives it a new identity: Reload, or opened again). A
 * document closed, discarded or still the same, saves and edits included, is not seen again.
 */
internal fun noteToMarkSeen(lastShown: Uri?, lastId: DocumentId?, shown: Uri?, shownId: DocumentId?): Uri? = when {
    shown == null -> null
    shown != lastShown -> shown
    lastId != null && shownId != lastId -> shown
    else -> null
}
