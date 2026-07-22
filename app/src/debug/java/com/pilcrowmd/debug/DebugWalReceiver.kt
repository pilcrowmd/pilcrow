// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pilcrowmd.PilcrowApplication
import com.pilcrowmd.repository.LocalFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * DEBUG-ONLY. Lives in `src/debug`, so it is compiled into debug builds only and is entirely
 * absent from release. Reproduces a save interrupted mid-write so crash recovery can be shown
 * end-to-end on a device:
 *
 *   1. open a throwaway test .md in Pilcrow (it becomes the "last file");
 *   2. `adb shell am broadcast -n com.pilcrowmd/.debug.DebugWalReceiver`
 *      → stages the file's content (plus a marker) to the WAL and truncates the file on disk;
 *   3. `adb shell am force-stop com.pilcrowmd`  (kill in the "mid-write" window);
 *   4. relaunch Pilcrow → launch-time recovery restores the file;
 *   5. reopen the file → content is intact, with the marker appended (proof recovery ran).
 */
class DebugWalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PilcrowApplication ?: return
        val repo = app.container.fileRepository as? LocalFileRepository ?: return
        val storage = app.container.storageManager

        val pending = goAsync() // keep the receiver alive across the suspend work
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    val uri = storage.lastFileUri.first()
                    if (uri == null) {
                        Log.w(TAG, "No last file open — open a throwaway .md in Pilcrow first.")
                        return@withTimeout
                    }
                    val current = repo.readFile(uri).getOrDefault("")
                    val staged = current + "\n\n<!-- recovered-by-WAL ${System.currentTimeMillis()} -->\n"
                    repo.debugStageWithoutCommit(uri, staged)
                        .onSuccess {
                            Log.w(TAG, "Staged + truncated $uri. Now force-stop and relaunch to recover.")
                        }
                        .onFailure { Log.w(TAG, "debugStageWithoutCommit failed: ${it.message}") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "debug hook error: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugWalReceiver"
        const val TIMEOUT_MS = 5000L
    }
}
