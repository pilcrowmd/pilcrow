// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M-176: the store hand-off, and its https fallback when nothing handles `market://`. */
@RunWith(RobolectricTestRunner::class)
class StoreListingTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    /**
     * Records every start as `package|uri`; refuses the schemes in [missing] the way a device with
     * no handler does.
     */
    private class Recording(base: Context, private val missing: Set<String>) : ContextWrapper(base) {
        val started = mutableListOf<String>()
        override fun startActivity(intent: Intent) {
            if (intent.data?.scheme in missing) throw ActivityNotFoundException()
            started += "${intent.`package`}|${intent.data}"
        }
    }

    // The market link is pinned to the Play Store app; the browser fallback is not pinned at all.
    private val market = "com.android.vending|market://details?id=${app.packageName}"
    private val https = "null|https://play.google.com/store/apps/details?id=${app.packageName}"

    @Test
    fun `opens the market link when something handles it`() {
        val ctx = Recording(app, missing = emptySet())
        openStoreListing(ctx)
        assertEquals(listOf(market), ctx.started)
    }

    @Test
    fun `falls back to the https listing when nothing handles market`() {
        val ctx = Recording(app, missing = setOf("market"))
        openStoreListing(ctx)
        assertEquals(listOf(https), ctx.started)
    }

    @Test
    fun `nothing to open at all does not crash`() {
        val ctx = Recording(app, missing = setOf("market", "https"))
        openStoreListing(ctx)
        assertEquals(emptyList<String>(), ctx.started)
    }
}
