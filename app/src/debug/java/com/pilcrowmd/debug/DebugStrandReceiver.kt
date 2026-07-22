// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.pilcrowmd.PilcrowApplication
import com.pilcrowmd.repository.LocalFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * DEBUG-ONLY. Lives in `src/debug`, so it is compiled into debug builds only and is entirely
 * absent from release. Produces a genuinely *stranded* WAL slot in one shot so the escape hatch
 * can be verified on a device without the flaky "open a file, truncate it,
 * then delete it" dance — a SAF "wt" open RE-CREATES a deleted target, so deletion alone does not
 * strand a slot. Instead this stages a slot whose target URI can never be committed:
 *
 *   1. `adb shell am broadcast -n com.pilcrowmd/.debug.DebugStrandReceiver`
 *      → stages content + the commit marker to the WAL against a deliberately un-writable URI
 *        (an ExternalStorageProvider path under a folder the app holds no grant for, so the
 *        "wt" open throws — exactly the uncommittable state launch recovery cannot resolve);
 *   2. relaunch Pilcrow → launch recovery can't commit it → the slot is left STRANDED →
 *      the "Recover unsaved files" dialog (launcher start) or the red indicator (intent open)
 *      surfaces it for "Save a copy" / "Discard".
 *
 * Optionally pass `--es key <suffix>` to strand a distinctly-named second slot for the multi-slot
 * test (e.g. `--es key B` → "Recovered note B.md").
 */
class DebugStrandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PilcrowApplication ?: return
        val repo = app.container.fileRepository as? LocalFileRepository ?: return

        val suffix = intent.getStringExtra("key")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        // A path-based ExternalStorageProvider URI under a folder the app was never granted: any
        // "wt" open throws (no grant / missing parent), so the slot can never be committed.
        val target = Uri.parse(
            "content://com.android.externalstorage.documents/document/" +
                Uri.encode("primary:Download/__pilcrow_stranded__/Recovered note$suffix.md"),
        )
        val content = "STRANDED SLOT TEST$suffix\n\n" +
            "These are the recovered bytes — a copy saved from here must contain this text.\n"

        val pending = goAsync() // keep the receiver alive across the suspend work
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(TIMEOUT_MS) {
                    // debugStageWithoutCommit stages the slot durably, THEN opens the target "wt".
                    // For this deliberately un-writable target that open throws — which is exactly what
                    // we want: a FAILURE result here means the slot was staged but can't be committed,
                    // i.e. stranded. A SUCCESS would mean the target was writable after all, leaving a
                    // recoverable (not stranded) slot — so flag that as misconfigured.
                    // A FAILURE result is the intended path: the slot is staged durably, then the
                    // target "wt" open throws (un-writable) → the slot can't be committed → stranded.
                    // A SUCCESS would mean the target was writable, leaving a recoverable (not
                    // stranded) slot — so flag that as a misconfigured target URI.
                    repo.debugStageWithoutCommit(target, content)
                        .onFailure { Log.w(TAG, "Stranded slot staged; relaunch Pilcrow to surface it.") }
                        .onSuccess { Log.w(TAG, "Target writable — slot is NOT stranded; use an unwritable URI.") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "debug hook error: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugStrandReceiver"
        const val TIMEOUT_MS = 5000L
    }
}
