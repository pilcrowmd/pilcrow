// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import android.content.Context
import android.net.Uri
import com.pilcrowmd.domain.markdown.EmojiShortcodes
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.export.PdfExporter
import com.pilcrowmd.rendering.EmojiGlyphs
import com.pilcrowmd.rendering.MarkwonRenderer
import com.pilcrowmd.rendering.mermaid.MermaidDiagramRenderer
import com.pilcrowmd.rendering.mermaid.WebViewMermaidRenderer
import com.pilcrowmd.repository.AssetEmojiTable
import com.pilcrowmd.repository.DraftRepository
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FolderAccessRepository
import com.pilcrowmd.repository.LocalDraftRepository
import com.pilcrowmd.repository.LocalFileRepository
import com.pilcrowmd.repository.LocalFolderAccessRepository
import com.pilcrowmd.repository.LocalNoteLinkRepository
import com.pilcrowmd.repository.LocalNotesFolderRepository
import com.pilcrowmd.repository.NoteLinkRepository
import com.pilcrowmd.repository.NotesFolderRepository
import com.pilcrowmd.repository.ProviderDocumentTree
import com.pilcrowmd.repository.SaveReportingFileRepository
import com.pilcrowmd.storage.LocalNoteMarksStore
import com.pilcrowmd.storage.LocalNotesFolderStore
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.NoteMarksStore
import com.pilcrowmd.storage.NotesFolderStore
import com.pilcrowmd.storage.StorageManager
import kotlinx.coroutines.flow.Flow

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
    override val fileRepository: FileRepository by lazy { saveReporting }

    // M-162b (M4): the same repository, reporting each finished save; the save itself is unchanged.
    private val saveReporting by lazy {
        // noBackupFilesDir: durable and non-evictable, so the crash-recovery write-ahead log
        // survives storage pressure (cacheDir can be wiped by the OS) and is not cloud-backed up.
        SaveReportingFileRepository(LocalFileRepository(appContext.contentResolver, appContext.noBackupFilesDir))
    }

    // noBackupFilesDir like the save journal: excluded from Auto Backup, and Android never clears it.
    override val draftRepository: DraftRepository by lazy {
        LocalDraftRepository(appContext.noBackupFilesDir)
    }
    override val savedDocuments: Flow<Uri> get() = saveReporting.savedDocuments

    /**
     * Storage manager: DataStore persistence for preferences, scroll state, recents.
     * Built from applicationContext, safe for retained ViewModels.
     */
    override val storageManager: StorageManager by lazy {
        LocalStorageManager(appContext)
    }

    /**
     * Markdown renderer: Markwon configured once with plugins (tables, code highlighting,
     * LaTeX, frontmatter). One instance per process, reused across
     * all Preview blocks and previews (stateless renderer, state in the block entries).
     * Built from applicationContext context for theme/resource resolution, safe for retained ViewModels.
     */
    override val markwonRenderer: MarkwonRenderer by lazy {
        MarkwonRenderer(appContext, relativeImages = folderAccessRepository, emoji = emojiShortcodes)
    }

    /**
     * M-247: the `:shortcode:` table, built once and given to both the renderer and search, so the
     * reader, the PDF and search always agree on which names paint as emoji. The asset is read on
     * first use; a name whose emoji this device's fonts cannot draw stays literal everywhere.
     */
    private val emojiShortcodes: EmojiShortcodes by lazy {
        val glyphs = EmojiGlyphs()
        EmojiShortcodes(AssetEmojiTable({ appContext.assets.open(AssetEmojiTable.ASSET_PATH) }, glyphs::canDraw))
    }

    /**
     * M-93: folder grants (Android's persisted tree grants) and the walk that turns `images/x.png`
     * in a note into a document inside a granted folder.
     */
    override val folderAccessRepository: FolderAccessRepository by lazy {
        LocalFolderAccessRepository(documentTree)
    }

    /** M-162b: the notes folders, over the same folder grants as [folderAccessRepository]. */
    override val notesFolderRepository: NotesFolderRepository by lazy {
        LocalNotesFolderRepository(documentTree, notesFolderStore, appContext.resources)
    }

    /** M-160: links between notes, over the notes folders and the same folder grants. */
    override val noteLinkRepository: NoteLinkRepository by lazy {
        LocalNoteLinkRepository(documentTree, notesFolderRepository)
    }

    override val notesFolderStore: NotesFolderStore by lazy {
        LocalNotesFolderStore(appContext)
    }

    override val noteMarksStore: NoteMarksStore by lazy {
        LocalNoteMarksStore(appContext)
    }

    private val documentTree by lazy {
        ProviderDocumentTree(appContext.contentResolver, appContext.packageManager)
    }

    /**
     * M-70: Mermaid diagrams drawn on the device, offline, in one hidden WebView used only as a
     * compiler. Built from applicationContext; the WebView itself is only created on first render.
     */
    override val mermaidRenderer: MermaidDiagramRenderer by lazy {
        WebViewMermaidRenderer(appContext)
    }

    /**
     * PDF exporter: off-screen rendering to PdfDocument with SAF write support.
     * Depends on markwonRenderer for markdown parsing and block rendering, and on mermaidRenderer to
     * draw diagrams (M-70).
     * Built once and reused for all exports.
     */
    override val pdfExporter: PdfExporter by lazy {
        PdfExporter(appContext, markwonRenderer, mermaidRenderer)
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
        SearchMarkdownUseCase(parseMarkdownHeadingsUseCase, emojiShortcodes)
    }

    /**
     * App metadata: versionName from the APK manifest.
     * Lazy-initialized once on first access; the PackageManager call happens once per process.
     */
    override val appInfo: AppInfo by lazy {
        DefaultAppInfo(appContext)
    }

    /** Installer and first-install time, read from PackageManager by the caller, off the main thread. */
    override val installSource: InstallSource by lazy {
        DefaultInstallSource(appContext)
    }

    override val clock: Clock = Clock { System.currentTimeMillis() }
}
