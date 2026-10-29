// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import android.net.Uri
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.repository.DraftRepository
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FolderAccessRepository
import com.pilcrowmd.repository.NoteLinkRepository
import com.pilcrowmd.repository.NotesFolderRepository
import com.pilcrowmd.storage.NoteMarksStore
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.flow.Flow

/**
 * Composition root interface. Lazy singletons for file access, persistence, and rendering.
 * All dependencies are built from applicationContext (never Activity), so retained
 * ViewModels that hold references to these dependencies survive Activity destruction
 * (Safeguard: retained ViewModel never references an Activity).
 *
 * vNext hooks: the interface is extensible for cloud file access, remote storage,
 * and theming variants without changing ViewModel/View code.
 */
interface AppContainer {
    /** File I/O abstraction. */
    val fileRepository: FileRepository

    /** Persistence abstraction. */
    val storageManager: StorageManager

    /** Unsaved edits kept between a keystroke and a save, for recovery after a kill or crash. */
    val draftRepository: DraftRepository

    /** M-93: folder grants and the relative-picture resolver. */
    val folderAccessRepository: FolderAccessRepository

    /** M-162b: the notes folders the user added, and their notes. */
    val notesFolderRepository: NotesFolderRepository

    /** M-160: links between notes in an added notes folder. */
    val noteLinkRepository: NoteLinkRepository

    /** M-162b: what the notes-folder list remembers (folders, tick, open subfolders). */
    val notesFolderStore: NotesFolderStore

    /** M-162b (M4): each document the app has just saved in place, from [fileRepository]. */
    val savedDocuments: Flow<Uri>

    /** S8-A52: each save in place as it starts, from [fileRepository]. */
    val saveStarts: Flow<Uri>

    /** M-162b: the New and Updated marks of each notes folder. */
    val noteMarksStore: NoteMarksStore

    /** Markdown rendering engine (lazy, one instance per process). */
    val markwonRenderer: MarkwonRenderer

    /** M-70: on-device, offline Mermaid diagram drawing (lazy, one hidden WebView per process). */
    val mermaidRenderer: MermaidDiagramRenderer

    /** PDF export engine for off-screen rendering and SAF writing. */
    val pdfExporter: PdfExporter

    /** Domain UseCase for heading extraction from markdown. */
    val parseMarkdownHeadingsUseCase: ParseMarkdownHeadingsUseCase

    /** Domain UseCase for searching markdown content. */
    val searchMarkdownUseCase: SearchMarkdownUseCase

    /** App-level metadata (versionName for About card, etc.). */
    val appInfo: AppInfo

    /** How this copy was installed: Play or not, and when first (M-176, M-177). */
    val installSource: InstallSource

    /** Wall-clock time for date-gated prompts (M-177, M-174). */
    val clock: Clock
}
