// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **M-242.** The share sheet lists the app only when the type the sender puts on an `ACTION_SEND`
 * intent matches the manifest's `ACTION_SEND` filter. Some file managers label a `.md` file with a
 * generic or non-standard type rather than `text/markdown`, so the filter has to accept those too,
 * but not every type: an image must not list the app.
 *
 * Robolectric resolves intents against the merged manifest's intent filters, so these checks read
 * the filter that ships.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityShareFilterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shareResolvesToMainActivity(mimeType: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, Uri.parse("content://m242/doc.md"))
            setPackage(context.packageName)
        }
        return context.packageManager.queryIntentActivities(intent, 0)
            .any { it.activityInfo.name == MainActivity::class.java.name }
    }

    @Test
    fun shareOfEachMarkdownTypeResolvesToMainActivity() {
        val types = listOf(
            "text/plain",
            "text/markdown",
            "application/octet-stream",
            "text/x-markdown",
            "text/x-web-markdown",
            "application/x-markdown",
        )
        val missing = types.filterNot(::shareResolvesToMainActivity)

        assertEquals("ACTION_SEND must resolve to MainActivity for every type", emptyList<String>(), missing)
    }

    @Test
    fun shareOfAnImageDoesNotResolveToMainActivity() {
        // Control: the positive cases above must come from the declared types, not from a filter
        // that matches everything.
        assertTrue("text/markdown must resolve", shareResolvesToMainActivity("text/markdown"))
        assertFalse("image/png must not resolve", shareResolvesToMainActivity("image/png"))
    }
}
