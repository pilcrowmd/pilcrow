// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pilcrowmd.PilcrowApplication
import com.pilcrowmd.repository.FileText
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
                    val marker = "\n\n<!-- recovered-by-WAL ${System.currentTimeMillis()} -->\n"
                    val staged = walStagedContent(repo.readFile(uri), marker)
                    if (staged == null) {
                        Log.w(TAG, "Not staged: $uri could not be read or is not UTF-8.")
                        return@withTimeout
                    }
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

/**
 * What the hook stages for a file it has [read]: its text plus [marker], or null to leave the file
 * alone. Recovery writes the staged text back as UTF-8, so a file that is not UTF-8 is never staged
 * (NEW-12), and a failed read is not turned into empty content that would replace the file.
 */
internal fun walStagedContent(read: Result<FileText>, marker: String): String? =
    read.getOrNull()?.takeIf { it.isUtf8 }?.let { it.content + marker }
