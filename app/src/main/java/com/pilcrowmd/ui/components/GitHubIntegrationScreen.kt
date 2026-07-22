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
 * GitHubIntegrationScreen: a roadmap-teaser sub-screen for the planned GitHub Markdown-browsing
 * feature, reached from the Settings → Integrations card. View-layer only — no ViewModel / data /
 * save-path contact. Mirrors the [LicensesScreen] modal pattern (header + ArrowBack close).
 *
 * The "Request this feature" button fires an ACTION_SENDTO `mailto:` intent (only email apps
 * respond), pre-addressed to pilcrowmd@gmail.com with a fixed subject. If no email app is installed
 * it degrades gracefully (a toast, never a crash — Safeguard 3 spirit). Colours come only from the
 * token layer (Safeguard 4).
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
                text = "GitHub integration",
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
                text = "GitHub integration",
                color = c.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "We're planning to let you browse and open Markdown straight from your " +
                    "GitHub repositories, without leaving Pilcrow. It's not here yet — but it's " +
                    "on the roadmap.",
                color = c.secondaryText,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "If you'd find it useful, or have a specific workflow in mind, tell us how " +
                    "you'd use it.",
                color = c.secondaryText,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(18.dp))
            RequestFeatureButton(onClick = { requestGitHubIntegration(context) })
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
        text = "Request this feature",
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
 * Open the system email composer pre-addressed to pilcrowmd@gmail.com with a fixed subject via
 * ACTION_SENDTO `mailto:` (only true email apps resolve this, so no unrelated app is offered). If
 * there is no email app, show a toast instead of crashing.
 */
private fun requestGitHubIntegration(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.fromParts("mailto", "pilcrowmd@gmail.com", null)
        putExtra(Intent.EXTRA_SUBJECT, "GitHub integration request")
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No email app found — write to pilcrowmd@gmail.com", Toast.LENGTH_LONG).show()
    }
}
