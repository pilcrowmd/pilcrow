// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.mdColors

/**
 * S8-A46: the open file changed on another device soon after it was opened. With no unsaved edits
 * it is the temporary-file banner ([TransientBanner]) with its own text: tap to reload, or close it.
 * With unsaved edits it offers the two choices, Reload (the caller asks first: the edits are lost)
 * and Keep mine, in the same strip. Colours come only from the token layer (Safeguard 4).
 */
@Composable
internal fun ChangedFileBanner(
    hasEdits: Boolean,
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
    enabled: Boolean = true,
) {
    if (!hasEdits) {
        TransientBanner(
            text = stringResource(R.string.main_banner_changed),
            enabled = enabled,
            onClick = onReload,
            onClose = onKeepMine,
        )
        return
    }
    val c = mdColors()
    Column(modifier = Modifier.fillMaxWidth().background(c.border)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(c.secondarySurface).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.main_banner_changed_edits),
                color = c.primaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onReload, enabled = enabled) {
                Text(stringResource(R.string.main_action_reload), color = c.accent, fontSize = 13.sp)
            }
            TextButton(onClick = onKeepMine, enabled = enabled) {
                Text(stringResource(R.string.main_action_keep_mine), color = c.primaryText, fontSize = 13.sp)
            }
        }
        // The 1 dp line under the strip, in the border token.
        Spacer(Modifier.height(1.dp))
    }
}

/**
 * S8-A90: Save while the file on disk is another app's version (after Keep mine, or with the banner
 * still showing). Writing over it is asked first: Save a copy (the other version stays), Overwrite,
 * or Cancel.
 */
@Composable
internal fun OverwriteChangedFileDialog(onSaveACopy: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    val c = mdColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.secondarySurface,
        titleContentColor = c.primaryText,
        textContentColor = c.secondaryText,
        title = { Text(stringResource(R.string.main_dialog_overwrite_title)) },
        text = { Text(stringResource(R.string.main_dialog_overwrite_text)) },
        // Three buttons: the dialog lays its two button slots out in a row that wraps.
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.main_dialog_reload_cancel), color = c.secondaryText)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onOverwrite) {
                    Text(stringResource(R.string.main_action_overwrite), color = c.primaryText)
                }
                TextButton(onClick = onSaveACopy) {
                    Text(stringResource(R.string.main_action_save_a_copy), color = c.accent)
                }
            }
        },
    )
}
