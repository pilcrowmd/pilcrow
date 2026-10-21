// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * M-392: the two Settings contact hand-offs. The application context is used on purpose: it is not an
 * Activity, so a start without FLAG_ACTIVITY_NEW_TASK would be refused on a device.
 *
 * "Nothing handles the intent" is simulated with `checkActivities(true)`, which makes Robolectric
 * throw ActivityNotFoundException for an intent no registered activity resolves, as a device does.
 */
@RunWith(RobolectricTestRunner::class)
class ContactLinksTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun startedIntent(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `email us starts a SENDTO to the contact address with the subject in the URI`() {
        openEmailUs(app)

        val intent = startedIntent()
        assertNotNull("no activity was started", intent)
        assertEquals(Intent.ACTION_SENDTO, intent!!.action)
        assertEquals("mailto:pilcrowmd@gmail.com?subject=PilcrowMD", intent.data.toString())
        assertEquals("PilcrowMD", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(
            "a non-Activity context needs FLAG_ACTIVITY_NEW_TASK",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }

    @Test
    fun `report an issue starts a VIEW of the GitHub issue chooser`() {
        openIssueReport(app)

        val intent = startedIntent()
        assertNotNull("no activity was started", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("https://github.com/pilcrowmd/pilcrow/issues/new/choose", intent.data.toString())
        assertTrue(
            "a non-Activity context needs FLAG_ACTIVITY_NEW_TASK",
            (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
        )
    }

    @Test
    fun `email us with no email app shows the fallback toast and does not throw`() {
        shadowOf(app).checkActivities(true)

        openEmailUs(app)

        assertNull("nothing should have started", startedIntent())
        assertEquals("No email app found. Write to pilcrowmd@gmail.com", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `report an issue with no browser shows the fallback toast and does not throw`() {
        shadowOf(app).checkActivities(true)

        openIssueReport(app)

        assertNull("nothing should have started", startedIntent())
        assertEquals(
            "No browser found. The address is github.com/pilcrowmd/pilcrow/issues",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    /** A context whose system refuses the start, as a managed profile or restricted component does. */
    private class Refusing(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = throw SecurityException("refused")
    }

    @Test
    fun `email us the system refuses shows the fallback toast and does not throw`() {
        openEmailUs(Refusing(app))

        assertEquals("No email app found. Write to pilcrowmd@gmail.com", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `report an issue the system refuses shows the fallback toast and does not throw`() {
        openIssueReport(Refusing(app))

        assertEquals(
            "No browser found. The address is github.com/pilcrowmd/pilcrow/issues",
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
