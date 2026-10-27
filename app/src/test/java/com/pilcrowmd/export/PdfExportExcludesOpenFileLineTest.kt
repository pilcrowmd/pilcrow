// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.export

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.PrepareReaderModelUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.FileRepository
import com.pilcrowmd.repository.FileText
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The line that names the open file is a view over the document, never part of it: the text an
 * export is given is the document's own text, byte for byte. The note is opened from a nested folder
 * path and has a long name, so neither the folder names nor the file name could be in the text by
 * any route other than someone putting the line there.
 */
@RunWith(RobolectricTestRunner::class)
class PdfExportExcludesOpenFileLineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageScope: CoroutineScope

    private val noteUri = Uri.parse("content://t/Notes%2Fprojects%2Fquarterly-plan-draft.md")
    private val noteText = "# Heading\n\nBody text.\n"

    @Before
    fun setup() {
        storageScope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After
    fun tearDown() {
        storageScope.cancel()
    }

    private val repository = object : FileRepository {
        override suspend fun readFile(uri: Uri) = Result.success(FileText(noteText, isUtf8 = true))
        override suspend fun saveFile(uri: Uri, content: String) = Result.success(Unit)
        override suspend fun recoverPendingSaves() = Result.success(0)
        override suspend fun takePersistableUriPermission(uri: Uri) = Result.success(Unit)
        override suspend fun displayName(uri: Uri) = "quarterly-plan-draft.md"
        override fun hasPersistedPermission(uri: Uri) = true
        override fun hasPersistedWritePermission(uri: Uri) = true
        override suspend fun strandedSlots() = Result.success(emptyList<com.pilcrowmd.repository.StrandedSlot>())
        override suspend fun saveStrandedSlotToTarget(slotKey: String, targetUri: Uri) = Result.success(Unit)
        override suspend fun discardSlot(key: String) = Result.success(Unit)
    }

    @Test
    fun theTextHandedToTheExporterIsTheDocumentsOwnText() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dataStore = PreferenceDataStoreFactory.create(scope = storageScope) {
            tempFolder.newFile("pdf_open_file_test.preferences_pb")
        }
        val headings = ParseMarkdownHeadingsUseCase()
        val exporter = mockk<PdfExporter>(relaxed = true)
        val viewModel = MarkdownViewModel(
            repository = repository,
            storage = LocalStorageManager(context, dataStore),
            parseHeadingsUseCase = headings,
            searchUseCase = SearchMarkdownUseCase(headings),
            pdfExporter = exporter,
            appInfo = object : AppInfo {
                override val versionName = "test"
            },
            prepareReaderModel = PrepareReaderModelUseCase(headings::parseTree, headings),
        )

        viewModel.loadFile(noteUri)
        awaitTrue("the note never finished loading") {
            viewModel.currentDocument.value?.uri == noteUri && viewModel.fileLoadState.value == FileLoadState.Success
        }

        viewModel.exportPdf(Uri.parse("content://t/out.pdf"))
        val exported = slot<String>()
        verify(timeout = 5_000L) { exporter.exportToUri(capture(exported), any(), any(), any()) }

        assertEquals(noteText, exported.captured)
        assertFalse(exported.captured.contains("quarterly-plan-draft"))
        assertFalse(exported.captured.contains("projects"))
        assertTrue(exported.captured.startsWith("# Heading"))
    }

    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10L)
        }
        assertTrue(message, condition())
    }
}
