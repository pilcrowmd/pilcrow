// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.domain.usecase.CountRelativeImagesUseCase
import com.pilcrowmd.repository.FolderAccessRepository
import com.pilcrowmd.repository.NoteFolder
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** M-93: the strip above a note that explains its missing pictures. */
sealed interface ImageFolderBanner {
    /** The note has [pictureCount] pictures next to it, and a folder grant would show them. */
    data class AskForFolder(val pictureCount: Int) : ImageFolderBanner

    /** The note sits in a folder Android will not let the app open (Download/, the top of storage). */
    data object CannotShowHere : ImageFolderBanner
}

data class ImageFolderUi(
    val banner: ImageFolderBanner? = null,
    /** True when the folder picker can help: drives "Tap to show" and the overflow-menu item. */
    val canAskForFolder: Boolean = false,
    /** Changes whenever the granted folders do, so the reader renders its pictures again. */
    val accessKey: String = "",
)

/**
 * M-93: decides whether the reader shows the folder banner, and asks for the folder.
 *
 * The banner shows only when the note has pictures next to it AND no grant covers its folder,
 * once per folder: "Not now" is remembered per folder, after which only "Tap to show" and the
 * overflow-menu item reach the picker. A note in a folder Android will not grant gets a short
 * explanation instead, and never the picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImageFolderViewModel(
    private val folders: FolderAccessRepository,
    private val storage: StorageManager,
    private val countRelativeImages: CountRelativeImagesUseCase,
) : ViewModel() {

    private data class Shown(val uri: Uri, val content: String)

    private data class Facts(val uri: Uri, val pictures: Int, val folder: NoteFolder, val grants: List<Uri>)

    private val shown = MutableStateFlow<Shown?>(null)
    private val refreshes = MutableStateFlow(0)

    private val facts: StateFlow<Facts?> = combine(shown, refreshes) { note, _ -> note }
        .mapLatest { note -> note?.let { factsFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ImageFolderUi> = combine(facts, storage.dismissedImageFolders) { f, dismissed ->
        uiFor(f, dismissed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImageFolderUi())

    private val pickFolderEvents = MutableSharedFlow<Uri>(extraBufferCapacity = 1)

    /** Where to open the folder picker: the note itself, so it starts in the note's folder. */
    val pickFolder: SharedFlow<Uri> = pickFolderEvents.asSharedFlow()

    /** The reader shows [uri] with [content]; null when no document is shown. */
    fun onDocumentShown(uri: Uri?, content: String) {
        shown.value = uri?.let { Shown(it, content) }
    }

    /** Look at the grants again — after a pick, and when the app comes back to the front. */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    /** "Not now" (or "OK" on the can't-show banner): remembered for this folder. */
    fun dismissBanner() {
        val key = when (val folder = facts.value?.folder) {
            is NoteFolder.NeedsGrant -> folder.folderKey
            is NoteFolder.Blocked -> folder.folderKey
            else -> return
        }
        viewModelScope.launch { storage.setImageFolderDismissed(key, true) }
    }

    /** "Allow folder", "Tap to show" or the menu item. Does nothing where the picker cannot help. */
    fun requestFolderAccess() {
        val current = facts.value ?: return
        val folder = current.folder as? NoteFolder.NeedsGrant ?: return
        if (current.pictures > 0) pickFolderEvents.tryEmit(folder.pickerStart)
    }

    /** The picker's answer; null when the user backed out. */
    fun onFolderPicked(treeUri: Uri?) {
        if (treeUri == null) return
        val asked = facts.value
        viewModelScope.launch {
            folders.grantFolder(treeUri)
            refresh()
            // A grant that covers the note clears that folder's "Not now", so losing the grant
            // later (revoked, or pruned by Android) brings the banner back.
            val before = asked?.folder as? NoteFolder.NeedsGrant ?: return@launch
            if (folders.folderFor(asked.uri) is NoteFolder.Covered) {
                storage.setImageFolderDismissed(before.folderKey, false)
            }
        }
    }

    private suspend fun factsFor(note: Shown): Facts {
        val pictures = withContext(Dispatchers.Default) { countRelativeImages(note.content) }
        if (pictures == 0) return Facts(note.uri, 0, NoteFolder.Unknown, folders.grantedFolders())
        return Facts(note.uri, pictures, folders.folderFor(note.uri), folders.grantedFolders())
    }

    private fun uiFor(facts: Facts?, dismissed: Set<String>): ImageFolderUi {
        if (facts == null) return ImageFolderUi()
        val accessKey = facts.grants.joinToString("|")
        // A note with no pictures next to it arrives here as Unknown (factsFor), so it gets nothing.
        return when (val folder = facts.folder) {
            is NoteFolder.NeedsGrant -> ImageFolderUi(
                banner = ImageFolderBanner.AskForFolder(facts.pictures).takeUnless { folder.folderKey in dismissed },
                canAskForFolder = true,
                accessKey = accessKey,
            )
            is NoteFolder.Blocked -> ImageFolderUi(
                banner = ImageFolderBanner.CannotShowHere.takeUnless { folder.folderKey in dismissed },
                accessKey = accessKey,
            )
            is NoteFolder.Covered, NoteFolder.Unknown -> ImageFolderUi(accessKey = accessKey)
        }
    }

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ImageFolderViewModel(
                folders = container.folderAccessRepository,
                storage = container.storageManager,
                countRelativeImages = CountRelativeImagesUseCase(),
            ) as T
        }
    }
}
