// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.storage.StorageManager

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

    /** Markdown rendering engine (lazy, one instance per process). */
    val markwonRenderer: MarkwonRenderer

    /** PDF export engine for off-screen rendering and SAF writing. */
    val pdfExporter: PdfExporter

    /** Domain UseCase for heading extraction from markdown. */
    val parseMarkdownHeadingsUseCase: ParseMarkdownHeadingsUseCase

    /** Domain UseCase for searching markdown content. */
    val searchMarkdownUseCase: SearchMarkdownUseCase

    /** App-level metadata (versionName for About card, etc.). */
    val appInfo: AppInfo
}
