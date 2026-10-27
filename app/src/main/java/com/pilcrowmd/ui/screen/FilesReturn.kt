// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * What [filesReturnStep] decides: the open note counted as opened from My folders, the pick still
 * waiting to open, and whether to reopen My folders.
 */
internal data class FilesReturnStep(val openedFromFiles: Uri?, val pending: Uri?, val reopenFiles: Boolean)

/**
 * S8-A38: closing a note that was opened from My folders returns to My folders instead of Welcome.
 *
 * Called once per change of the open document, from [shown] (null: none) to [current].
 * [openedFromFiles] is the open note the user picked there; [pending] is a pick whose open has not
 * landed (it waits on the unsaved-changes prompt, or on the load). The two are kept apart so a pick
 * that never lands cannot take the flag from the note that is still open. A pick becomes the flag
 * only when it is what opens; the flag stops counting as soon as another note replaces it (a followed
 * link, a Recent), and after the one return.
 */
internal fun filesReturnStep(openedFromFiles: Uri?, pending: Uri?, shown: Uri?, current: Uri?): FilesReturnStep = when {
    current == shown -> FilesReturnStep(openedFromFiles, pending, reopenFiles = false)
    current == null && shown != null && shown == openedFromFiles -> FilesReturnStep(null, pending, reopenFiles = true)
    // Closed some other note: whatever is awaited is still awaited.
    current == null -> FilesReturnStep(openedFromFiles, pending, reopenFiles = false)
    current == pending -> FilesReturnStep(current, null, reopenFiles = false)
    // The same note shown again with nothing shown before: a rotation or a restore after process death.
    current == openedFromFiles -> FilesReturnStep(openedFromFiles, pending, reopenFiles = false)
    else -> FilesReturnStep(null, pending, reopenFiles = false)
}

/**
 * Remembers the note the user picked in My folders and, when the open document changes, asks
 * [filesReturnStep] whether to go back there ([onReturnToFiles]). The flag and the pick are saved, so
 * a rotation or a restore after process death keeps them; what is shown is not, because after process
 * death the ViewModel starts empty and a saved "shown" would read as that note being closed.
 * Returns the function that records a pick, and the one that forgets it when its open is cancelled.
 */
@Composable
internal fun FilesReturnEffect(documentUri: Uri?, onReturnToFiles: () -> Unit): FilesReturnPicks {
    var openedFromFiles by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pending by rememberSaveable { mutableStateOf<Uri?>(null) }
    var shown by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(documentUri) {
        val step = filesReturnStep(openedFromFiles, pending, shown, documentUri)
        openedFromFiles = step.openedFromFiles
        pending = step.pending
        shown = documentUri
        if (step.reopenFiles) onReturnToFiles()
    }
    return FilesReturnPicks(
        // The note already open is picked again: nothing will change, so nothing could land the pick.
        picked = { if (it == shown) openedFromFiles = it else pending = it },
        forget = { pending = null },
    )
}

/**
 * What the screen tells [FilesReturnEffect]:
 * the note picked in My folders, and that its open was cancelled or failed.
 */
internal class FilesReturnPicks(val picked: (Uri) -> Unit, val forget: () -> Unit)
