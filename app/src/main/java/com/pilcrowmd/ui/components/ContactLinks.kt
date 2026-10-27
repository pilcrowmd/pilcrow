// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.pilcrowmd.R

/** Where mail to the developer goes. */
internal const val CONTACT_EMAIL = "pilcrowmd@gmail.com"

/** The GitHub issue chooser (bug report, feature request). */
internal const val ISSUE_REPORT_URL = "https://github.com/pilcrowmd/pilcrow/issues/new/choose"

/**
 * Open the user's email app with a new message to [CONTACT_EMAIL]. Nothing is sent unless the user
 * taps send; no network use here. With no app, or one the system will not open, a toast gives the
 * address instead.
 *
 * The subject is in the URI as well as in `EXTRA_SUBJECT` because Gmail ignores `EXTRA_SUBJECT` on
 * `ACTION_SENDTO`. This is a try/catch and
 * NOT a `resolveActivity` check on purpose: from API 30 package visibility makes `resolveActivity`
 * return null without a `<queries>` entry (see [openStoreListing]).
 */
fun openEmailUs(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL?subject=" + Uri.encode("PilcrowMD")))
        .putExtra(Intent.EXTRA_SUBJECT, "PilcrowMD")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startOrToast(context, intent, context.getString(R.string.contact_no_email_app, CONTACT_EMAIL))
}

/**
 * Open the GitHub issue chooser in the user's browser. With no browser, or one the system will not
 * open, a toast gives the address instead. Try/catch, not `resolveActivity`, for the same
 * package-visibility reason as [openEmailUs].
 */
fun openIssueReport(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_REPORT_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = context.getString(R.string.contact_no_browser, "github.com/pilcrowmd/pilcrow/issues")
    startOrToast(context, intent, fallback)
}

/**
 * Start [intent]. No handler throws [ActivityNotFoundException]; a component the system will not
 * open (managed profile, restricted app) throws [SecurityException]. Both show [fallback].
 */
private fun startOrToast(context: Context, intent: Intent, fallback: String) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, fallback, Toast.LENGTH_LONG).show()
    }
}
