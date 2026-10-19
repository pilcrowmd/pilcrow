// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.di

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.domain.model.isRatingCardDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M-176's Play gate, on both sides of the API 30 split: 30+ asks `getInstallSourceInfo`, 26–29 the
 * deprecated `getInstallerPackageName`. Each SDK case sets ONLY the record its own branch reads, so
 * a branch reading the wrong API sees no installer and fails the Play case.
 */
@RunWith(RobolectricTestRunner::class)
class InstallSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun onApi30(installer: String?): InstallSource {
        shadowOf(context.packageManager).setInstallSourceInfo(context.packageName, null, installer)
        return DefaultInstallSource(context)
    }

    private fun onApi29(installer: String?): InstallSource {
        // The framework setter (deprecated at 30, like the getter), backed by Robolectric's own store.
        @Suppress("DEPRECATION")
        context.packageManager.setInstallerPackageName(context.packageName, installer)
        return DefaultInstallSource(context)
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 - Play shows the card`() = assertTrue(onApi30(PLAY_STORE_INSTALLER).isPlayInstall())

    @Test
    @Config(sdk = [29])
    fun `API 29 - Play shows the card`() = assertTrue(onApi29(PLAY_STORE_INSTALLER).isPlayInstall())

    @Test
    @Config(sdk = [30])
    fun `API 30 - everything else hides it`() = OTHERS.forEach {
        assertFalse("installer $it", onApi30(it).isPlayInstall())
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 - everything else hides it`() = OTHERS.forEach {
        assertFalse("installer $it", onApi29(it).isPlayInstall())
    }

    /** A lookup that throws (NameNotFoundException: a package that is not installed) hides, never crashes. */
    @Test
    @Config(sdk = [30])
    fun `a failed lookup hides it`() {
        val notInstalled = object : ContextWrapper(context) {
            override fun getPackageName() = "com.example.not.installed"
        }
        assertFalse(DefaultInstallSource(notInstalled).isPlayInstall())
        assertNull(DefaultInstallSource(notInstalled).firstInstallTimeMillis())
    }

    /**
     * The failed lookup's install time, fed to M-177's gate with every other gate open: the card
     * stays hidden. A 0 fallback would read as "installed in 1970" and pass the 7-day gate.
     */
    @Test
    @Config(sdk = [30])
    fun `an unreadable install time hides the rating card`() {
        val notInstalled = object : ContextWrapper(context) {
            override fun getPackageName() = "com.example.not.installed"
        }
        val firstInstall = DefaultInstallSource(notInstalled).firstInstallTimeMillis()
        assertFalse(
            isRatingCardDue(
                isPlayInstall = true,
                opens = 50,
                firstInstallMillis = firstInstall,
                nowMillis = 1_800_000_000_000L,
                dismissed = false,
                updateNoteDue = false,
            ),
        )
    }

    @Test
    fun `first install time comes from PackageInfo`() {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = 1_234_567L
        shadowOf(context.packageManager).installPackage(info)
        assertEquals(1_234_567L, DefaultInstallSource(context).firstInstallTimeMillis())
    }

    private companion object {
        val OTHERS = listOf(null, "org.fdroid.fdroid", "com.google.android.packageinstaller")
    }
}
