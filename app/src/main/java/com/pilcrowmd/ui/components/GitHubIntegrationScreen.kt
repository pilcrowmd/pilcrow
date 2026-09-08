// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilcrowmd.ui.theme.mdColors

/**
 * GitHubIntegrationScreen: the feature-request sub-screen, reached from the Settings → Feedback
 * card. View-layer only — no ViewModel / data / save-path contact. Mirrors the [LicensesScreen]
 * modal pattern (header + ArrowBack close).
 *
 * It promises nothing and carries no date. GitHub Markdown browsing is named only as an EXAMPLE of
 * a request people have made, never as a commitment — the screen's job is to collect what
 * users want built, not to advertise a roadmap.
 *
 * The button fires an ACTION_SENDTO `mailto:` intent (only email apps respond), pre-addressed to
 * pilcrowmd@gmail.com with a fixed subject. This is the app's only feedback channel and is kept
 * deliberately. If no email app is installed it degrades gracefully (a toast, never a crash —
 * Safeguard 3 spirit). Colours come only from the token layer (Safeguard 4).
 */
@Composable
fun GitHubIntegrationScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val c = mdColors()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(c.primaryBackground),
    ) {
        // Header (mirrors LicensesScreen)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Tell us what to build",
                color = c.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Close",
                tint = c.secondaryText,
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onClose() },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.secondarySurface)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Tell us what to build",
                color = c.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "What gets built next is decided by what people ask for. This is how you " +
                    "ask.",
                color = c.secondaryText,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Opening Markdown straight from a GitHub repository is one thing people " +
                    "have asked for. If that's what you want, say so — or tell us something else " +
                    "entirely. Describing the workflow you have in mind helps most.",
                color = c.secondaryText,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(18.dp))
            RequestFeatureButton(onClick = { sendFeatureRequest(context) })
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Opens your email app — nothing is sent unless you tap send.",
                color = c.secondaryText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Accent primary button (token colours only, Safeguard 4). */
@Composable
private fun RequestFeatureButton(onClick: () -> Unit) {
    val c = mdColors()
    Text(
        text = "Send us a request",
        color = c.onAccent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.accent)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    )
}

/**
 * Open the system email composer pre-addressed to [FEEDBACK_EMAIL] with [FEEDBACK_SUBJECT] already
 * filled, via ACTION_SENDTO `mailto:` (only true email apps resolve this, so no unrelated app is
 * offered). If there is no email app, show a toast instead of crashing.
 *
 * **The subject travels in the mailto URI, not in EXTRA_SUBJECT, and that is not a style choice.**
 * Gmail — the default handler on the test device, and the most common one — parses the `mailto:`
 * URI per RFC 6068 and **ignores `EXTRA_SUBJECT` entirely** on ACTION_SENDTO. Verified on device
 * (S24+, 2026-09-04): with the extra alone the composer opened with an EMPTY Subject field; with
 * the subject in the URI it arrives filled. The extra is kept alongside it purely as a fallback for
 * clients that read extras instead — it costs nothing and neither form is authoritative everywhere.
 *
 * The subject names the app because it lands in a personal inbox that receives more than this app's
 * mail — a bare "Feature request" is not identifiable there. Owner request from device UAT.
 */
private fun sendFeatureRequest(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$FEEDBACK_EMAIL?subject=" + Uri.encode(FEEDBACK_SUBJECT))
        putExtra(Intent.EXTRA_SUBJECT, FEEDBACK_SUBJECT)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No email app found — write to $FEEDBACK_EMAIL", Toast.LENGTH_LONG).show()
    }
}

/** Where feature requests go. The app's only feedback channel — see [sendFeatureRequest]. */
private const val FEEDBACK_EMAIL = "pilcrowmd@gmail.com"

/** Names the app so the mail is identifiable in an inbox that receives more than this app's mail. */
private const val FEEDBACK_SUBJECT = "PilcrowMD feature request"
