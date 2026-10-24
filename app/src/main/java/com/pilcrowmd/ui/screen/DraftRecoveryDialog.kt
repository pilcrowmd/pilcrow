// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.mdColors

/** When a draft was kept, for people: the time if it was today, else the date and time. */
internal fun draftTime(context: android.content.Context, millis: Long): String {
    val flags = android.text.format.DateUtils.FORMAT_SHOW_TIME or
        if (android.text.format.DateUtils.isToday(millis)) {
            0
        } else {
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH
        }
    return android.text.format.DateUtils.formatDateTime(context, millis, flags)
}

/** What the draft dialog says about the kept edits: which file, when, and whether the file moved on. */
internal data class DraftDialogInfo(val fileName: String, val time: String, val diskChanged: Boolean)

/**
 * Unsaved edits were kept from before the app was ended. Restore puts them in the editor (the file
 * is not touched until the user saves); Save a copy writes them to a new file; Discard throws them
 * away. [DraftDialogInfo.diskChanged] adds a warning that the file is no longer what the edits were typed on.
 * Cannot be dismissed without choosing: dismissing would leave the question open under the editor.
 */
@Composable
internal fun DraftRecoveryDialog(
    info: DraftDialogInfo,
    onRestore: () -> Unit,
    onSaveCopy: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        containerColor = mdColors().secondarySurface,
        titleContentColor = mdColors().primaryText,
        textContentColor = mdColors().secondaryText,
        title = { Text(stringResource(R.string.main_draft_title, info.fileName)) },
        text = {
            Text(
                stringResource(
                    if (info.diskChanged) R.string.main_draft_text_file_changed else R.string.main_draft_text,
                    info.time,
                ),
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onRestore) {
                    Text(stringResource(R.string.main_draft_restore), color = mdColors().primaryText)
                }
                TextButton(onClick = onSaveCopy) {
                    Text(stringResource(R.string.main_action_save_a_copy), color = mdColors().primaryText)
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.main_action_discard), color = mdColors().secondaryText)
                }
            }
        },
    )
}
