// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.repository

import android.net.Uri
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * M-162b (M4): [inner], unchanged, plus a report of each document the app has just saved in place,
 * so the notes list can take the saved values as the new baseline and the app's own save never
 * reads as Updated. Only [saveFile] is wrapped: it calls [inner] with the same arguments and returns
 * its result as it is, and an exception from [inner] passes through untouched. The report is a
 * non-suspending emit into a buffer that drops the oldest when full, so it can neither delay nor
 * fail a save (Safeguard 1). A stranded slot rescued to a new file is not reported: that is not a
 * save of the open note.
 */
class SaveReportingFileRepository(private val inner: FileRepository) : FileRepository by inner {

    private val saved = MutableSharedFlow<Uri>(
        extraBufferCapacity = SAVED_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The URI of every successful [saveFile], once the save has finished. */
    val savedDocuments: SharedFlow<Uri> = saved.asSharedFlow()

    private val started = MutableSharedFlow<Uri>(
        extraBufferCapacity = SAVED_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** S8-A52: the URI of every [saveFile] as it starts, whatever its outcome, reported the same way. */
    val saveStarts: SharedFlow<Uri> = started.asSharedFlow()

    override suspend fun saveFile(uri: Uri, content: String): Result<Unit> {
        started.tryEmit(uri)
        val result = inner.saveFile(uri, content)
        if (result.isSuccess) saved.tryEmit(uri)
        return result
    }

    private companion object {
        const val SAVED_BUFFER = 16
    }
}
