// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pilcrowmd.R
import com.pilcrowmd.ui.theme.mdColors

/**
 * S8-A55 "Allow editing" picker: `ACTION_OPEN_DOCUMENT` with no MIME filter (like [OpenAnyDocument],
 * so a `.md` a provider labels `application/octet-stream` is never greyed out), starting at the
 * input URI when there is one. The pick comes back with a persistable read+write grant.
 */
private class OpenDocumentAt : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .apply { if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input) }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * S8-A75: where the "Allow editing" picker opens. A DocumentsContract URI is its own start (the
 * picker shows that document's folder). A MediaStore file, which is what Samsung My Files sends,
 * is not one the picker can start at, so its folder is looked up (RELATIVE_PATH and VOLUME_NAME,
 * Android 10+) and named the way the external-storage provider does, e.g.
 * `content://com.android.externalstorage.documents/document/primary%3ADownload%2FNotes`. Anything
 * else, or a lookup that fails, gives null: the picker opens where Android chooses.
 */
internal fun pickerStartFor(context: Context, uri: Uri): Uri? = when {
    DocumentsContract.isDocumentUri(context, uri) -> uri
    uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        mediaStoreFolder(context, uri)
    else -> null
}

@RequiresApi(Build.VERSION_CODES.Q)
@Suppress("TooGenericExceptionCaught", "SwallowedException") // A provider may throw anything: null is the answer.
private fun mediaStoreFolder(context: Context, uri: Uri): Uri? = try {
    val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
    context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        val volume = if (cursor.moveToFirst()) cursor.getString(0) else null
        val relativePath = if (volume != null) cursor.getString(1) else null
        if (volume != null && relativePath != null) externalStorageFolder(volume, relativePath) else null
    }
} catch (_: Exception) {
    null
}

/** The external-storage provider's document URI for a folder MediaStore names by [volume] and [relativePath]. */
internal fun externalStorageFolder(volume: String, relativePath: String): Uri {
    val root = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else volume.uppercase()
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "$root:${relativePath.trimEnd('/')}")
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * S8-A55: what Save (or the banner) offers for a file another app opened read-only: "Allow editing"
 * (first) and "Save a copy", the Save-As that was the only choice before. Back or a tap outside
 * closes it.
 */
@Composable
internal fun EditChoiceDialog(onAllowEditing: () -> Unit, onSaveCopy: () -> Unit, onDismiss: () -> Unit) =
    TwoButtonDialog(
        title = R.string.main_dialog_edit_title,
        text = R.string.main_dialog_edit_text,
        confirm = R.string.main_action_allow_editing,
        dismiss = R.string.main_action_save_a_copy,
        onConfirm = onAllowEditing,
        onDismissButton = onSaveCopy,
        onDismiss = onDismiss,
    )

/** S8-A55: says what the picker is for before it opens. Continue opens it; Cancel changes nothing. */
@Composable
internal fun AllowEditingDialog(onContinue: () -> Unit, onDismiss: () -> Unit) = TwoButtonDialog(
    title = R.string.main_dialog_allow_editing_title,
    text = R.string.main_dialog_allow_editing_text,
    confirm = R.string.main_action_continue,
    dismiss = R.string.main_dialog_reload_cancel,
    onConfirm = onContinue,
    onDismissButton = onDismiss,
    onDismiss = onDismiss,
)

/** S8-A55: the picked file is not the open one (or it changed). Nothing was changed; pick again or stop. */
@Composable
internal fun NotSameFileDialog(onPickAgain: () -> Unit, onDismiss: () -> Unit) = TwoButtonDialog(
    title = R.string.main_dialog_not_same_file_title,
    text = R.string.main_dialog_not_same_file_text,
    confirm = R.string.main_action_pick_again,
    dismiss = R.string.main_dialog_reload_cancel,
    onConfirm = onPickAgain,
    onDismissButton = onDismiss,
    onDismiss = onDismiss,
)

/** The three dialogs above share one shape; colours from the token layer (Safeguard 4). */
@Suppress("LongParameterList") // Four labels and three actions of one dialog.
@Composable
private fun TwoButtonDialog(
    @StringRes title: Int,
    @StringRes text: Int,
    @StringRes confirm: Int,
    @StringRes dismiss: Int,
    onConfirm: () -> Unit,
    onDismissButton: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = mdColors().secondarySurface,
        titleContentColor = mdColors().primaryText,
        textContentColor = mdColors().secondaryText,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = mdColors().primaryText) }
        },
        dismissButton = {
            TextButton(onClick = onDismissButton) { Text(stringResource(dismiss), color = mdColors().secondaryText) }
        },
    )
}

/** The launcher for [OpenDocumentAt]; launch it with the URI to start at, or null. A cancel gives null. */
@Composable
internal fun rememberAllowEditingLauncher(onResult: (Uri?) -> Unit): ManagedActivityResultLauncher<Uri?, Uri?> =
    rememberLauncherForActivityResult(OpenDocumentAt(), onResult)
