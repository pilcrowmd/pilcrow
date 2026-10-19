// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import android.content.Context
import android.os.Build

/**
 * Facts about how this copy of the app was installed. **M-176, M-177.**
 *
 * They can only come from the device: the Play, F-Droid and GitHub builds are the same bytes, so no
 * build flag can tell them apart. Both reads are `PackageManager` binder calls, so they are plain
 * blocking functions here and the caller moves them off the main thread.
 */
interface InstallSource {
    /** True only when Google Play installed the app. Anything unknown answers false. */
    fun isPlayInstall(): Boolean

    /**
     * `PackageInfo.firstInstallTime`: the original install, which survives updates. Null if
     * unreadable, which hides the rating card (M-177): when in doubt, hide.
     */
    fun firstInstallTimeMillis(): Long?
}

/** The installer package name Google Play records. */
internal const val PLAY_STORE_INSTALLER = "com.android.vending"

class DefaultInstallSource(private val context: Context) : InstallSource {

    // runCatching, as DefaultAppInfo does: a NameNotFoundException or any other failure must hide
    // the Play-only cards, never crash the welcome screen. When in doubt, hide (M-176).
    override fun isPlayInstall(): Boolean = runCatching { installerPackageName() }.getOrNull() == PLAY_STORE_INSTALLER

    // Null, not 0: an epoch-zero install time would pass the 7-day gate on every device.
    override fun firstInstallTimeMillis(): Long? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }
            .getOrNull()

    // getInstallerPackageName is deprecated from API 30, which is why the split exists (minSdk 26).
    // Asking about our own package needs no permission and no <queries> entry.
    private fun installerPackageName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}
