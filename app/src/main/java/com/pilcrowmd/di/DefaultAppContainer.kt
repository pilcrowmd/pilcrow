// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import android.content.Context
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.LocalFileRepository
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager

/**
 * Default AppContainer implementation. All dependencies are built lazily from
 * the application context. Lazy singletons ensure one instance per process
 * and defer initialization until first use.
 */
class DefaultAppContainer(private val appContext: Context) : AppContainer {
    /**
     * File repository: ContentResolver-based file I/O for single-URI (v1).
     * Built from applicationContext, safe for retained ViewModels.
     */
    override val fileRepository: FileRepository by lazy {
        // noBackupFilesDir: durable and non-evictable, so the crash-recovery write-ahead log
        // survives storage pressure (cacheDir can be wiped by the OS) and is not cloud-backed up.
        LocalFileRepository(appContext.contentResolver, appContext.noBackupFilesDir)
    }

    /**
     * Storage manager: DataStore persistence for preferences, scroll state, recents.
     * Built from applicationContext, safe for retained ViewModels.
     */
    override val storageManager: StorageManager by lazy {
        LocalStorageManager(appContext)
    }

    /**
     * Markdown renderer: Markwon configured once with plugins (tables, code highlighting,
     * LaTeX, frontmatter, Mermaid opt-in). One instance per process, reused across
     * all Preview blocks and previews (stateless renderer, state in the block entries).
     * Built from applicationContext context for theme/resource resolution, safe for retained ViewModels.
     */
    override val markwonRenderer: MarkwonRenderer by lazy {
        MarkwonRenderer(appContext)
    }

    /**
     * PDF exporter: off-screen rendering to PdfDocument with SAF write support.
     * Depends on markwonRenderer for markdown parsing and block rendering.
     * Built once and reused for all exports.
     */
    override val pdfExporter: PdfExporter by lazy {
        PdfExporter(appContext, markwonRenderer)
    }

    /**
     * Domain UseCase for extracting headings and computing block offsets from markdown.
     * Stateless; encapsulates parsing logic. Built once and reused.
     */
    override val parseMarkdownHeadingsUseCase: ParseMarkdownHeadingsUseCase by lazy {
        ParseMarkdownHeadingsUseCase()
    }

    /**
     * Domain UseCase for searching markdown content.
     * Depends on parseMarkdownHeadingsUseCase for block offset computation.
     * Stateless; built once and reused.
     */
    override val searchMarkdownUseCase: SearchMarkdownUseCase by lazy {
        SearchMarkdownUseCase(parseMarkdownHeadingsUseCase)
    }

    /**
     * App metadata: versionName from the APK manifest.
     * Lazy-initialized once on first access; the PackageManager call happens once per process.
     */
    override val appInfo: AppInfo by lazy {
        DefaultAppInfo(appContext)
    }
}
