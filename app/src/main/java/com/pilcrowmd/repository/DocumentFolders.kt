// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

/**
 * M-93: which folder a note sits in, read from its document ID alone, before the app holds any
 * grant to that folder. Only IDs whose shape is documented are read; anything else is unknown.
 *
 * - Android's storage provider: `volume:relative/path`, e.g. `primary:Documents/Notes/trip.md`.
 * - The Downloads provider: `raw:/storage/emulated/0/Download/trip.md`. Its `msf:<n>` IDs name no
 *   folder, so they stay unknown.
 */
internal object DocumentFolders {

    const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    const val DOWNLOADS = "com.android.providers.downloads.documents"

    /** The folder picker refuses some folders from Android 11 (API 30) on. */
    private const val FOLDER_PICKER_LIMITS_API = 30

    private const val DOWNLOAD_DIR = "Download"
    private const val ANDROID_DIR = "Android"
    private const val RAW_PREFIX = "raw:"

    /**
     * [key] names the folder for remembering "Not now". [ungrantable] is true when Android's folder
     * picker will not let the user choose it: the top of a storage volume, `Download/`, or
     * `Android/` and below.
     */
    data class Folder(val key: String, val ungrantable: Boolean)

    fun folderOf(authority: String?, documentId: String, sdkInt: Int): Folder? = when (authority) {
        EXTERNAL_STORAGE -> storageFolder(documentId, sdkInt)
        DOWNLOADS -> downloadsFolder(documentId, sdkInt)
        else -> null
    }

    private fun storageFolder(documentId: String, sdkInt: Int): Folder? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val parent = documentId.substring(colon + 1).substringBeforeLast('/', missingDelimiterValue = "")
        return Folder(
            key = "$EXTERNAL_STORAGE|$volume:$parent",
            ungrantable = sdkInt >= FOLDER_PICKER_LIMITS_API && pickerRefuses(parent),
        )
    }

    /** A `raw:` path straight inside a volume's `Download/` is the one Downloads case we can place. */
    private fun downloadsFolder(documentId: String, sdkInt: Int): Folder? {
        if (!documentId.startsWith(RAW_PREFIX)) return null
        val parent = documentId.removePrefix(RAW_PREFIX).substringBeforeLast('/', missingDelimiterValue = "")
        val inDownloadRoot = parent.substringAfterLast('/').equals(DOWNLOAD_DIR, ignoreCase = true)
        if (!inDownloadRoot || sdkInt < FOLDER_PICKER_LIMITS_API) return null
        return Folder(key = "$DOWNLOADS|$parent", ungrantable = true)
    }

    private fun pickerRefuses(parent: String): Boolean = parent.isEmpty() ||
        parent.equals(DOWNLOAD_DIR, ignoreCase = true) ||
        parent.equals(ANDROID_DIR, ignoreCase = true) ||
        parent.startsWith("$ANDROID_DIR/", ignoreCase = true)
}
