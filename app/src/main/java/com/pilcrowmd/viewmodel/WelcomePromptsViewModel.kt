// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pilcrowmd.di.AppContainer
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.di.Clock
import com.pilcrowmd.di.InstallSource
import com.pilcrowmd.domain.model.isRatingCardDue
import com.pilcrowmd.domain.model.isUpdateNoteDue
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The small, dismissable prompts around the welcome screen: the rating cards (M-176, M-177) and
 * the update note (M-174).
 *
 * Its own ViewModel rather than more state on [MarkdownViewModel], which is about the open
 * document. It only reads preferences and install facts; the one thing it needs from the document
 * side, the open count, reaches it through [StorageManager], which [MarkdownViewModel] writes.
 */
class WelcomePromptsViewModel(
    private val storage: StorageManager,
    private val installSource: InstallSource,
    private val appInfo: AppInfo,
    private val clock: Clock,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private data class InstallFacts(val isPlayInstall: Boolean, val firstInstallMillis: Long?)

    /** Null until read; the reads are binder calls, so they happen once, off the main thread. */
    private val installFacts = MutableStateFlow<InstallFacts?>(null)

    init {
        viewModelScope.launch {
            installFacts.value = withContext(ioDispatcher) {
                InstallFacts(installSource.isPlayInstall(), installSource.firstInstallTimeMillis())
            }
        }
    }

    /** M-176: the Settings rating card shows only on a Play install. False until known. */
    val isPlayInstall: StateFlow<Boolean> = installFacts
        .map { it?.isPlayInstall == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * M-174, re-decided against the clock whenever the stored dismissal changes. Cold, so the
     * rating card's `combine` waits for its first real value instead of an initial `false`: a card
     * must never flash up for one frame before the note that outranks it is known.
     */
    private val updateNoteDue = storage.updateNoteDismissedAt.map { dismissedAt ->
        isUpdateNoteDue(appInfo.releasePrepMillis, clock.nowMillis(), dismissedAt)
    }

    /** M-174: the welcome-screen note that this version is over two months old. */
    val updateNoteVisible: StateFlow<Boolean> = updateNoteDue
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** M-177: the one-time welcome-screen rating card. */
    val ratingCardVisible: StateFlow<Boolean> = combine(
        installFacts,
        storage.documentsOpened,
        storage.ratingCardDismissed,
        updateNoteDue,
    ) { facts, opens, dismissed, noteDue ->
        facts != null &&
            isRatingCardDue(
                isPlayInstall = facts.isPlayInstall,
                opens = opens,
                firstInstallMillis = facts.firstInstallMillis,
                nowMillis = clock.nowMillis(),
                dismissed = dismissed,
                updateNoteDue = noteDue,
            )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Either button on the welcome card: hide it for good. */
    fun dismissRatingCard() {
        viewModelScope.launch { storage.dismissRatingCard() }
    }

    /** The note's ×: snooze it for [com.pilcrowmd.domain.model.UPDATE_NOTE_SNOOZE_DAYS] from now. */
    fun dismissUpdateNote() {
        viewModelScope.launch { storage.dismissUpdateNote(clock.nowMillis()) }
    }

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return WelcomePromptsViewModel(
                    storage = container.storageManager,
                    installSource = container.installSource,
                    appInfo = container.appInfo,
                    clock = container.clock,
                ) as T
            }
        }
    }
}
