// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-70, security lock 1: the app never asks for network access. Read from the merged manifest the
 * build produced, so a library that adds INTERNET in its own manifest fails this too, not just a
 * line in ours.
 */
@RunWith(RobolectricTestRunner::class)
class NoInternetPermissionTest {

    @Test
    fun `the app does not request the INTERNET permission`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertFalse(
            "INTERNET is requested: ${requested.joinToString()}",
            "android.permission.INTERNET" in requested,
        )
    }
}
