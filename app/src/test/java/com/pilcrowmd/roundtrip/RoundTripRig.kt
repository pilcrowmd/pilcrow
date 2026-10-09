// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.roundtrip

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import com.pilcrowmd.di.AppInfo
import com.pilcrowmd.domain.usecase.ParseMarkdownHeadingsUseCase
import com.pilcrowmd.domain.usecase.SearchMarkdownUseCase
import com.pilcrowmd.repository.LocalFileRepository
import com.pilcrowmd.storage.LocalStorageManager
import com.pilcrowmd.storage.StorageManager
import com.pilcrowmd.viewmodel.FileLoadState
import com.pilcrowmd.viewmodel.MarkdownViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.robolectric.Shadows.shadowOf
import java.io.File

/** What a load-then-save left on disk, or why it never got that far. */
internal sealed interface RoundTripOutcome {
    class Saved(val bytes: ByteArray) : RoundTripOutcome
    class Failed(val reason: String) : RoundTripOutcome
}

/**
 * Drives one document through the real open/save path:
 *
 * `LocalFileRepository.readFile` → `MarkdownViewModel.loadFile` (CRLF→LF, ending remembered) →
 * `MarkdownViewModel.saveFile` → `contentForDisk` → `LocalFileRepository.saveFile` (WAL + fsync).
 *
 * Only the SAF [ContentResolver] is faked, and only to hand out REAL file descriptors over a file in
 * [docsDir] — so decoding, encoding, the journal and the write are all production code.
 *
 * Blocking, main-looper driven: no `Dispatchers.setMain`, so the suites run in `testDebugUnitTest`.
 */
internal class RoundTripRig(context: Context, private val docsDir: File, walDir: File, dataStoreFile: File) {

    private val storageScope = CoroutineScope(Dispatchers.IO + Job())
    private val vmStore = ViewModelStore()
    private val files = mutableMapOf<Uri, File>()

    @Volatile
    private var activeUri: Uri = Uri.EMPTY

    private val resolver: ContentResolver = mockk {
        every { openFileDescriptor(any(), "r") } answers {
            ParcelFileDescriptor.open(fileFor(firstArg()), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        every { openFileDescriptor(any(), "wt") } answers {
            ParcelFileDescriptor.open(
                fileFor(firstArg()),
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_CREATE,
            )
        }
        // No provider row: displayName falls back to the URI's last segment, i.e. the corpus name.
        every { query(any(), any(), any(), any(), any()) } returns null
        // A persisted read+write grant, so each document opens as a normal writable file, not transient.
        val grant = mockk<UriPermission> {
            every { uri } answers { activeUri }
            every { isReadPermission } returns true
            every { isWritePermission } returns true
        }
        every { persistedUriPermissions } answers { listOf(grant) }
    }

    private val repository = LocalFileRepository(resolver, walDir)

    // The startup restore is switched off: with a DataStore shared across the whole corpus, every new
    // ViewModel would otherwise reopen the PREVIOUS document at construction, and a barrier on
    // `FileLoadState.Success` could be satisfied by that load instead of this one. Restore is not part
    // of the round-trip path; everything else is the real LocalStorageManager.
    private val storage: StorageManager = object : StorageManager by LocalStorageManager(
        context,
        PreferenceDataStoreFactory.create(scope = storageScope) { dataStoreFile },
    ) {
        override val lastFileUri: Flow<Uri?> = flowOf(null)
    }

    private fun fileFor(uri: Uri): File = checkNotNull(files[uri]) { "no corpus file for $uri" }

    private fun newViewModel(): MarkdownViewModel {
        val headings = ParseMarkdownHeadingsUseCase()
        val vm = MarkdownViewModel(
            repository = repository,
            storage = storage,
            parseHeadingsUseCase = headings,
            searchUseCase = SearchMarkdownUseCase(headings),
            pdfExporter = mockk(relaxed = true),
            appInfo = object : AppInfo {
                override val versionName: String = "test"
            },
        )
        vmStore.put("vm", vm) // replacing the key clears the previous ViewModel, cancelling its scope
        return vm
    }

    /**
     * Put [bytes] on disk, open, save unchanged, and return what is on disk afterwards.
     *
     * No edit is made before saving: `saveFile` writes an undirtied document as it is, which is
     * exactly the "open and save unchanged" case (and the SENTINEL below proves the write happened).
     *
     * BARRIERS ("a test that cannot fail is not a test"): each wait is on the state the call itself
     * publishes, never on an initial value. The load waits until the published document is THIS uri
     * and the load state is terminal (the startup restore is off, so nothing else can publish); the
     * save waits for a terminal save state, which the load's `Success` cannot satisfy.
     *
     * SENTINEL: between load and save the file on disk is overwritten. Without that, a save that never
     * wrote anything would leave the original bytes in place and pass byte-identity for free.
     */
    fun roundTrip(name: String, bytes: ByteArray): RoundTripOutcome {
        val file = File(docsDir, name).apply { writeBytes(bytes) }
        val uri = Uri.parse("content://roundtrip/$name")
        files[uri] = file
        activeUri = uri

        val vm = newViewModel()
        vm.loadFile(uri)
        val loaded = awaitState(vm, "load of $name") {
            it is FileLoadState.Error || (it is FileLoadState.Success && vm.currentDocument.value?.uri == uri)
        }
        if (loaded !is FileLoadState.Success) return RoundTripOutcome.Failed("load: $loaded")

        file.writeBytes(SENTINEL)
        vm.saveFile()
        val saved = awaitState(vm, "save of $name") {
            it is FileLoadState.SaveSuccess || it is FileLoadState.SaveError
        }
        if (saved !is FileLoadState.SaveSuccess) return RoundTripOutcome.Failed("save: $saved")
        return RoundTripOutcome.Saved(file.readBytes()).also { file.delete() }
    }

    /**
     * Poll [MarkdownViewModel.fileLoadState] until [done], idling the Robolectric main looper between
     * polls so `viewModelScope` continuations resumed from IO/Default can land — the `awaitValue`
     * idiom of MarkdownViewModelRenderModeTest, with no `Dispatchers.setMain`.
     */
    private fun awaitState(vm: MarkdownViewModel, what: String, done: (FileLoadState) -> Boolean): FileLoadState {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = vm.fileLoadState.value
            if (done(state)) return state
            Thread.sleep(POLL_MS)
        }
        return FileLoadState.Error("timed out waiting for the $what; last state ${vm.fileLoadState.value}")
    }

    /** Cancels every ViewModel scope and the DataStore scope. */
    fun close() {
        vmStore.clear()
        storageScope.cancel()
    }

    companion object {
        private const val AWAIT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 1L
        private val SENTINEL = "SENTINEL: the save did not write this file".toByteArray()

        /** A one-line account of where [actual] first departs from [expected], with hex context. */
        fun describeMismatch(name: String, expected: ByteArray, actual: ByteArray): String {
            val limit = minOf(expected.size, actual.size)
            val at = (0 until limit).firstOrNull { expected[it] != actual[it] } ?: limit
            fun hex(b: ByteArray) = b.copyOfRange(maxOf(0, at - 6), minOf(b.size, at + 10))
                .joinToString(" ") { "%02X".format(it) }
            return "$name: expected ${expected.size} B, saved ${actual.size} B, first difference at byte $at " +
                "[expected ${hex(expected)}] [saved ${hex(actual)}]"
        }
    }
}
