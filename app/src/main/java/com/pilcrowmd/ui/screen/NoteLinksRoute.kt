// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.pilcrowmd.R
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import com.pilcrowmd.viewmodel.NoteLinkEvent
import com.pilcrowmd.viewmodel.NoteLinksViewModel

/**
 * M-160: tells [linksViewModel] which note the reader shows ([content] null outside the reader, so the
 * K13/K14 bare links are worked out only for what the reader shows), and carries out each followed
 * link. A note opens through [viewModel]'s normal open, so an unsaved note gets the unsaved-changes
 * prompt first and nothing is saved or lost silently (K11). [showMessage] is the status toast.
 */
@Composable
internal fun NoteLinksEffect(
    linksViewModel: NoteLinksViewModel?,
    viewModel: MarkdownViewModel,
    documentUri: Uri?,
    content: String?,
    showMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentShowMessage = rememberUpdatedState(showMessage)
    LaunchedEffect(linksViewModel, documentUri, content) {
        linksViewModel?.onDocumentShown(documentUri, content)
    }
    // K13/K14: the reader's model is built with the bare links, so they go to the ViewModel that builds it.
    LaunchedEffect(linksViewModel) {
        linksViewModel?.let { forwardBareLinks(it, viewModel) }
    }
    LaunchedEffect(linksViewModel) {
        linksViewModel?.events?.collect { event ->
            handleNoteLinkEvent(event, viewModel, context, { uri, type -> openExternally(context, uri, type) }) {
                currentShowMessage.value(it)
            }
        }
    }
    // A link whose note could not be opened is over: opening that note another way later is not a link open.
    LaunchedEffect(linksViewModel) {
        linksViewModel?.let { dropFailedLinkOpens(it, viewModel) }
    }
}

/** K11 and failures: a followed link whose load failed leaves nothing waiting to be mistaken for a later open. */
internal suspend fun dropFailedLinkOpens(linksViewModel: NoteLinksViewModel, viewModel: MarkdownViewModel) {
    viewModel.fileLoadState.collect { if (it is FileLoadState.Error) linksViewModel.cancelPending() }
}

/** K13/K14: hands each set of bare links, as it is worked out, to the ViewModel whose model is built with it. */
internal suspend fun forwardBareLinks(linksViewModel: NoteLinksViewModel, viewModel: MarkdownViewModel) {
    linksViewModel.bareLinks.collect { viewModel.setBareLinks(it) }
}

/** Carries out one followed link (what [NoteLinksEffect] does with each event). */
internal fun handleNoteLinkEvent(
    event: NoteLinkEvent,
    viewModel: MarkdownViewModel,
    context: Context,
    openExternal: (Uri, String?) -> Boolean,
    showMessage: (String) -> Unit,
) {
    when (event) {
        // IF_CLEAN, like every open: a note with unsaved edits gets the prompt first (K11).
        is NoteLinkEvent.Open -> viewModel.loadFile(event.uri)
        is NoteLinkEvent.OpenExternal ->
            if (!openExternal(event.uri, event.mimeType)) showMessage(context.getString(R.string.note_link_cannot_open))
        is NoteLinkEvent.NotFound -> showMessage(context.getString(R.string.note_link_not_found, event.name))
        NoteLinkEvent.Fallback -> Unit
    }
}

/**
 * K10: Back after following a link opens the note it was followed from, through the normal open, so
 * unsaved edits get the prompt first; the reader restores that note's saved place.
 */
internal fun followLinkBack(linksViewModel: NoteLinksViewModel?, viewModel: MarkdownViewModel, currentUri: Uri?) {
    linksViewModel?.goBack(currentUri)?.let { viewModel.loadFile(it) }
}

/** K8: hands [uri] to the app the system picks, with read access; false when no app can open it. */
@Suppress("SwallowedException") // no app, or no access, is the answer, shown as "Can't open this file"
private fun openExternally(context: Context, uri: Uri, mimeType: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType != null) setDataAndType(uri, mimeType) else data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
