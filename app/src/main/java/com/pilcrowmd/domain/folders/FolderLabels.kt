// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.domain.folders

/** What tells one added folder from another in the list: its name and where it sits. */
data class FolderIdentity(val name: String, val parentHint: String?)

/**
 * P15: two added folders with the same name show where each one is, in grey after the name
 * (`Notes - Work`, `Notes - Home`). On Android that is the parent folder's name where the folder
 * permission shows the path (phone storage, SD card), else the source app's name (section 6).
 */
object FolderLabels {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val PRIMARY_VOLUME = "primary"

    /**
     * For each folder, the grey suffix to show after its name: its parent hint when two or more
     * of [folders] share a name (in one Unicode form, without case), else null.
     */
    fun labels(folders: List<FolderIdentity>): List<String?> {
        val counts = folders.groupingBy { foldName(it.name) }.eachCount()
        return folders.map { folder -> folder.parentHint.takeIf { counts.getValue(foldName(folder.name)) > 1 } }
    }

    /**
     * Where a phone storage or SD card folder sits, from its document ID: `primary:Work/Notes` is in
     * `Work`; `primary:Notes` is at the top of internal storage and `1A2B-3C4D:Notes` at the top of
     * an SD card. [ParentHint.Unknown] for the volume itself and for any other provider, whose IDs
     * say nothing about the path.
     */
    fun parentHintFromDocumentId(authority: String?, documentId: String): ParentHint {
        if (authority != EXTERNAL_STORAGE || ':' !in documentId) return ParentHint.Unknown
        val volume = documentId.substringBefore(':')
        val segments = documentId.substringAfter(':').split('/').filter { it.isNotEmpty() }
        return when {
            segments.size >= 2 -> ParentHint.Named(segments[segments.size - 2])
            segments.isEmpty() -> ParentHint.Unknown
            volume == PRIMARY_VOLUME -> ParentHint.InternalStorage
            else -> ParentHint.SdCard
        }
    }
}

/** Where an added folder sits, before it is put into words (the data layer holds the text). */
sealed interface ParentHint {
    /** Inside the folder [name]. */
    data class Named(val name: String) : ParentHint

    /** At the top of the phone's internal storage. */
    data object InternalStorage : ParentHint

    /** At the top of an SD card (or another removable volume). */
    data object SdCard : ParentHint

    /** The ID does not say; the source app's name is the fallback (section 6). */
    data object Unknown : ParentHint
}
