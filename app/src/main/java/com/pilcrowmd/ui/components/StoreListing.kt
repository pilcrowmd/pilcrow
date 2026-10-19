// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pilcrowmd.di.PLAY_STORE_INSTALLER

/**
 * The rating ask, word for word the same on both cards (M-176, M-177).
 */
internal const val RATING_ASK = "A rating on Google Play helps others find it and keeps this free app going."

/**
 * Open this app's Play listing so the user can rate it (M-176, M-177). No Play Core, no network.
 *
 * `market://` first, pinned to the Play Store app: the button says "Rate on Play", and an unpinned
 * `market://` can land in the F-Droid client (which has no ratings) or a chooser. With the Play
 * Store app missing or disabled, `startActivity` throws and the https listing opens in a browser. This is a
 * try/catch and NOT a `resolveActivity` check on purpose: from API 30 package visibility makes
 * `resolveActivity` return null without a `<queries>` entry, so the fallback would fire on every
 * device. The id comes from [Context.getPackageName], so only the release id has a listing: a debug
 * build (`.debug`) opens a listing that does not exist.
 */
fun openStoreListing(context: Context) {
    val id = context.packageName
    try {
        context.startActivity(storeIntent("market://details?id=$id").setPackage(PLAY_STORE_INSTALLER))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(storeIntent("https://play.google.com/store/apps/details?id=$id"))
        } catch (_: ActivityNotFoundException) {
            // No browser either. Nothing to open, and nothing worth crashing over.
        }
    }
}

private fun storeIntent(uri: String) =
    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
