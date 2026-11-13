// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

// M-162a: the system document pickers open in the folder of the last file used. Each contract reads
// [start] when the picker is launched, not when the launcher is remembered, so the folder follows
// the newest recent. A null start leaves the picker at the system's own default.

/** [ActivityResultContracts.OpenDocument] that opens in the folder of [start]'s document. */
internal class OpenDocumentFrom(private val start: () -> Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).startingAt(start())
}

/** [ActivityResultContracts.CreateDocument] that opens in the folder of [start]'s document. */
internal class CreateDocumentFrom(mimeType: String, private val start: () -> Uri?) :
    ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).startingAt(start())
}

/**
 * "Open any document" SAF contract: like [ActivityResultContracts.OpenDocument] but with **no**
 * `EXTRA_MIME_TYPES`, so the system picker leaves every openable file selectable. The standard
 * `OpenDocument` always sets `EXTRA_MIME_TYPES`, and some providers (notably Samsung's picker) do not
 * treat a literal all-types wildcard entry there as "all"; they keep non-matching files greyed out.
 * Omitting the extra entirely (only the wildcard `type`) is the reliable "show all files" path that
 * backs the "Browse all files" fallback for a mislabeled `.md` (S24+ UAT). Read-only, never writes.
 * Opens in the folder of [start]'s document (M-162a).
 */
internal class OpenAnyDocument(private val start: () -> Uri?) : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .startingAt(start())

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** The plain document Uri (never a tree Uri): the picker opens the folder that holds it. */
private fun Intent.startingAt(start: Uri?): Intent = apply {
    if (start != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, start)
}
