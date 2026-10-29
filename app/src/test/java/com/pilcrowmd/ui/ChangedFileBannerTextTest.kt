// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S8-A52: the changed-file notices have exactly the chosen wording. */
@RunWith(RobolectricTestRunner::class)
class ChangedFileBannerTextTest {

    @Test
    fun `the notice with no unsaved edits has the chosen wording`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "This file was just changed outside PilcrowMD. Tap to reload.",
            context.getString(R.string.main_banner_changed),
        )
    }

    @Test
    fun `the notice with unsaved edits has the same wording before its two choices`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "This file was just changed outside PilcrowMD.",
            context.getString(R.string.main_banner_changed_edits),
        )
    }
}
