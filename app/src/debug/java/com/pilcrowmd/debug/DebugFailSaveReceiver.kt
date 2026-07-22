// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pilcrowmd.PilcrowApplication
import com.pilcrowmd.repository.LocalFileRepository

/**
 * DEBUG-ONLY. Lives in `src/debug`, so it is compiled into debug builds only and is entirely absent
 * from release. Arms a one-shot "fail the next save" fault so the failed-save UX can be verified
 * deterministically on a device (the organic triggers — Drive offline cache, MediaStore recreating a
 * deleted target — get silently rescued by the platform, so a real failure can't be forced reliably).
 *
 *   1. open a file in Pilcrow and make an edit (do not save yet);
 *   2. `adb shell am broadcast -n com.pilcrowmd/.debug.DebugFailSaveReceiver`
 *      → arms the next [LocalFileRepository.saveFile] to abort at the target-open step exactly as a
 *        real I/O failure would (it throws BEFORE the "wt" open truncates the file);
 *   3. tap Save in Pilcrow → "Save failed" surfaces via the normal snackbar, the edit stays in the
 *      editor, and the original file is byte-for-byte intact (the target was never opened for write).
 *
 * One-shot: the flag is consumed by the failing save, so the save after it succeeds normally.
 */
class DebugFailSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PilcrowApplication ?: return
        val repo = app.container.fileRepository as? LocalFileRepository ?: return
        repo.debugFailNextSaveArmed = true
        Log.w(TAG, "Armed: the next save in Pilcrow will fail. Edit a file and tap Save.")
    }

    private companion object {
        const val TAG = "DebugFailSaveReceiver"
    }
}
